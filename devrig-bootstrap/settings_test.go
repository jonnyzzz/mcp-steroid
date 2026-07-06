package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func readSettings(t *testing.T, home string) map[string]json.RawMessage {
	t.Helper()
	b, err := os.ReadFile(claudeSettingsPath(home))
	if err != nil {
		t.Fatalf("read settings: %v", err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("parse settings: %v", err)
	}
	return m
}

func TestInstallStatusLineAddsWhenAbsentAndPreservesKeys(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(home), []byte(`{"theme":"dark"}`), 0o644)

	if err := installStatusLine(home, "/opt/devrig/bootstrap"); err != nil {
		t.Fatalf("install: %v", err)
	}
	m := readSettings(t, home)
	if string(m["theme"]) != `"dark"` {
		t.Fatalf("sibling key clobbered: %s", m["theme"])
	}
	if !strings.Contains(string(m["statusLine"]), statuslineFlag) {
		t.Fatalf("statusLine not added/ours: %s", m["statusLine"])
	}
	// Idempotent: second call keeps exactly one ours statusLine and does not error.
	if err := installStatusLine(home, "/opt/devrig/bootstrap"); err != nil {
		t.Fatalf("install idempotent: %v", err)
	}
}

func TestInstallStatusLineRewritesWhenOurs(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	// A stale ours-bar pointing at an old binary path -> re-writing must update it to the new path
	// (and, in production, bump the file so a late-arming watcher fires).
	os.WriteFile(claudeSettingsPath(home),
		[]byte(`{"statusLine":{"type":"command","command":"/old/boot `+statuslineFlag+`"}}`), 0o644)
	if err := installStatusLine(home, "/new/boot"); err != nil {
		t.Fatalf("install: %v", err)
	}
	cmd := string(readSettings(t, home)["statusLine"])
	if !strings.Contains(cmd, "/new/boot") || strings.Contains(cmd, "/old/boot") {
		t.Fatalf("ours status line should be rewritten to the new path, got %s", cmd)
	}
}

func TestInstallStatusLineSkipsWhenForeignPresent(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(home),
		[]byte(`{"statusLine":{"type":"command","command":"my.sh"}}`), 0o644)
	if err := installStatusLine(home, "/opt/devrig/bootstrap"); err != nil {
		t.Fatalf("install: %v", err)
	}
	if !strings.Contains(string(readSettings(t, home)["statusLine"]), "my.sh") {
		t.Fatal("must not overwrite a foreign statusLine")
	}
}

func TestRemoveStatusLineOnlyIfOurs(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	// ours -> removed
	installStatusLine(home, "/opt/devrig/bootstrap")
	if err := removeStatusLine(home); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if _, ok := readSettings(t, home)["statusLine"]; ok {
		t.Fatal("ours statusLine should be removed")
	}
	// foreign -> preserved
	os.WriteFile(claudeSettingsPath(home),
		[]byte(`{"statusLine":{"type":"command","command":"my.sh"}}`), 0o644)
	if err := removeStatusLine(home); err != nil {
		t.Fatalf("remove foreign: %v", err)
	}
	if !strings.Contains(string(readSettings(t, home)["statusLine"]), "my.sh") {
		t.Fatal("foreign statusLine must be preserved")
	}
}

func TestShouldUseHookMode(t *testing.T) {
	// no settings anywhere -> bar mode (false)
	home := t.TempDir()
	cwd := t.TempDir()
	if shouldUseHookMode(home, cwd) {
		t.Fatal("no statusLine anywhere -> bar mode expected")
	}
	// user has a FOREIGN one -> hook mode
	os.MkdirAll(filepath.Join(home, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(home), []byte(`{"statusLine":{"type":"command","command":"x"}}`), 0o644)
	if !shouldUseHookMode(home, cwd) {
		t.Fatal("foreign user statusLine -> hook mode expected")
	}
	// user has OUR bar -> NOT hook mode (so re-asserting the bar doesn't flip us to hook)
	homeOurs, cwdOurs := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(homeOurs, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(homeOurs),
		[]byte(`{"statusLine":{"type":"command","command":"/boot `+statuslineFlag+`"}}`), 0o644)
	if shouldUseHookMode(homeOurs, cwdOurs) {
		t.Fatal("our own bar must NOT count as a user status line")
	}
	// project local has one -> hook mode
	home2, cwd2 := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(cwd2, ".claude"), 0o755)
	os.WriteFile(filepath.Join(cwd2, ".claude", "settings.local.json"),
		[]byte(`{"statusLine":{"type":"command","command":"y"}}`), 0o644)
	if !shouldUseHookMode(home2, cwd2) {
		t.Fatal("project-local statusLine -> hook mode expected")
	}
	// malformed user settings -> hook mode (never overwrite)
	home3, cwd3 := t.TempDir(), t.TempDir()
	os.MkdirAll(filepath.Join(home3, ".claude"), 0o755)
	os.WriteFile(claudeSettingsPath(home3), []byte(`{ not json`), 0o644)
	if !shouldUseHookMode(home3, cwd3) {
		t.Fatal("malformed user settings -> hook mode expected")
	}
}
