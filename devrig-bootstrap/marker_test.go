package main

import (
	"os"
	"path/filepath"
	"testing"
)

func writeMarker(t *testing.T, home string, pid int64, createdAt, baseURL string) {
	t.Helper()
	dir := markersDir(home)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	body := `{"schema":1,"pid":` + itoa(pid) + `,"createdAt":"` + createdAt + `"`
	if baseURL != "" {
		body += `,"mcpSteroidServer":{"baseUrl":"` + baseURL + `"}`
	}
	body += `}`
	if err := os.WriteFile(filepath.Join(dir, itoa(pid)+".mcp-steroid"), []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
}

func itoa(n int64) string { // tiny helper so the test has no strconv import noise
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		b = append([]byte{'-'}, b...)
	}
	return string(b)
}

func TestDiscoverIdeEndpointsNewestFirstAndFilters(t *testing.T) {
	home := t.TempDir()
	writeMarker(t, home, 100, "2026-07-03T10:00:00Z", "http://127.0.0.1:6315/mcp") // older
	writeMarker(t, home, 200, "2026-07-03T12:00:00Z", "http://127.0.0.1:6316/mcp") // newer
	writeMarker(t, home, 300, "2026-07-03T13:00:00Z", "")                          // no server -> filtered
	// A malformed file and a non-marker file must both be ignored, not crash.
	os.WriteFile(filepath.Join(markersDir(home), "400.mcp-steroid"), []byte("{garbage"), 0o644)
	os.WriteFile(filepath.Join(markersDir(home), "notes.txt"), []byte("ignore me"), 0o644)

	got := discoverIdeEndpoints(home)
	if len(got) != 2 {
		t.Fatalf("want 2 endpoints, got %d: %+v", len(got), got)
	}
	if got[0].pid != 200 || got[0].baseURL != "http://127.0.0.1:6316/mcp" {
		t.Fatalf("newest must be first, got %+v", got[0])
	}
	if got[1].pid != 100 {
		t.Fatalf("older must be second, got %+v", got[1])
	}
}

func TestDiscoverIdeEndpointsEmptyWhenNoDir(t *testing.T) {
	if got := discoverIdeEndpoints(t.TempDir()); len(got) != 0 {
		t.Fatalf("want empty, got %+v", got)
	}
}
