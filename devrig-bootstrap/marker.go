package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
)

// ideEndpoint is a running IDE's Streamable-HTTP MCP endpoint discovered from a pid marker.
type ideEndpoint struct {
	mcpURL    string
	headers   map[string]string // e.g. {"Authorization": "Bearer <token>"} — forwarded on every POST
	pid       int64
	createdAt string
}

// markerFileRegex matches the plugin's marker filename: "<pid>.mcp-steroid".
// (markersDir lives in progress.go — the shared marker/lock directory.)
var markerFileRegex = regexp.MustCompile(`^(\d+)\.mcp-steroid$`)

// pidMarkerShape is the minimal subset of PidMarker (schema 1) the bootstrap reads.
// Field names mirror mcp-steroid-server/.../McpSteroidServerInfo.kt: the URL is `mcpUrl`
// (NOT `baseUrl`) and `headers` carries the bearer token the plugin expects.
type pidMarkerShape struct {
	Pid              int64  `json:"pid"`
	CreatedAt        string `json:"createdAt"`
	McpSteroidServer *struct {
		McpURL  string            `json:"mcpUrl"`
		Headers map[string]string `json:"headers"`
	} `json:"mcpSteroidServer"`
}

// discoverIdeEndpoints returns every plugin MCP endpoint from the marker directory, newest
// (createdAt) first. Unreadable/malformed markers and markers without an MCP endpoint are skipped
// (logged to stderr, never fatal). stdout is never touched.
func discoverIdeEndpoints(home string) []ideEndpoint {
	entries, err := os.ReadDir(markersDir(home))
	if err != nil {
		if !os.IsNotExist(err) {
			os.Stderr.WriteString("devrig-bootstrap: reading marker dir: " + err.Error() + "\n")
		}
		return nil
	}
	var out []ideEndpoint
	for _, e := range entries {
		if e.IsDir() || !markerFileRegex.MatchString(e.Name()) {
			continue
		}
		data, rerr := os.ReadFile(filepath.Join(markersDir(home), e.Name()))
		if rerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: reading marker " + e.Name() + ": " + rerr.Error() + "\n")
			continue
		}
		var m pidMarkerShape
		if jerr := json.Unmarshal(data, &m); jerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: skipping malformed marker " + e.Name() + ": " + jerr.Error() + "\n")
			continue
		}
		if m.McpSteroidServer == nil || m.McpSteroidServer.McpURL == "" {
			continue
		}
		out = append(out, ideEndpoint{
			mcpURL:    m.McpSteroidServer.McpURL,
			headers:   m.McpSteroidServer.Headers,
			pid:       m.Pid,
			createdAt: m.CreatedAt,
		})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].createdAt != out[j].createdAt {
			return out[i].createdAt > out[j].createdAt // newest first
		}
		return out[i].pid > out[j].pid
	})
	return out
}
