package main

import (
	"bufio"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"
)

// fakeDevrig simulates `devrig mcp`: it answers the proxy's initialize handshake,
// then answers tools/list with a single real-looking tool.
func fakeDevrig(in io.Reader, out io.WriteCloser) {
	defer out.Close()
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	enc := json.NewEncoder(out)
	for sc.Scan() {
		line := sc.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		var m rpcMessage
		if err := json.Unmarshal(line, &m); err != nil {
			continue
		}
		switch {
		case m.Method == "initialize":
			enc.Encode(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(m.ID),
				"result": map[string]any{"protocolVersion": "2024-11-05",
					"capabilities": map[string]any{"tools": map[string]any{}},
					"serverInfo":   map[string]any{"name": "devrig", "version": "9.9"}}})
		case m.Method == "tools/list":
			enc.Encode(map[string]any{"jsonrpc": "2.0", "id": json.RawMessage(m.ID),
				"result": map[string]any{"tools": []any{map[string]any{"name": "steroid_execute_code"}}}})
		case m.Method == "notifications/initialized":
			// no reply
		}
	}
}

func TestBackendHandshake(t *testing.T) {
	toDevR, toDevW := io.Pipe()   // proxy -> devrig
	fromDevR, fromDevW := io.Pipe() // devrig -> proxy
	go fakeDevrig(toDevR, fromDevW)

	b := newBackend(toDevW, fromDevR)
	done := make(chan error, 1)
	go func() { done <- b.handshake("2024-11-05") }()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("handshake: %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("handshake timed out")
	}

	// After handshake, a tools/list request must return the fake devrig tool.
	b.writer.writeJSON(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
	msg, _, err := b.reader.read()
	if err != nil {
		t.Fatalf("read tools/list: %v", err)
	}
	if !strings.Contains(string(msg.Result), "steroid_execute_code") {
		t.Fatalf("expected devrig tool, got %s", msg.Result)
	}

	b.stdin.Close() // let fakeDevrig see EOF and exit
}
