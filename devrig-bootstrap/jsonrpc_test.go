package main

import (
	"bytes"
	"encoding/json"
	"io"
	"strings"
	"sync"
	"testing"
)

func TestClassifyMessages(t *testing.T) {
	req := rpcMessage{Method: "tools/list", ID: json.RawMessage(`1`)}
	if !req.isRequest() || req.isNotification() || req.isResponse() {
		t.Fatalf("request misclassified: %+v", req)
	}
	notf := rpcMessage{Method: "notifications/initialized"}
	if !notf.isNotification() || notf.isRequest() {
		t.Fatalf("notification misclassified: %+v", notf)
	}
	resp := rpcMessage{ID: json.RawMessage(`2`), Result: json.RawMessage(`{}`)}
	if !resp.isResponse() || resp.isRequest() {
		t.Fatalf("response misclassified: %+v", resp)
	}
}

func TestReaderSkipsBlankAndMalformed(t *testing.T) {
	in := strings.NewReader("\n" + `garbage` + "\n" + `{"jsonrpc":"2.0","id":1,"method":"ping"}` + "\n")
	r := newMsgReader(in)
	msg, raw, err := r.read()
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if msg.Method != "ping" {
		t.Fatalf("want ping, got %q (raw=%s)", msg.Method, raw)
	}
	if _, _, err := r.read(); err != io.EOF {
		t.Fatalf("want EOF, got %v", err)
	}
}

func TestWriterSerializesLines(t *testing.T) {
	var buf bytes.Buffer
	w := newMsgWriter(&buf)
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); _ = w.writeJSON(map[string]any{"jsonrpc": "2.0"}) }()
	}
	wg.Wait()
	// Every line must be independently valid JSON (no interleaving).
	for _, line := range strings.Split(strings.TrimRight(buf.String(), "\n"), "\n") {
		var v map[string]any
		if err := json.Unmarshal([]byte(line), &v); err != nil {
			t.Fatalf("interleaved/invalid line %q: %v", line, err)
		}
	}
}

func TestIDPrefixRoundTrips(t *testing.T) {
	for _, orig := range []string{`5`, `"abc"`, `"with\"quote"`} {
		pref := addIDPrefix(json.RawMessage(orig))
		back, ok := stripIDPrefix(pref)
		if !ok {
			t.Fatalf("stripIDPrefix(%s) reported no prefix", pref)
		}
		if string(back) != orig {
			t.Fatalf("round-trip: want %s, got %s (prefixed=%s)", orig, back, pref)
		}
	}
	// A non-prefixed id is reported as such and returned unchanged.
	if got, ok := stripIDPrefix(json.RawMessage(`7`)); ok || string(got) != `7` {
		t.Fatalf("plain id must not be reported as prefixed and must be unchanged: got=%s ok=%v", got, ok)
	}
}

func TestRewriteID(t *testing.T) {
	raw := []byte(`{"jsonrpc":"2.0","id":1,"method":"sampling/createMessage","params":{}}`)
	out := rewriteID(raw, json.RawMessage(`"x"`))
	var m map[string]json.RawMessage
	if err := json.Unmarshal(out, &m); err != nil {
		t.Fatalf("rewritten not valid JSON: %v", err)
	}
	if string(m["id"]) != `"x"` {
		t.Fatalf("id not rewritten: %s", m["id"])
	}
	if string(m["method"]) != `"sampling/createMessage"` {
		t.Fatalf("method clobbered: %s", m["method"])
	}
}
