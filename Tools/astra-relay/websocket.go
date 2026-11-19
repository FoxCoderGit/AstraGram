package main

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"sync"
	"time"
)

const maxFrame = 65536
const maxMessage = 1048576

// WebSocket framing only: the payload is an opaque tgnet byte stream.
type wsStream struct {
	conn            net.Conn
	reader          *bufio.Reader
	upstream        net.Conn
	device, ip      string
	authorizedUntil int64
	traffic         func(bool, int)
	writer          sync.Mutex
	once            sync.Once
	done            chan struct{}
	fragmented      bool
	messageSize     uint64
}

func websocketAccept(key string) string {
	hash := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	return base64.StdEncoding.EncodeToString(hash[:])
}
func writeAll(writer io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := writer.Write(data)
		if err != nil {
			return err
		}
		if n <= 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}
func (w *wsStream) writeFrame(opcode byte, data []byte) error {
	if len(data) > maxFrame || opcode >= 8 && len(data) > 125 {
		return errors.New("oversized output frame")
	}
	w.writer.Lock()
	defer w.writer.Unlock()
	_ = w.conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
	header := []byte{0x80 | opcode}
	switch {
	case len(data) < 126:
		header = append(header, byte(len(data)))
	case len(data) <= 65535:
		header = append(header, 126, byte(len(data)>>8), byte(len(data)))
	default:
		header = append(header, 127)
		header = binary.BigEndian.AppendUint64(header, uint64(len(data)))
	}
	if err := writeAll(w.conn, header); err != nil {
		return err
	}
	return writeAll(w.conn, data)
}
func (w *wsStream) readFrame() (byte, []byte, error) {
	_ = w.conn.SetReadDeadline(time.Now().Add(90 * time.Second))
	var header [2]byte
	if _, err := io.ReadFull(w.reader, header[:]); err != nil {
		return 0, nil, err
	}
	fin := header[0]&0x80 != 0
	opcode := header[0] & 15
	if header[0]&0x70 != 0 || header[1]&0x80 == 0 {
		return 0, nil, errors.New("invalid WebSocket flags")
	}
	length := uint64(header[1] & 127)
	if length == 126 {
		var ext [2]byte
		if _, err := io.ReadFull(w.reader, ext[:]); err != nil {
			return 0, nil, err
		}
		length = uint64(binary.BigEndian.Uint16(ext[:]))
		if length < 126 {
			return 0, nil, errors.New("noncanonical frame length")
		}
	}
	if length == 127 {
		var ext [8]byte
		if _, err := io.ReadFull(w.reader, ext[:]); err != nil {
			return 0, nil, err
		}
		length = binary.BigEndian.Uint64(ext[:])
		if length < 65536 || length>>63 != 0 {
			return 0, nil, errors.New("noncanonical frame length")
		}
	}
	if length > maxFrame {
		return 0, nil, errors.New("frame limit")
	}
	if opcode >= 8 {
		if !fin || length > 125 || opcode != 8 && opcode != 9 && opcode != 10 {
			return 0, nil, errors.New("invalid control frame")
		}
	} else {
		if opcode == 2 {
			if w.fragmented {
				return 0, nil, errors.New("interleaved binary message")
			}
			w.messageSize = 0
		} else if opcode != 0 || !w.fragmented {
			return 0, nil, errors.New("binary frames required")
		}
		w.messageSize += length
		if w.messageSize > maxMessage {
			return 0, nil, errors.New("message limit")
		}
		w.fragmented = !fin
	}
	var mask [4]byte
	if _, err := io.ReadFull(w.reader, mask[:]); err != nil {
		return 0, nil, err
	}
	payload := make([]byte, int(length))
	if _, err := io.ReadFull(w.reader, payload); err != nil {
		return 0, nil, err
	}
	for i := range payload {
		payload[i] ^= mask[i&3]
	}
	if opcode == 8 && len(payload) == 1 {
		return 0, nil, errors.New("invalid close frame")
	}
	return opcode, payload, nil
}
func (w *wsStream) close(code uint16) {
	w.once.Do(func() {
		payload := []byte{byte(code >> 8), byte(code)}
		// Bound an administrative close even when the peer stops reading.
		go func() {
			select {
			case <-time.After(time.Second):
				_ = w.conn.Close()
			case <-w.done:
			}
		}()
		_ = w.writeFrame(8, payload)
		_ = w.conn.Close()
		_ = w.upstream.Close()
		close(w.done)
	})
}
func (w *wsStream) pump() {
	go func() {
		buffer := make([]byte, 32768)
		for {
			n, err := w.upstream.Read(buffer)
			if n > 0 {
				if w.traffic != nil {
					w.traffic(false, n)
				}
				if e := w.writeFrame(2, buffer[:n]); e != nil {
					break
				}
			}
			if err != nil {
				break
			}
		}
		w.close(1000)
	}()
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if time.Now().Unix() >= w.authorizedUntil {
					w.close(4001)
					return
				}
				if w.writeFrame(9, nil) != nil {
					w.close(1001)
					return
				}
			case <-w.done:
				return
			}
		}
	}()
	defer w.close(1000)
	for {
		opcode, payload, err := w.readFrame()
		if err != nil {
			return
		}
		switch opcode {
		case 0, 2:
			if w.traffic != nil {
				w.traffic(true, len(payload))
			}
			_ = w.upstream.SetWriteDeadline(time.Now().Add(30 * time.Second))
			if writeAll(w.upstream, payload) != nil {
				return
			}
		case 8:
			return
		case 9:
			if w.writeFrame(10, payload) != nil {
				return
			}
		case 10:
		}
	}
}
