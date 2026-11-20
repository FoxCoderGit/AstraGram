package main

import (
	"context"
	"encoding/hex"
	"net"
	"net/http"
	"os"
	"time"
)

func (s *relayServer) setPolicy(enabled *bool, device string, denied *bool, blockMedia ...*bool) error {
	s.mu.Lock()
	previous := s.policy
	next := Policy{BlockMedia: previous.BlockMedia, Revision: s.policy.Revision + 1, Enabled: s.policy.Enabled, Denied: map[string]bool{}}
	for k, v := range s.policy.Denied {
		next.Denied[k] = v
	}
	if enabled != nil {
		next.Enabled = *enabled
	}
	if len(blockMedia) > 0 && blockMedia[0] != nil {
		next.BlockMedia = *blockMedia[0]
	}
	if device != "" && denied != nil {
		if *denied {
			next.Denied[device] = true
		} else {
			delete(next.Denied, device)
		}
	}
	if err := atomicJSON(s.config.PolicyFile, next); err != nil {
		s.mu.Unlock()
		return err
	}
	s.policy = next
	var closeStreams, restrictedStreams []*wsStream
	for stream := range s.active {
		if !next.Enabled || next.Denied[stream.device] {
			closeStreams = append(closeStreams, stream)
		} else if next.blocksTraffic(stream.trafficClass) && !previous.blocksTraffic(stream.trafficClass) {
			restrictedStreams = append(restrictedStreams, stream)
		}
	}
	s.mu.Unlock()
	for _, stream := range closeStreams {
		go stream.close(4003)
	}
	for _, stream := range restrictedStreams {
		go stream.close(1012)
	}
	return nil
}
func (s *relayServer) adminHandler(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/reconnect" && r.Method == "POST" {
		s.mu.Lock()
		streams := make([]*wsStream, 0, len(s.active))
		for stream := range s.active {
			streams = append(streams, stream)
		}
		s.mu.Unlock()
		for _, stream := range streams {
			go stream.close(1012)
		}
		writeJSON(w, 200, map[string]any{"status": "ok", "connections": len(streams)})
		return
	}
	if r.URL.Path != "/policy" {
		w.WriteHeader(404)
		return
	}
	if r.Method == "GET" {
		s.mu.Lock()
		defer s.mu.Unlock()
		devices := map[string]int{}
		for device, count := range s.devices {
			devices[device] = count
		}
		writeJSON(w, 200, map[string]any{"policy": s.policy, "active_connections": len(s.active), "active_devices": devices, "started_at": s.started,
			"bytes_up": s.bytesUp.Load(), "bytes_down": s.bytesDown.Load(), "device_limit": s.config.MaxDeviceConnections, "connection_limit": 512})
		return
	}
	if r.Method != "POST" {
		w.WriteHeader(405)
		return
	}
	var request struct {
		Enabled    *bool  `json:"enabled"`
		BlockMedia *bool  `json:"block_media"`
		Device     string `json:"device"`
		Denied     *bool  `json:"denied"`
	}
	if !readJSON(w, r, &request) {
		return
	}
	if request.Enabled == nil && request.BlockMedia == nil && request.Device == "" {
		w.WriteHeader(400)
		return
	}
	if request.Device != "" {
		if len(request.Device) != 64 || request.Denied == nil {
			w.WriteHeader(400)
			return
		}
		if _, err := hex.DecodeString(request.Device); err != nil {
			w.WriteHeader(400)
			return
		}
	}
	if err := s.setPolicy(request.Enabled, request.Device, request.Denied, request.BlockMedia); err != nil {
		w.WriteHeader(503)
		return
	}
	writeJSON(w, 200, map[string]string{"status": "ok"})
}
func (s *relayServer) serveAdmin(ctx context.Context) error {
	if info, err := os.Lstat(s.config.AdminSocket); err == nil {
		if info.Mode()&os.ModeSocket == 0 {
			return os.ErrExist
		}
		if err = os.Remove(s.config.AdminSocket); err != nil {
			return err
		}
	}
	listener, err := net.Listen("unix", s.config.AdminSocket)
	if err != nil {
		return err
	}
	if err = os.Chmod(s.config.AdminSocket, 0660); err != nil {
		_ = listener.Close()
		return err
	}
	server := &http.Server{Handler: http.HandlerFunc(s.adminHandler), ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, MaxHeaderBytes: 4096}
	go func() { <-ctx.Done(); _ = server.Close() }()
	return server.Serve(listener)
}
