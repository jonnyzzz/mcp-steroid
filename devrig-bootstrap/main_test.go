package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestApplyStatusLineMode covers the logic the SessionStart hook (--install-statusline) and the MCP
// server share: install a bar when downloading with no user status line, else fall back to hook mode.
func TestApplyStatusLineMode(t *testing.T) {
	// Run from a clean cwd so shouldUseHookMode sees no project/local statusLine.
	cwd := t.TempDir()
	old, _ := os.Getwd()
	if err := os.Chdir(cwd); err != nil {
		t.Fatal(err)
	}
	defer os.Chdir(old)

	// bar mode: downloading, no status line anywhere.
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	if mode := applyStatusLineMode(home); mode != "bar" {
		t.Fatalf("no user status line -> want bar, got %q", mode)
	}
	if statuslineOwner(home) != "bar" {
		t.Fatalf("owner marker should be bar, got %q", statuslineOwner(home))
	}
	b, _ := os.ReadFile(claudeSettingsPath(home))
	if !strings.Contains(string(b), statuslineFlag) {
		t.Fatalf("bar should be installed into settings.json, got %s", b)
	}

	// hook mode: user already has a status line -> we touch nothing.
	home2 := t.TempDir()
	os.MkdirAll(markersDir(home2), 0o755)
	os.WriteFile(lockPath(home2), []byte("1"), 0o644)
	os.MkdirAll(filepath.Join(home2, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(home2), []byte(`{"statusLine":{"type":"command","command":"mine.py"}}`), 0o644)
	if mode := applyStatusLineMode(home2); mode != "hook" {
		t.Fatalf("existing status line -> want hook, got %q", mode)
	}
	if statuslineOwner(home2) != "hook" {
		t.Fatalf("owner marker should be hook, got %q", statuslineOwner(home2))
	}
	b2, _ := os.ReadFile(claudeSettingsPath(home2))
	if !strings.Contains(string(b2), "mine.py") {
		t.Fatalf("existing status line must be preserved, got %s", b2)
	}
}
