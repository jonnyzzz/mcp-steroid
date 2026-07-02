package main

import (
	"encoding/json"
	"fmt"
	"os"
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
			"name": "devrig_status",
			"description": "Check devrig install/download progress. Call this when the user asks " +
				"whether devrig is ready or how the download is going. devrig is downloading in the " +
				"background; when it finishes, its full IDE toolset activates automatically on your " +
				"next message — no restart needed.",
			"inputSchema": map[string]any{"type": "object", "properties": map[string]any{}},
		},
	}}
}

// initializeResult is the MCP initialize response. It advertises listChanged on tools,
// resources, and prompts so Claude registers its list_changed handlers and re-fetches
// each list when the proxy hot-swaps to the real devrig backend (see proxy.go).
// protocolVersion echoes the client's requested version (baseline fallback when empty).
func initializeResult(protocolVersion string) map[string]any {
	if protocolVersion == "" {
		protocolVersion = "2024-11-05"
	}
	return map[string]any{
		"protocolVersion": protocolVersion,
		"capabilities": map[string]any{
			"tools":     map[string]any{"listChanged": true},
			"resources": map[string]any{"listChanged": true},
			"prompts":   map[string]any{"listChanged": true},
		},
		"serverInfo": map[string]any{"name": serverName, "version": "0.0.0"},
	}
}

// handle returns (result, isNotification). Notifications produce no response.
// Retained for the proxy's pre-swap local path (see proxy.go localResult).
func handle(req rpcRequest) (any, bool) {
	switch req.Method {
	case "initialize":
		var params struct {
			ProtocolVersion string `json:"protocolVersion"`
		}
		if len(req.Params) > 0 {
			if err := json.Unmarshal(req.Params, &params); err != nil {
				// Benign: an unparseable params yields the empty-string fallback,
				// which is the correct baseline default. Log to stderr (never stdout,
				// which is the JSON-RPC channel) so the failure is not silent.
				fmt.Fprintf(os.Stderr, "devrig-bootstrap: initialize params unparseable, using baseline protocol: %v\n", err)
			}
		}
		return initializeResult(params.ProtocolVersion), false
	case "tools/list":
		return toolsList(), false
	case "tools/call":
		return toolCall(req.Params), false
	case "resources/list":
		return map[string]any{"resources": []any{}}, false
	case "prompts/list":
		return map[string]any{"prompts": []any{}}, false
	default:
		if len(req.ID) == 0 {
			return nil, true
		}
		return map[string]any{}, false
	}
}

