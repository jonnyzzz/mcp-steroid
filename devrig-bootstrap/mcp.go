package main

import (
	"bufio"
	"encoding/json"
	"io"
)

type rpcRequest struct {
	Jsonrpc string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type rpcResponse struct {
	Jsonrpc string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result"`
}

const serverName = "devrig-bootstrap"

func toolsList() any {
	return map[string]any{"tools": []any{
		map[string]any{
			"name": "devrig_setup",
			"description": "Reports devrig IDE-bridge install status. devrig is downloading in the " +
				"background; once it finishes, restart Claude to activate the full IDE bridge.",
			"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
		},
	}}
}

// handle returns (result, isNotification). Notifications produce no response.
func handle(req rpcRequest) (any, bool) {
	switch req.Method {
	case "initialize":
		return map[string]any{
			"protocolVersion": "2024-11-05",
			"capabilities":    map[string]any{"tools": map[string]any{}},
			"serverInfo":      map[string]any{"name": serverName, "version": "0.0.0"},
		}, false
	case "tools/list":
		return toolsList(), false
	case "tools/call":
		return toolCall(req.Params), false // implemented in Task 4
	default:
		if len(req.ID) == 0 { // a notification (no id) we don't handle
			return nil, true
		}
		return map[string]any{}, false
	}
}

func Serve(in io.Reader, out io.Writer) error {
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 0, 64*1024), 8*1024*1024)
	enc := json.NewEncoder(out)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var req rpcRequest
		if err := json.Unmarshal(line, &req); err != nil {
			continue // ignore malformed; never crash the stdio loop
		}
		result, isNotif := handle(req)
		if isNotif || len(req.ID) == 0 {
			continue
		}
		if err := enc.Encode(rpcResponse{Jsonrpc: "2.0", ID: req.ID, Result: result}); err != nil {
			return err
		}
	}
	return sc.Err()
}
