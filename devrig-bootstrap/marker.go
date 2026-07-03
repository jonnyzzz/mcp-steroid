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
	baseURL   string
	pid       int64
	createdAt string
}

// markerFileRegex matches the plugin's marker filename: "<pid>.mcp-steroid".
var markerFileRegex = regexp.MustCompile(`^(\d+)\.mcp-steroid$`)

// markerDir is where the IDE plugin writes its pid markers.
func markerDir(home string) string {
	return filepath.Join(home, ".mcp-steroid", "markers")
}

// pidMarkerShape is the minimal subset of PidMarker (schema 1) the bootstrap reads.
type pidMarkerShape struct {
	Pid              int64  `json:"pid"`
	CreatedAt        string `json:"createdAt"`
	McpSteroidServer *struct {
		BaseURL string `json:"baseUrl"`
	} `json:"mcpSteroidServer"`
}

// discoverIdeEndpoints returns every plugin MCP endpoint from the marker directory, newest
// (createdAt) first. Unreadable/malformed markers and markers without an MCP endpoint are skipped
// (logged to stderr, never fatal). stdout is never touched.
func discoverIdeEndpoints(home string) []ideEndpoint {
	entries, err := os.ReadDir(markerDir(home))
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
		data, rerr := os.ReadFile(filepath.Join(markerDir(home), e.Name()))
		if rerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: reading marker " + e.Name() + ": " + rerr.Error() + "\n")
			continue
		}
		var m pidMarkerShape
		if jerr := json.Unmarshal(data, &m); jerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: skipping malformed marker " + e.Name() + ": " + jerr.Error() + "\n")
			continue
		}
		if m.McpSteroidServer == nil || m.McpSteroidServer.BaseURL == "" {
			continue
		}
		out = append(out, ideEndpoint{baseURL: m.McpSteroidServer.BaseURL, pid: m.Pid, createdAt: m.CreatedAt})
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].createdAt != out[j].createdAt {
			return out[i].createdAt > out[j].createdAt // newest first
		}
		return out[i].pid > out[j].pid
	})
	return out
}
