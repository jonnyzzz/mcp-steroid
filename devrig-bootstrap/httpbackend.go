package main

import (
	"encoding/json"
	"io"
	"os"
)

// newHTTPBackend connects to a running IDE's Streamable-HTTP MCP endpoint and presents it to the
// proxy as an ordinary *backend. The proxy writes client messages into b.writer and reads backend
// responses from b.reader exactly as it does for the stdio devrig backend; a pump goroutine bridges
// those pipes to HTTP POSTs. b.shutdown stops the goroutine and closes the response side so the
// proxy's backend pump observes EOF.
func newHTTPBackend(mcpURL string, headers map[string]string, protocolVersion string) (*backend, error) {
	c := newHttpMcpClient(mcpURL, headers)

	// MCP handshake over HTTP: initialize (captures the session), then notifications/initialized.
	initReq, _ := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": backendInitID, "method": "initialize",
		"params": map[string]any{
			"protocolVersion": protocolVersion,
			"capabilities":    map[string]any{},
			"clientInfo":      map[string]any{"name": "devrig-bootstrap-proxy", "version": "0.0.0"},
		},
	})
	if _, err := c.post(initReq); err != nil {
		return nil, err
	}
	initedNotif, _ := json.Marshal(notif("notifications/initialized"))
	if _, err := c.post(initedNotif); err != nil {
		return nil, err
	}

	// reqR/reqW: proxy -> pump (client messages to POST). respR/respW: pump -> proxy (HTTP responses).
	reqR, reqW := io.Pipe()
	respR, respW := io.Pipe()
	b := &backend{
		writer: newMsgWriter(reqW),
		reader: newMsgReader(respR),
	}
	b.shutdown = func() {
		// Closing both ends unblocks the pump's read and the proxy's backend read.
		_ = reqR.Close()
		_ = reqW.Close()
		_ = respW.Close()
	}

	go func() {
		defer respW.Close()
		in := newMsgReader(reqR)
		respWriter := newMsgWriter(respW)
		for {
			_, raw, err := in.read()
			if err != nil {
				return // reqR closed by shutdown, or EOF
			}
			body, perr := c.post(raw)
			if perr != nil {
				os.Stderr.WriteString("devrig-bootstrap: http backend post failed: " + perr.Error() + "\n")
				return // treat a dead IDE endpoint as backend exit; proxy falls back / waits for Tier 2
			}
			if body == nil {
				continue // notification: 202, nothing to forward
			}
			if werr := respWriter.writeRaw(body); werr != nil {
				os.Stderr.WriteString("devrig-bootstrap: http backend forward failed: " + werr.Error() + "\n")
				return
			}
		}
	}()

	return b, nil
}
