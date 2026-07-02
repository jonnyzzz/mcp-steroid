package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"strings"
	"sync"
)

// rpcMessage is any JSON-RPC message (request, response, or notification).
// Kept generic so the proxy can classify and forward messages it doesn't interpret.
type rpcMessage struct {
	Jsonrpc string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method,omitempty"`
	Params  json.RawMessage `json:"params,omitempty"`
	Result  json.RawMessage `json:"result,omitempty"`
	Error   json.RawMessage `json:"error,omitempty"`
}

func (m rpcMessage) isRequest() bool      { return m.Method != "" && len(m.ID) > 0 }
func (m rpcMessage) isNotification() bool { return m.Method != "" && len(m.ID) == 0 }
func (m rpcMessage) isResponse() bool     { return m.Method == "" && len(m.ID) > 0 }

// msgReader reads newline-delimited JSON messages, skipping blank/malformed lines.
type msgReader struct{ sc *bufio.Scanner }

func newMsgReader(r io.Reader) *msgReader {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	return &msgReader{sc: sc}
}

// read returns the next message and its verbatim bytes (for lossless forwarding).
// Returns io.EOF when the stream ends.
func (m *msgReader) read() (rpcMessage, []byte, error) {
	for m.sc.Scan() {
		line := m.sc.Bytes()
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		raw := make([]byte, len(line))
		copy(raw, line)
		var msg rpcMessage
		if err := json.Unmarshal(raw, &msg); err != nil {
			continue // ignore malformed; never crash the loop
		}
		return msg, raw, nil
	}
	if err := m.sc.Err(); err != nil {
		return rpcMessage{}, nil, err
	}
	return rpcMessage{}, nil, io.EOF
}

// msgWriter writes newline-delimited JSON, serialized so concurrent writers never interleave.
type msgWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func newMsgWriter(w io.Writer) *msgWriter { return &msgWriter{w: w} }

func (m *msgWriter) writeRaw(raw []byte) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, err := m.w.Write(raw); err != nil {
		return err
	}
	_, err := m.w.Write([]byte("\n"))
	return err
}

func (m *msgWriter) writeJSON(v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return m.writeRaw(b)
}

func newResult(id json.RawMessage, result any) rpcResponse {
	return rpcResponse{Jsonrpc: "2.0", ID: id, Result: result}
}

func notif(method string) map[string]any {
	return map[string]any{"jsonrpc": "2.0", "method": method}
}

// idPrefix namespaces backend-originated (server->client) request ids so they can't
// collide with client-originated ids when both traverse the proxy.
const idPrefix = "devrigproxy~"

func addIDPrefix(id json.RawMessage) json.RawMessage {
	// json.Marshal of a Go string never errors; result is always a valid, escaped JSON string.
	b, _ := json.Marshal(idPrefix + string(id))
	return json.RawMessage(b)
}

// stripIDPrefix returns the original id text and true if id was a prefixed string.
func stripIDPrefix(id json.RawMessage) (json.RawMessage, bool) {
	var s string
	if err := json.Unmarshal(id, &s); err != nil {
		return id, false
	}
	if !strings.HasPrefix(s, idPrefix) {
		return id, false
	}
	return json.RawMessage(s[len(idPrefix):]), true
}

// rewriteID returns raw with its "id" field replaced by newID (other fields untouched).
func rewriteID(raw []byte, newID json.RawMessage) []byte {
	var m map[string]json.RawMessage
	if err := json.Unmarshal(raw, &m); err != nil {
		return raw
	}
	m["id"] = newID
	b, err := json.Marshal(m)
	if err != nil {
		return raw
	}
	return b
}
