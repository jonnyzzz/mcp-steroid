package main

import (
	"bufio"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// collectLines reads NDJSON messages from r until closed, sending each parsed message on ch.
func collectLines(r io.Reader, ch chan<- rpcMessage) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
	for sc.Scan() {
		line := sc.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		var m rpcMessage
		if json.Unmarshal(line, &m) == nil {
			ch <- m
		}
	}
	close(ch)
}

// waitFor drains ch until pred matches or timeout.
func waitFor(t *testing.T, ch <-chan rpcMessage, timeout time.Duration, pred func(rpcMessage) bool) rpcMessage {
	t.Helper()
	deadline := time.After(timeout)
	for {
		select {
		case m, ok := <-ch:
			if !ok {
				t.Fatal("channel closed before match")
			}
			if pred(m) {
				return m
			}
		case <-deadline:
			t.Fatal("timed out waiting for expected message")
		}
	}
}

func TestProxyHotSwapEmitsListChangedAndForwards(t *testing.T) {
	home := t.TempDir()
	old := swapPollInterval
	swapPollInterval = 10 * time.Millisecond
	defer func() { swapPollInterval = old }()

	clientInR, clientInW := io.Pipe()   // test -> proxy (client stdin)
	clientOutR, clientOutW := io.Pipe() // proxy -> test (client stdout)

	// Injected fake backend factory: connect the proxy to an in-memory fakeDevrig.
	p := newProxy(clientInR, clientOutW, home)
	p.startBackend = func(h, ver string) (*backend, error) {
		toDevR, toDevW := io.Pipe()
		fromDevR, fromDevW := io.Pipe()
		go fakeDevrig(toDevR, fromDevW)
		b := newBackend(toDevW, fromDevR)
		if err := b.handshake(ver); err != nil {
			return nil, err
		}
		return b, nil
	}
	go func() { _ = p.run() }()

	msgs := make(chan rpcMessage, 64)
	go collectLines(clientOutR, msgs)

	enc := json.NewEncoder(clientInW)
	// initialize + initialized
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize",
		"params": map[string]any{"protocolVersion": "2024-11-05"}})
	init := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "1" })
	if !strings.Contains(string(init.Result), "listChanged") {
		t.Fatalf("initialize must advertise listChanged: %s", init.Result)
	}
	enc.Encode(map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"})

	// pre-swap tools/list -> only devrig_status
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
	pre := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "2" })
	if !strings.Contains(string(pre.Result), "devrig_status") || strings.Contains(string(pre.Result), "steroid_execute_code") {
		t.Fatalf("pre-swap tools/list must be devrig_status only: %s", pre.Result)
	}

	// Simulate download completion: create the launcher so installState -> "installed".
	binDir := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(binDir, 0o755)
	os.WriteFile(filepath.Join(binDir, "devrig"), []byte("#!/bin/sh\n"), 0o755)

	// The proxy must emit tools/list_changed.
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool {
		return m.isNotification() && m.Method == "notifications/tools/list_changed"
	})

	// post-swap tools/list -> forwarded to devrig -> real tool
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 3, "method": "tools/list"})
	post := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "3" })
	if !strings.Contains(string(post.Result), "steroid_execute_code") {
		t.Fatalf("post-swap tools/list must be forwarded to devrig: %s", post.Result)
	}

	// devrig_status is still intercepted locally post-swap.
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 4, "method": "tools/call",
		"params": map[string]any{"name": "devrig_status"}})
	st := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "4" })
	if !strings.Contains(string(st.Result), "content") {
		t.Fatalf("devrig_status must be answered locally: %s", st.Result)
	}

	clientInW.Close()
}

func TestBackendExitClearsProxy(t *testing.T) {
	home := t.TempDir()
	old := swapPollInterval
	swapPollInterval = 10 * time.Millisecond
	defer func() { swapPollInterval = old }()

	clientInR, clientInW := io.Pipe()
	clientOutR, clientOutW := io.Pipe()

	// channel to receive the fake backend's write end so the test can close it to simulate a crash.
	backendWriteEnd := make(chan *io.PipeWriter, 1)

	p := newProxy(clientInR, clientOutW, home)
	p.startBackend = func(h, ver string) (*backend, error) {
		toDevR, toDevW := io.Pipe()
		fromDevR, fromDevW := io.Pipe()
		backendWriteEnd <- fromDevW // give test control of the write end
		go func() {
			// Mini-fake: answer initialize handshake, then block until fromDevW is closed.
			sc := bufio.NewScanner(toDevR)
			sc.Buffer(make([]byte, 0, 64*1024), 1<<20)
			enc2 := json.NewEncoder(fromDevW)
			for sc.Scan() {
				line := sc.Bytes()
				if len(strings.TrimSpace(string(line))) == 0 {
					continue
				}
				var m rpcMessage
				if json.Unmarshal(line, &m) != nil {
					continue
				}
				if m.Method == "initialize" {
					enc2.Encode(map[string]any{
						"jsonrpc": "2.0", "id": json.RawMessage(m.ID),
						"result": map[string]any{
							"protocolVersion": "2024-11-05",
							"capabilities":    map[string]any{"tools": map[string]any{}},
							"serverInfo":      map[string]any{"name": "devrig", "version": "9.9"},
						},
					})
					break
				}
			}
			// Drain stdin so writes don't block; exit when fromDevW is closed (toDevR will EOF).
			io.Copy(io.Discard, toDevR)
		}()
		b := newBackend(toDevW, fromDevR)
		if err := b.handshake(ver); err != nil {
			return nil, err
		}
		return b, nil
	}
	go func() { _ = p.run() }()

	msgs := make(chan rpcMessage, 64)
	go collectLines(clientOutR, msgs)

	enc := json.NewEncoder(clientInW)

	// initialize + initialized
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize",
		"params": map[string]any{"protocolVersion": "2024-11-05"}})
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "1" })
	enc.Encode(map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"})

	// Trigger swap by simulating devrig binary becoming available.
	binDir := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(binDir, 0o755)
	os.WriteFile(filepath.Join(binDir, "devrig"), []byte("#!/bin/sh\n"), 0o755)

	// Wait for the tools/list_changed notification that signals swap.
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool {
		return m.isNotification() && m.Method == "notifications/tools/list_changed"
	})

	// Get the fake backend's write end (sent during startBackend call).
	var fakeOut *io.PipeWriter
	select {
	case fakeOut = <-backendWriteEnd:
	case <-time.After(3 * time.Second):
		t.Fatal("did not receive backend write end in time")
	}

	// Verify p.backend is non-nil after swap.
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		b := p.backend
		p.mu.Unlock()
		if b != nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	p.mu.Lock()
	if p.backend == nil {
		p.mu.Unlock()
		t.Fatal("expected p.backend to be non-nil after swap")
	}
	p.mu.Unlock()

	// Simulate backend crash by closing its output pipe (EOF to pumpBackend).
	fakeOut.Close()

	// p.backend must be cleared within a short time (pumpBackend detects EOF and nils it).
	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		p.mu.Lock()
		b := p.backend
		p.mu.Unlock()
		if b == nil {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	p.mu.Lock()
	cleared := p.backend == nil
	p.mu.Unlock()
	if !cleared {
		t.Fatal("expected p.backend to be cleared after backend crash, but it is still non-nil")
	}

	// devrig_status must still be answered locally after the backend exits.
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 5, "method": "tools/call",
		"params": map[string]any{"name": "devrig_status"}})
	st := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "5" })
	if !strings.Contains(string(st.Result), "content") {
		t.Fatalf("devrig_status must be answered locally after crash: %s", st.Result)
	}

	clientInW.Close()
}

func TestProxyTierOneThenTierTwo(t *testing.T) {
	home := t.TempDir()
	old := swapPollInterval
	swapPollInterval = 10 * time.Millisecond
	defer func() { swapPollInterval = old }()

	// A live IDE endpoint is present from the start.
	ide := fakeMcpHTTP(t)
	defer ide.Close()
	writeMarker(t, home, 4242, "2026-07-03T12:00:00Z", ide.URL)

	clientInR, clientInW := io.Pipe()
	clientOutR, clientOutW := io.Pipe()
	p := newProxy(clientInR, clientOutW, home)
	// Inject a fake devrig stdio backend factory for the Tier-2 swap.
	p.startBackend = func(h, ver string) (*backend, error) {
		toDevR, toDevW := io.Pipe()
		fromDevR, fromDevW := io.Pipe()
		go fakeDevrig(toDevR, fromDevW)
		b := newBackend(toDevW, fromDevR)
		if err := b.handshake(ver); err != nil {
			return nil, err
		}
		b.shutdown = func() { toDevW.Close() }
		return b, nil
	}
	go func() { _ = p.run() }()

	msgs := make(chan rpcMessage, 64)
	go collectLines(clientOutR, msgs)

	enc := json.NewEncoder(clientInW)
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": map[string]any{"protocolVersion": "2024-11-05"}})
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "1" })
	enc.Encode(map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"})

	// Tier 1: list_changed fires, and tools/list now forwards to the IDE HTTP endpoint.
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool {
		return m.isNotification() && m.Method == "notifications/tools/list_changed"
	})
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
	t1 := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "2" })
	if !strings.Contains(string(t1.Result), `"ok":true`) {
		t.Fatalf("Tier-1 tools/list must be served by the IDE HTTP endpoint: %s", t1.Result)
	}

	// Tier 2: devrig finishes installing -> a second list_changed -> tools/list forwards to devrig.
	binDir := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(binDir, 0o755)
	os.WriteFile(filepath.Join(binDir, "devrig"), []byte("#!/bin/sh\n"), 0o755)
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool {
		return m.isNotification() && m.Method == "notifications/tools/list_changed"
	})
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 3, "method": "tools/list"})
	t2 := waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "3" })
	if !strings.Contains(string(t2.Result), "steroid_execute_code") {
		t.Fatalf("Tier-2 tools/list must be forwarded to devrig: %s", t2.Result)
	}
	clientInW.Close()
}

func TestProxyInvokesOnSwapToDevrig(t *testing.T) {
	home := t.TempDir()
	old := swapPollInterval
	swapPollInterval = 10 * time.Millisecond
	defer func() { swapPollInterval = old }()

	clientInR, clientInW := io.Pipe()
	clientOutR, clientOutW := io.Pipe()
	p := newProxy(clientInR, clientOutW, home)
	p.startBackend = func(h, ver string) (*backend, error) {
		toDevR, toDevW := io.Pipe()
		fromDevR, fromDevW := io.Pipe()
		go fakeDevrig(toDevR, fromDevW)
		b := newBackend(toDevW, fromDevR)
		if err := b.handshake(ver); err != nil {
			return nil, err
		}
		b.shutdown = func() { toDevW.Close() }
		return b, nil
	}
	fired := make(chan struct{}, 1)
	p.onSwapToDevrig = func() { fired <- struct{}{} }
	go func() { _ = p.run() }()

	msgs := make(chan rpcMessage, 64)
	go collectLines(clientOutR, msgs)
	enc := json.NewEncoder(clientInW)
	enc.Encode(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": map[string]any{"protocolVersion": "2024-11-05"}})
	waitFor(t, msgs, 3*time.Second, func(m rpcMessage) bool { return m.isResponse() && string(m.ID) == "1" })
	enc.Encode(map[string]any{"jsonrpc": "2.0", "method": "notifications/initialized"})

	binDir := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(binDir, 0o755)
	os.WriteFile(filepath.Join(binDir, "devrig"), []byte("#!/bin/sh\n"), 0o755)

	select {
	case <-fired:
	case <-time.After(3 * time.Second):
		t.Fatal("onSwapToDevrig was not invoked after Tier-2 swap")
	}
	clientInW.Close()
}
