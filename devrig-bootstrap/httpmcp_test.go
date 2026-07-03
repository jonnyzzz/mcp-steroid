package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeMcpHTTP emulates the plugin's McpHttpTransport: issues a session id on the first POST,
// echoes it back on later POSTs, returns 202 for notifications (no id), JSON otherwise.
func fakeMcpHTTP(t *testing.T) *httptest.Server {
	t.Helper()
	const sess = "sess-123"
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Mcp-Session-Id") == "" {
			w.Header().Set("Mcp-Session-Id", sess)
		}
		body, _ := io.ReadAll(r.Body)
		if strings.Contains(string(body), `"method":"notifications/`) {
			w.WriteHeader(http.StatusAccepted)
			return
		}
		// Echo the request's id back, as a real MCP server does, so the proxy's client can match it.
		var req struct {
			ID json.RawMessage `json:"id"`
		}
		_ = json.Unmarshal(body, &req)
		id := req.ID
		if len(id) == 0 {
			id = json.RawMessage(`1`)
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"jsonrpc":"2.0","id":` + string(id) + `,"result":{"ok":true}}`))
	}))
}

func TestHttpMcpClientSessionAndPost(t *testing.T) {
	srv := fakeMcpHTTP(t)
	defer srv.Close()
	c := newHttpMcpClient(srv.URL)

	body, err := c.post([]byte(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`))
	if err != nil {
		t.Fatalf("initialize post: %v", err)
	}
	if !strings.Contains(string(body), `"ok":true`) {
		t.Fatalf("unexpected body: %s", body)
	}
	if c.sessionID != "sess-123" {
		t.Fatalf("session not captured: %q", c.sessionID)
	}

	// A notification returns 202 with no body.
	nb, err := c.post([]byte(`{"jsonrpc":"2.0","method":"notifications/initialized"}`))
	if err != nil {
		t.Fatalf("notif post: %v", err)
	}
	if nb != nil {
		t.Fatalf("notification must yield nil body, got %s", nb)
	}
}
