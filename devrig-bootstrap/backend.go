package main

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
)

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
	if err := b.handshake(protocolVersion); err != nil {
		_ = cmd.Process.Kill()
		return nil, err
	}
	return b, nil
}
