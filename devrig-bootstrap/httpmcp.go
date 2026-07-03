package main

import (
	"bytes"
	"fmt"
	"io"
	"net/http"
	"time"
)

// httpMcpClient is a minimal Streamable-HTTP MCP client for the IDE plugin's /mcp endpoint.
// The plugin's transport is request/response JSON (no server->client SSE), so this only POSTs.
type httpMcpClient struct {
	url       string
	sessionID string
	http      *http.Client
}

func newHttpMcpClient(url string) *httpMcpClient {
	return &httpMcpClient{url: url, http: &http.Client{Timeout: 30 * time.Second}}
}

// post sends one JSON-RPC message and returns the response body (nil for 202/empty).
// It captures the Mcp-Session-Id response header so subsequent calls stay in one session.
func (c *httpMcpClient) post(raw []byte) ([]byte, error) {
	req, err := http.NewRequest(http.MethodPost, c.url, bytes.NewReader(raw))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	if c.sessionID != "" {
		req.Header.Set("Mcp-Session-Id", c.sessionID)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if sid := resp.Header.Get("Mcp-Session-Id"); sid != "" {
		c.sessionID = sid
	}
	if resp.StatusCode == http.StatusAccepted || resp.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, resp.Body)
		return nil, nil
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		snippet, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return nil, fmt.Errorf("mcp http %s: %d: %s", c.url, resp.StatusCode, snippet)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return nil, nil
	}
	return body, nil
}
