package main

import (
	"strings"
	"testing"
	"time"
)

func TestHTTPBackendHandshakeAndForward(t *testing.T) {
	srv := fakeMcpHTTP(t) // reused from httpmcp_test.go
	defer srv.Close()

	b, err := newHTTPBackend(srv.URL, "2024-11-05")
	if err != nil {
		t.Fatalf("newHTTPBackend: %v", err)
	}
	defer b.shutdown()

	// Proxy writes a client request into the backend; the pump POSTs it and feeds back the response.
	if err := b.writer.writeJSON(map[string]any{"jsonrpc": "2.0", "id": 7, "method": "tools/list"}); err != nil {
		t.Fatalf("write: %v", err)
	}
	done := make(chan rpcMessage, 1)
	go func() { m, _, _ := b.reader.read(); done <- m }()
	select {
	case m := <-done:
		if !strings.Contains(string(m.Result), `"ok":true`) {
			t.Fatalf("unexpected forwarded response: %s", m.Result)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for forwarded HTTP response")
	}
}

func TestHTTPBackendShutdownUnblocksReader(t *testing.T) {
	srv := fakeMcpHTTP(t)
	defer srv.Close()
	b, err := newHTTPBackend(srv.URL, "2024-11-05")
	if err != nil {
		t.Fatalf("newHTTPBackend: %v", err)
	}
	eof := make(chan struct{})
	go func() {
		for {
			if _, _, rerr := b.reader.read(); rerr != nil {
				close(eof)
				return
			}
		}
	}()
	b.shutdown()
	select {
	case <-eof:
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown did not unblock the backend reader")
	}
}
