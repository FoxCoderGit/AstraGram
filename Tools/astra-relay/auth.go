package main

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

var url64 = base64.RawURLEncoding

func digest(value []byte) string { sum := sha256.Sum256(value); return hex.EncodeToString(sum[:]) }
func randomHex(size int) string {
	value := make([]byte, size)
	if _, err := rand.Read(value); err != nil {
		panic(err)
	}
	return hex.EncodeToString(value)
}

type envelope struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}
type challenge struct {
	device string
	until  int64
}
type accessRequest struct {
	Nonce     string   `json:"nonce"`
	PublicKey string   `json:"public_key"`
	Proof     string   `json:"proof"`
	Lease     envelope `json:"lease"`
}
type tokenClaims struct {
	V         int    `json:"v"`
	Device    string `json:"device"`
	PublicKey string `json:"public_key"`
	Until     int64  `json:"until"`
	ID        string `json:"id"`
}

type relayServer struct {
	config     Config
	signing    *rsa.PrivateKey
	tokenKey   []byte
	filter     destinationFilter
	now        func() time.Time
	mu         sync.Mutex
	policy     Policy
	challenges map[string]challenge
	replay     map[string]int64
	active     map[*wsStream]bool
	devices    map[string]int
	ips        map[string]int
	reserved   int
	bytesUp    atomic.Uint64
	bytesDown  atomic.Uint64
	started    int64
	dial       func(context.Context, string, string) (net.Conn, error)
	rates      rateBudget
}

func newRelay(c Config) (*relayServer, error) {
	raw, err := os.ReadFile(c.SigningKey)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(raw)
	if block == nil {
		return nil, errors.New("invalid policy signing key")
	}
	var key *rsa.PrivateKey
	if parsed, e := x509.ParsePKCS8PrivateKey(block.Bytes); e == nil {
		key, _ = parsed.(*rsa.PrivateKey)
	}
	if key == nil {
		key, err = x509.ParsePKCS1PrivateKey(block.Bytes)
		if err != nil {
			return nil, err
		}
	}
	if key.N.BitLen() < 2048 {
		return nil, errors.New("policy signing key too small")
	}
	tokenKey, err := os.ReadFile(c.TokenKey)
	if os.IsNotExist(err) {
		tokenKey = make([]byte, 32)
		if _, err = rand.Read(tokenKey); err != nil {
			return nil, err
		}
		if err = os.WriteFile(c.TokenKey, tokenKey, 0600); err != nil {
			return nil, err
		}
	} else if err != nil {
		return nil, err
	}
	if len(tokenKey) != 32 {
		return nil, errors.New("invalid token signing key")
	}
	pair, err := tls.LoadX509KeyPair(c.TLSCert, c.TLSKey)
	if err != nil {
		return nil, err
	}
	leaf, err := x509.ParseCertificate(pair.Certificate[0])
	if err != nil {
		return nil, err
	}
	if err = leaf.VerifyHostname(c.Endpoint.Host); err != nil {
		return nil, err
	}
	c.Endpoint.Pins = []string{digest(leaf.RawSubjectPublicKeyInfo)}
	filter, err := newFilter(c.CIDRs, c.Ports)
	if err != nil {
		return nil, err
	}
	s := &relayServer{config: c, signing: key, tokenKey: tokenKey, filter: filter, now: time.Now, started: time.Now().Unix(), policy: Policy{Revision: 1, Enabled: true, Denied: map[string]bool{}}, challenges: map[string]challenge{}, replay: map[string]int64{}, active: map[*wsStream]bool{}, devices: map[string]int{}, ips: map[string]int{}}
	if data, e := os.ReadFile(c.PolicyFile); e == nil {
		if err = json.Unmarshal(data, &s.policy); err != nil {
			return nil, err
		}
	} else if !os.IsNotExist(e) {
		return nil, e
	} else if err = atomicJSON(c.PolicyFile, s.policy); err != nil {
		return nil, err
	}
	if s.policy.Revision == 0 || s.policy.Denied == nil {
		return nil, errors.New("invalid relay policy")
	}
	return s, nil
}

func (s *relayServer) sign(value any) (envelope, error) {
	payload, err := json.Marshal(value)
	if err != nil {
		return envelope{}, err
	}
	hash := sha256.Sum256(payload)
	signature, err := rsa.SignPKCS1v15(rand.Reader, s.signing, crypto.SHA256, hash[:])
	if err != nil {
		return envelope{}, err
	}
	return envelope{url64.EncodeToString(payload), url64.EncodeToString(signature)}, nil
}
func parseDevice(encoded string) (*ecdsa.PublicKey, string, error) {
	if len(encoded) > 512 {
		return nil, "", errors.New("device key too large")
	}
	der, err := url64.DecodeString(encoded)
	if err != nil {
		return nil, "", err
	}
	parsed, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, "", err
	}
	key, ok := parsed.(*ecdsa.PublicKey)
	if !ok || key.Curve != elliptic.P256() {
		return nil, "", errors.New("invalid device key")
	}
	return key, digest(der), nil
}
func (s *relayServer) validLease(lease envelope, device string, now int64) (int64, error) {
	if len(lease.Payload) > 8192 || len(lease.Signature) > 1024 {
		return 0, errors.New("invalid lease size")
	}
	payload, err := url64.DecodeString(lease.Payload)
	if err != nil {
		return 0, err
	}
	signature, err := url64.DecodeString(lease.Signature)
	if err != nil {
		return 0, err
	}
	hash := sha256.Sum256(payload)
	if err = rsa.VerifyPKCS1v15(&s.signing.PublicKey, crypto.SHA256, hash[:], signature); err != nil {
		return 0, err
	}
	var claims struct {
		V      int    `json:"v"`
		App    string `json:"app"`
		Device string `json:"device"`
		Issued int64  `json:"issued_at"`
		Until  int64  `json:"until"`
	}
	if err = json.Unmarshal(payload, &claims); err != nil {
		return 0, err
	}
	if claims.V != 1 || claims.App != appID || claims.Device != device || claims.Until <= now || claims.Issued > now+60 || claims.Until <= claims.Issued || claims.Until-claims.Issued > daySeconds {
		return 0, errors.New("expired or unrelated lease")
	}
	return claims.Until, nil
}
func verifyProof(key *ecdsa.PublicKey, encoded, message string) bool {
	if len(encoded) > 256 {
		return false
	}
	signature, err := url64.DecodeString(encoded)
	if err != nil {
		return false
	}
	hash := sha256.Sum256([]byte(message))
	return ecdsa.VerifyASN1(key, hash[:], signature)
}
func peerIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return "unknown"
	}
	return host
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}
func readJSON(w http.ResponseWriter, r *http.Request, value any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 16384)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		writeJSON(w, 400, map[string]string{"error": "bad_request"})
		return false
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		writeJSON(w, 400, map[string]string{"error": "bad_request"})
		return false
	}
	return true
}
func (s *relayServer) challengeHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	now := s.now().Unix()
	if !s.rates.take("challenge-ip:"+peerIP(r), now, 120) {
		w.WriteHeader(429)
		return
	}
	var request struct {
		PublicKey string `json:"public_key"`
	}
	if !readJSON(w, r, &request) {
		return
	}
	_, device, err := parseDevice(request.PublicKey)
	if err != nil {
		w.WriteHeader(400)
		return
	}
	if !s.rates.take("challenge-device:"+device, now, 12) {
		w.WriteHeader(429)
		return
	}
	nonce := randomHex(32)
	s.mu.Lock()
	for k, v := range s.challenges {
		if v.until <= now {
			delete(s.challenges, k)
		}
	}
	if len(s.challenges) >= 4096 {
		s.mu.Unlock()
		w.WriteHeader(503)
		return
	}
	s.challenges[nonce] = challenge{device, now + 60}
	s.mu.Unlock()
	writeJSON(w, 200, map[string]any{"nonce": nonce, "server_time": now})
}
func (s *relayServer) accessHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	now := s.now().Unix()
	if !s.rates.take("access-ip:"+peerIP(r), now, 120) {
		w.WriteHeader(429)
		return
	}
	var request accessRequest
	if !readJSON(w, r, &request) {
		return
	}
	key, device, err := parseDevice(request.PublicKey)
	if err != nil {
		w.WriteHeader(401)
		return
	}
	s.mu.Lock()
	challenge, ok := s.challenges[request.Nonce]
	delete(s.challenges, request.Nonce)
	s.mu.Unlock()
	if !ok || challenge.until <= now || challenge.device != device {
		w.WriteHeader(401)
		return
	}
	leaseUntil, err := s.validLease(request.Lease, device, now)
	payload, decodeErr := url64.DecodeString(request.Lease.Payload)
	if err != nil || decodeErr != nil || !verifyProof(key, request.Proof, "astrarelay-policy-v1\n"+request.Nonce+"\n"+digest(payload)) {
		w.WriteHeader(401)
		return
	}
	s.mu.Lock()
	policy := s.policy
	allowed := policy.Enabled && !policy.Denied[device]
	s.mu.Unlock()
	result := map[string]any{"v": 1, "kind": "astrarelay", "app": appID, "device": device, "nonce": request.Nonce, "issued_at": now, "until": now + daySeconds, "revision": policy.Revision, "allowed": allowed}
	if allowed {
		tokenUntil := now + daySeconds
		if leaseUntil < tokenUntil {
			tokenUntil = leaseUntil
		}
		token := tokenClaims{1, device, request.PublicKey, tokenUntil, randomHex(16)}
		raw, _ := json.Marshal(token)
		body := url64.EncodeToString(raw)
		mac := hmac.New(sha256.New, s.tokenKey)
		_, _ = mac.Write([]byte(body))
		result["token"] = body + "." + url64.EncodeToString(mac.Sum(nil))
		result["token_until"] = tokenUntil
		result["endpoint"] = s.config.Endpoint
	} else {
		result["reason"] = "administrative_denial"
	}
	signed, err := s.sign(result)
	if err != nil {
		w.WriteHeader(503)
		return
	}
	writeJSON(w, 200, signed)
}
func (s *relayServer) verifyConnection(r *http.Request, address string, port int) (tokenClaims, int) {
	var claims tokenClaims
	authorization := r.Header.Get("Authorization")
	if !strings.HasPrefix(authorization, "Bearer ") {
		return claims, 401
	}
	token := strings.TrimPrefix(authorization, "Bearer ")
	if len(token) > 4096 {
		return claims, 401
	}
	parts := strings.Split(token, ".")
	if len(parts) != 2 {
		return claims, 401
	}
	mac := hmac.New(sha256.New, s.tokenKey)
	_, _ = mac.Write([]byte(parts[0]))
	signature, err := url64.DecodeString(parts[1])
	if err != nil || !hmac.Equal(mac.Sum(nil), signature) {
		return claims, 401
	}
	raw, err := url64.DecodeString(parts[0])
	if err != nil || json.Unmarshal(raw, &claims) != nil {
		return claims, 401
	}
	now := s.now().Unix()
	if claims.V != 1 || claims.Until <= now {
		return claims, 401
	}
	key, device, err := parseDevice(claims.PublicKey)
	if err != nil || device != claims.Device {
		return claims, 401
	}
	nonce := r.Header.Get("X-Astra-Nonce")
	if len(nonce) != 64 {
		return claims, 401
	}
	if _, err = hex.DecodeString(nonce); err != nil {
		return claims, 401
	}
	stamp := r.Header.Get("X-Astra-Time")
	issued, err := strconv.ParseInt(stamp, 10, 64)
	if err != nil || issued < now-120 || issued > now+120 {
		return claims, 401
	}
	message := "astrarelay-connect-v1\n" + nonce + "\n" + stamp + "\n" + address + "\n" + strconv.Itoa(port) + "\n" + digest([]byte(token))
	if !verifyProof(key, r.Header.Get("X-Astra-Proof"), message) {
		return claims, 401
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.policy.Enabled || s.policy.Denied[device] {
		return claims, 403
	}
	for k, until := range s.replay {
		if until <= now {
			delete(s.replay, k)
		}
	}
	replayKey := device + ":" + nonce
	if _, ok := s.replay[replayKey]; ok || len(s.replay) >= 16384 {
		return claims, 401
	}
	s.replay[replayKey] = now + 240
	return claims, 0
}
