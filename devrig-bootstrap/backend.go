package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

// startBackendTimeout is the maximum time to wait for the devrig mcp handshake to complete.
// A package var (not const) so tests can lower it.
var startBackendTimeout = 120 * time.Second

const backendInitID = "devrig-proxy-init"

var backendInitIDRaw = json.RawMessage(`"` + backendInitID + `"`)

// backend is a live `devrig mcp` child the proxy forwards traffic to.
type backend struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	reader *msgReader
	writer *msgWriter
}

// newBackend wires a backend over arbitrary streams (tests use in-memory pipes).
func newBackend(stdin io.WriteCloser, stdout io.Reader) *backend {
	return &backend{stdin: stdin, reader: newMsgReader(stdout), writer: newMsgWriter(stdin)}
}

// handshake performs the MCP client side: initialize -> await response -> initialized.
func (b *backend) handshake(protocolVersion string) error {
	initReq := map[string]any{
		"jsonrpc": "2.0", "id": backendInitID, "method": "initialize",
		"params": map[string]any{
			"protocolVersion": protocolVersion,
			"capabilities":    map[string]any{},
			"clientInfo":      map[string]any{"name": "devrig-bootstrap-proxy", "version": "0.0.0"},
		},
	}
	if err := b.writer.writeJSON(initReq); err != nil {
		return err
	}
	for {
		msg, _, err := b.reader.read()
		if err != nil {
			return err
		}
		if msg.isResponse() && bytes.Equal(msg.ID, backendInitIDRaw) {
			break
		}
		// Ignore anything the backend emits before its initialize response.
	}
	return b.writer.writeJSON(notif("notifications/initialized"))
}

// runHandshakeWithTimeout runs b.handshake within startBackendTimeout.
// On timeout it calls kill() then returns an error.
// On handshake error it also calls kill() then returns the error.
func runHandshakeWithTimeout(b *backend, protocolVersion string, kill func()) error {
	hsDone := make(chan error, 1)
	go func() { hsDone <- b.handshake(protocolVersion) }()
	select {
	case err := <-hsDone:
		if err != nil {
			kill()
		}
		return err
	case <-time.After(startBackendTimeout):
		kill()
		os.Stderr.WriteString("devrig-bootstrap: devrig mcp handshake timed out after " + startBackendTimeout.String() + ", killed child\n")
		return fmt.Errorf("devrig mcp handshake timed out after %v, killed child", startBackendTimeout)
	}
}

// startBackend spawns `devrig mcp`, wires its stdio, and completes the handshake.
func startBackend(home, protocolVersion string) (*backend, error) {
	name := "devrig"
	if runtime.GOOS == "windows" {
		name = "devrig.cmd"
	}
	devrig := filepath.Join(home, ".mcp-steroid", "bin", name)

	cmd := exec.Command(devrig, "mcp")
	cmd.Stderr = os.Stderr // child diagnostics -> our stderr (Claude's MCP log), never stdout
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}

	b := newBackend(stdin, stdout)
	b.cmd = cmd
	kill := func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
	}
	if err := runHandshakeWithTimeout(b, protocolVersion, kill); err != nil {
		return nil, err
	}
	return b, nil
}
