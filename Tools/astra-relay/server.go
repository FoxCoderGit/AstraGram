package main

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

func headerToken(value, want string) bool {
	for _, part := range strings.Split(value, ",") {
		if strings.EqualFold(strings.TrimSpace(part), want) {
			return true
		}
	}
	return false
}
func (s *relayServer) reserve(device, ip string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !s.policy.Enabled || s.policy.Denied[device] || s.devices[device] >= s.config.MaxDeviceConnections || s.ips[ip] >= s.config.MaxIPConnections || s.reserved >= 512 {
		return false
	}
	s.reserved++
	s.devices[device]++
	s.ips[ip]++
	return true
}
func (s *relayServer) release(device, ip string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.reserved--
	s.devices[device]--
	if s.devices[device] <= 0 {
		delete(s.devices, device)
	}
	s.ips[ip]--
	if s.ips[ip] <= 0 {
		delete(s.ips, ip)
	}
}
func (s *relayServer) tunnelHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" || !headerToken(r.Header.Get("Connection"), "upgrade") || !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") || r.Header.Get("Sec-WebSocket-Version") != "13" || r.Header.Get("Sec-WebSocket-Protocol") != protocol {
		w.WriteHeader(400)
		return
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	decoded, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(decoded) != 16 {
		w.WriteHeader(400)
		return
	}
	if len(r.URL.RawQuery) > 256 {
		w.WriteHeader(400)
		return
	}
	address := r.URL.Query().Get("ip")
	port, err := strconv.Atoi(r.URL.Query().Get("port"))
	if err != nil || !s.filter.allows(address, port) {
		w.WriteHeader(400)
		return
	}
	claims, status := s.verifyConnection(r, address, port)
	if status != 0 {
		w.WriteHeader(status)
		return
	}
	traffic := r.Header.Get("X-Astra-Traffic")
	if traffic != "" && traffic != "media" && traffic != "voice" && traffic != "control" {
		w.WriteHeader(400)
		return
	}
	s.mu.Lock()
	blocked := s.policy.blocksTraffic(traffic)
	s.mu.Unlock()
	if blocked {
		w.WriteHeader(409)
		return
	}
	ip := peerIP(r)
	if !s.reserve(claims.Device, ip) {
		s.mu.Lock()
		denied := !s.policy.Enabled || s.policy.Denied[claims.Device]
		s.mu.Unlock()
		if denied {
			w.WriteHeader(403)
		} else {
			w.WriteHeader(429)
		}
		return
	}
	defer s.release(claims.Device, ip)
	ctx, cancel := context.WithTimeout(r.Context(), 8*time.Second)
	defer cancel()
	dial := s.dial
	if dial == nil {
		dial = (&net.Dialer{KeepAlive: 30 * time.Second}).DialContext
	}
	upstream, err := dial(ctx, "tcp", net.JoinHostPort(address, strconv.Itoa(port)))
	if err != nil {
		w.WriteHeader(502)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		_ = upstream.Close()
		w.WriteHeader(500)
		return
	}
	conn, buffer, err := hijacker.Hijack()
	if err != nil {
		_ = upstream.Close()
		return
	}
	_, err = fmt.Fprintf(buffer, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: %s\r\nSec-WebSocket-Protocol: %s\r\n\r\n", websocketAccept(key), protocol)
	if err == nil {
		err = buffer.Flush()
	}
	if err != nil {
		_ = conn.Close()
		_ = upstream.Close()
		return
	}
	stream := &wsStream{conn: conn, reader: buffer.Reader, upstream: upstream, device: claims.Device, ip: ip, trafficClass: traffic, authorizedUntil: claims.Until, done: make(chan struct{})}
	stream.traffic = func(up bool, count int) {
		if up {
			s.bytesUp.Add(uint64(count))
		} else {
			s.bytesDown.Add(uint64(count))
		}
	}
	s.mu.Lock()
	allowed := s.policy.Enabled && !s.policy.Denied[claims.Device]
	blocked = s.policy.blocksTraffic(traffic)
	if allowed && !blocked {
		s.active[stream] = true
	}
	s.mu.Unlock()
	if !allowed {
		stream.close(4003)
		return
	}
	if blocked {
		stream.close(1012)
		return
	}
	defer func() { s.mu.Lock(); delete(s.active, stream); s.mu.Unlock() }()
	stream.pump()
}
func (s *relayServer) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/challenge", s.challengeHandler)
	mux.HandleFunc("/v1/access", s.accessHandler)
	mux.HandleFunc("/v1/tunnel", s.tunnelHandler)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(200)
		_, _ = w.Write([]byte("AstraRelay\n"))
	})
	return mux
}
func (s *relayServer) httpServer() *http.Server {
	var lock sync.Mutex
	pending := map[net.Conn]bool{}
	return &http.Server{Addr: s.config.Listen, Handler: s.handler(), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 15 * time.Second, WriteTimeout: 15 * time.Second, IdleTimeout: 45 * time.Second, MaxHeaderBytes: 16384, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12, NextProtos: []string{"http/1.1"}},
		ConnState: func(conn net.Conn, state http.ConnState) {
			lock.Lock()
			refuse := false
			if state == http.StateNew {
				if len(pending) >= 1024 {
					refuse = true
				} else {
					pending[conn] = true
				}
			}
			if state == http.StateClosed || state == http.StateHijacked {
				delete(pending, conn)
			}
			lock.Unlock()
			if refuse {
				_ = conn.Close()
			}
		},
	}
}
