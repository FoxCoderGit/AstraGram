package main

import (
	"bufio"
	"bytes"
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

type testDevice struct {
	key            *ecdsa.PrivateKey
	public, device string
	lease          envelope
}

func testServer(t *testing.T) *relayServer {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	filter, err := newFilter(telegramCIDRs, []int{80, 443, 5222})
	if err != nil {
		t.Fatal(err)
	}
	return &relayServer{config: Config{Endpoint: Endpoint{Host: "relay.example.test", IPs: []string{"93.88.204.2"}, Port: 9446, Path: "/v1/tunnel", Pins: []string{"test-only"}}, PolicyFile: filepath.Join(t.TempDir(), "policy.json"), MaxDeviceConnections: 3, MaxIPConnections: 4}, signing: key, tokenKey: bytes.Repeat([]byte{37}, 32), filter: filter, now: time.Now, policy: Policy{Revision: 1, Enabled: true, Denied: map[string]bool{}}, challenges: map[string]challenge{}, replay: map[string]int64{}, active: map[*wsStream]bool{}, devices: map[string]int{}, ips: map[string]int{}}
}
func newDevice(t *testing.T, s *relayServer) testDevice {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	d := testDevice{key: key, public: url64.EncodeToString(der), device: digest(der)}
	d.lease, err = s.sign(map[string]any{"v": 1, "app": appID, "device": d.device, "issued_at": s.now().Unix(), "until": s.now().Unix() + daySeconds})
	if err != nil {
		t.Fatal(err)
	}
	return d
}
func proof(t *testing.T, key *ecdsa.PrivateKey, value string) string {
	t.Helper()
	sum := sha256.Sum256([]byte(value))
	sig, err := ecdsa.SignASN1(rand.Reader, key, sum[:])
	if err != nil {
		t.Fatal(err)
	}
	return url64.EncodeToString(sig)
}
func callJSON(t *testing.T, handler http.Handler, path string, value any) *httptest.ResponseRecorder {
	t.Helper()
	body, err := json.Marshal(value)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest("POST", path, bytes.NewReader(body))
	request.RemoteAddr = "203.0.113.10:1234"
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	return response
}
func access(t *testing.T, s *relayServer, d testDevice) (accessRequest, *httptest.ResponseRecorder) {
	t.Helper()
	response := callJSON(t, s.handler(), "/v1/challenge", map[string]string{"public_key": d.public})
	if response.Code != 200 {
		t.Fatalf("challenge: %d", response.Code)
	}
	var challenge struct {
		Nonce string `json:"nonce"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &challenge); err != nil {
		t.Fatal(err)
	}
	payload, _ := url64.DecodeString(d.lease.Payload)
	request := accessRequest{Nonce: challenge.Nonce, PublicKey: d.public, Lease: d.lease, Proof: proof(t, d.key, "astrarelay-policy-v1\n"+challenge.Nonce+"\n"+digest(payload))}
	return request, callJSON(t, s.handler(), "/v1/access", request)
}
func signedResult(t *testing.T, s *relayServer, response *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	if response.Code != 200 {
		t.Fatalf("access response %d", response.Code)
	}
	var signed envelope
	if err := json.Unmarshal(response.Body.Bytes(), &signed); err != nil {
		t.Fatal(err)
	}
	payload, err := url64.DecodeString(signed.Payload)
	if err != nil {
		t.Fatal(err)
	}
	signature, _ := url64.DecodeString(signed.Signature)
	sum := sha256.Sum256(payload)
	if err = rsa.VerifyPKCS1v15(&s.signing.PublicKey, crypto.SHA256, sum[:], signature); err != nil {
		t.Fatal(err)
	}
	var result map[string]any
	if err = json.Unmarshal(payload, &result); err != nil {
		t.Fatal(err)
	}
	return result
}
func connectRequest(t *testing.T, s *relayServer, d testDevice, token, address string, port int) *http.Request {
	t.Helper()
	r := httptest.NewRequest("GET", "/v1/tunnel", nil)
	nonce := randomHex(32)
	stamp := strconv.FormatInt(s.now().Unix(), 10)
	r.Header.Set("Authorization", "Bearer "+token)
	r.Header.Set("X-Astra-Nonce", nonce)
	r.Header.Set("X-Astra-Time", stamp)
	r.Header.Set("X-Astra-Proof", proof(t, d.key, "astrarelay-connect-v1\n"+nonce+"\n"+stamp+"\n"+address+"\n"+strconv.Itoa(port)+"\n"+digest([]byte(token))))
	return r
}
func TestAccessAndReplay(t *testing.T) {
	s := testServer(t)
	d := newDevice(t, s)
	request, response := access(t, s, d)
	result := signedResult(t, s, response)
	if result["allowed"] != true || result["device"] != d.device || result["nonce"] != request.Nonce {
		t.Fatal("policy binding failed")
	}
	if callJSON(t, s.handler(), "/v1/access", request).Code != 401 {
		t.Fatal("policy replay accepted")
	}
	token := result["token"].(string)
	r := connectRequest(t, s, d, token, "149.154.167.50", 443)
	if claims, status := s.verifyConnection(r, "149.154.167.50", 443); status != 0 || claims.Device != d.device {
		t.Fatal("connection authorization failed", status)
	}
	if _, status := s.verifyConnection(r, "149.154.167.50", 443); status != 401 {
		t.Fatal("connection replay accepted")
	}
	if _, status := s.verifyConnection(connectRequest(t, s, d, token, "149.154.167.50", 443), "91.108.56.1", 443); status != 401 {
		t.Fatal("destination substitution accepted")
	}
	bad := connectRequest(t, s, d, token, "149.154.167.50", 443)
	bad.Header.Set("X-Astra-Proof", "invalid")
	if _, status := s.verifyConnection(bad, "149.154.167.50", 443); status != 401 {
		t.Fatal("forged proof accepted")
	}
	s.now = func() time.Time { return time.Unix(int64(result["token_until"].(float64))+1, 0) }
	if _, status := s.verifyConnection(connectRequest(t, s, d, token, "149.154.167.50", 443), "149.154.167.50", 443); status != 401 {
		t.Fatal("expired token accepted")
	}
}
func TestAdministrativeDenial(t *testing.T) {
	s := testServer(t)
	d := newDevice(t, s)
	_, response := access(t, s, d)
	token := signedResult(t, s, response)["token"].(string)
	disabled := false
	if err := s.setPolicy(&disabled, "", nil); err != nil {
		t.Fatal(err)
	}
	_, response = access(t, s, d)
	result := signedResult(t, s, response)
	if result["allowed"] != false || result["reason"] != "administrative_denial" || result["token"] != nil || result["endpoint"] != nil {
		t.Fatal("denial indistinguishable from failure")
	}
	if int64(result["until"].(float64)-result["issued_at"].(float64)) != daySeconds {
		t.Fatal("denial duration")
	}
	if _, status := s.verifyConnection(connectRequest(t, s, d, token, "149.154.167.50", 443), "149.154.167.50", 443); status != 403 {
		t.Fatal("disabled relay accepted tunnel")
	}
	enabled := true
	denied := true
	if err := s.setPolicy(&enabled, d.device, &denied); err != nil {
		t.Fatal(err)
	}
	_, response = access(t, s, d)
	if signedResult(t, s, response)["allowed"] != false {
		t.Fatal("device ban ignored")
	}
	denied = false
	if err := s.setPolicy(nil, d.device, &denied); err != nil {
		t.Fatal(err)
	}
	_, response = access(t, s, d)
	if signedResult(t, s, response)["allowed"] != true {
		t.Fatal("reenabling failed")
	}
}
func TestInvalidLeasesAndChallenges(t *testing.T) {
	s := testServer(t)
	d := newDevice(t, s)
	for _, claims := range []map[string]any{
		{"v": 1, "app": "other.app", "device": d.device, "issued_at": s.now().Unix(), "until": s.now().Unix() + 300},
		{"v": 1, "app": appID, "device": "another-device", "issued_at": s.now().Unix(), "until": s.now().Unix() + 300},
		{"v": 1, "app": appID, "device": d.device, "issued_at": s.now().Unix() - 1000, "until": s.now().Unix() - 1},
		{"v": 1, "app": appID, "device": d.device, "issued_at": s.now().Unix(), "until": s.now().Unix() + daySeconds + 1},
	} {
		d.lease, _ = s.sign(claims)
		_, response := access(t, s, d)
		if response.Code != 401 {
			t.Fatal("invalid lease accepted")
		}
	}
	if callJSON(t, s.handler(), "/v1/challenge", map[string]string{"public_key": "invalid"}).Code != 400 {
		t.Fatal("invalid key accepted")
	}
	response := httptest.NewRecorder()
	s.handler().ServeHTTP(response, httptest.NewRequest("POST", "/policy", nil))
	if response.Code != 404 {
		t.Fatal("public admin endpoint exposed")
	}
}
func TestDestinationAndCapacityLimits(t *testing.T) {
	s := testServer(t)
	for _, address := range []string{"127.0.0.1", "::1", "10.0.0.1", "169.254.169.254", "0.0.0.0", "8.8.8.8", "telegram.org", "239.1.2.3", "::ffff:127.0.0.1"} {
		if s.filter.allows(address, 443) {
			t.Fatal("non-Telegram destination accepted", address)
		}
	}
	if !s.filter.allows("149.154.167.50", 443) || !s.filter.allows("2001:b28:f23d::1", 5222) || s.filter.allows("149.154.167.50", 22) {
		t.Fatal("destination filter")
	}
	if !s.reserve("device", "ip") || !s.reserve("device", "ip") || !s.reserve("device", "ip") || s.reserve("device", "ip") {
		t.Fatal("device capacity")
	}
	for i := 0; i < 3; i++ {
		s.release("device", "ip")
	}
	if s.reserved != 0 || len(s.devices) != 0 || len(s.ips) != 0 {
		t.Fatal("reservation leak")
	}
	for i := 0; i < 12; i++ {
		if !s.rates.take("test", 100, 12) {
			t.Fatal("rate limit too early")
		}
	}
	if s.rates.take("test", 100, 12) || !s.rates.take("test", 161, 12) {
		t.Fatal("rate limit window")
	}
}
func masked(opcode byte, payload []byte, fin bool) []byte {
	header := []byte{opcode}
	if fin {
		header[0] |= 128
	}
	switch {
	case len(payload) < 126:
		header = append(header, 128|byte(len(payload)))
	case len(payload) <= 65535:
		header = append(header, 128|126, byte(len(payload)>>8), byte(len(payload)))
	default:
		header = append(header, 128|127)
		header = binary.BigEndian.AppendUint64(header, uint64(len(payload)))
	}
	mask := []byte{1, 2, 3, 4}
	header = append(header, mask...)
	for i, value := range payload {
		header = append(header, value^mask[i&3])
	}
	return header
}
func TestOpaqueBinaryAndFragmentation(t *testing.T) {
	for _, size := range []int{0, 1, 125, 126, 65535, 65536} {
		a, b := net.Pipe()
		payload := make([]byte, size)
		_, _ = rand.Read(payload)
		go func() { _ = writeAll(b, masked(2, payload, true)); _ = b.Close() }()
		stream := &wsStream{conn: a, reader: bufio.NewReader(a)}
		op, got, err := stream.readFrame()
		_ = a.Close()
		if err != nil || op != 2 || !bytes.Equal(got, payload) {
			t.Fatal("opaque frame changed", size, err)
		}
	}
	a, b := net.Pipe()
	defer a.Close()
	defer b.Close()
	go func() {
		_ = writeAll(b, append(append(masked(2, []byte{1, 2}, false), masked(9, []byte{7}, true)...), masked(0, []byte{3, 4}, true)...))
	}()
	stream := &wsStream{conn: a, reader: bufio.NewReader(a)}
	for _, want := range []byte{2, 9, 0} {
		op, _, err := stream.readFrame()
		if err != nil || op != want {
			t.Fatal("fragmented/control handling", err)
		}
	}
}
func TestMalformedFrames(t *testing.T) {
	for _, frame := range [][]byte{{0x82, 0}, {0xc2, 0x80}, {0x83, 0x80}, {0x81, 0x80}, {0x09, 0x80}, {0x89, 0xfe, 0, 126}, {0x82, 0xfe, 0, 1}, {0x80, 0x80}, masked(8, []byte{1}, true), {0x82, 0xff, 0x80, 0, 0, 0, 0, 0, 0, 0}} {
		a, b := net.Pipe()
		go func(data []byte) { _ = writeAll(b, data); _ = b.Close() }(frame)
		stream := &wsStream{conn: a, reader: bufio.NewReader(a)}
		_, _, err := stream.readFrame()
		_ = a.Close()
		if err == nil {
			t.Fatal("malformed frame accepted")
		}
	}
}
func TestMediaPolicyRejectsNewMediaBeforeDial(t *testing.T) {
    s := testServer(t)
    d := newDevice(t, s)
    _, response := access(t, s, d)
    token := signedResult(t, s, response)["token"].(string)
    enabled := true
    if err := s.setPolicy(nil, "", nil, &enabled); err != nil { t.Fatal(err) }
    s.dial = func(context.Context, string, string) (net.Conn, error) { return nil, io.EOF }
    for traffic, want := range map[string]int{"media":409, "":409, "voice":502, "control":502} {
        req := connectRequest(t, s, d, token, "149.154.167.50", 443)
        req.Header.Set("Connection", "Upgrade")
        req.Header.Set("Upgrade", "websocket")
        req.Header.Set("Sec-WebSocket-Version", "13")
        req.Header.Set("Sec-WebSocket-Protocol", protocol)
        req.Header.Set("Sec-WebSocket-Key", base64.StdEncoding.EncodeToString(make([]byte,16)))
        req.Header.Set("X-Astra-Traffic", traffic)
        result := httptest.NewRecorder()
        s.tunnelHandler(result, req)
        if result.Code != want { t.Fatalf("traffic %q: got %d want %d", traffic, result.Code, want) }
    }
}

func TestTLSTunnelAndImmediateAdministrativeClose(t *testing.T) {
	s := testServer(t)
	d := newDevice(t, s)
	_, response := access(t, s, d)
	token := signedResult(t, s, response)["token"].(string)
	s.dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		if address != "149.154.167.50:443" {
			t.Error("destination changed")
		}
		a, b := net.Pipe()
		go func() { _, _ = io.Copy(b, b); _ = b.Close() }()
		return a, nil
	}
	remote := httptest.NewTLSServer(s.handler())
	defer remote.Close()
	roots := x509.NewCertPool()
	roots.AddCert(remote.Certificate())
	conn, err := tls.Dial("tcp", remote.Listener.Addr().String(), &tls.Config{RootCAs: roots, ServerName: "127.0.0.1"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	request := connectRequest(t, s, d, token, "149.154.167.50", 443)
	key := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{91}, 16))
	_, err = fmt.Fprintf(conn, "GET /v1/tunnel?ip=149.154.167.50&port=443 HTTP/1.1\r\nHost: example.com\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: %s\r\nSec-WebSocket-Protocol: %s\r\nAuthorization: %s\r\nX-Astra-Nonce: %s\r\nX-Astra-Time: %s\r\nX-Astra-Proof: %s\r\n\r\n", key, protocol, request.Header.Get("Authorization"), request.Header.Get("X-Astra-Nonce"), request.Header.Get("X-Astra-Time"), request.Header.Get("X-Astra-Proof"))
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(conn)
	handshake, err := http.ReadResponse(reader, nil)
	if err != nil || handshake.StatusCode != 101 || handshake.Header.Get("Sec-WebSocket-Accept") != websocketAccept(key) {
		t.Fatal("TLS WebSocket handshake", err)
	}
	payload := make([]byte, 32768)
	_, _ = rand.Read(payload)
	if err = writeAll(conn, masked(2, payload, true)); err != nil {
		t.Fatal(err)
	}
	readServer := func() (byte, []byte) {
		t.Helper()
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		head := make([]byte, 2)
		if _, e := io.ReadFull(reader, head); e != nil {
			t.Fatal(e)
		}
		size := int(head[1] & 127)
		if size == 126 {
			ext := make([]byte, 2)
			_, _ = io.ReadFull(reader, ext)
			size = int(binary.BigEndian.Uint16(ext))
		}
		data := make([]byte, size)
		if _, e := io.ReadFull(reader, data); e != nil {
			t.Fatal(e)
		}
		return head[0] & 15, data
	}
	op, got := readServer()
	if op != 2 || !bytes.Equal(payload, got) {
		t.Fatal("tunnel altered bytes")
	}
	disabled := false
	if err = s.setPolicy(&disabled, "", nil); err != nil {
		t.Fatal(err)
	}
	op, got = readServer()
	if op != 8 || len(got) != 2 || binary.BigEndian.Uint16(got) != 4003 {
		t.Fatal("administrative close missing")
	}
}
