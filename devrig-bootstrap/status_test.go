package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInstallState(t *testing.T) {
	home := t.TempDir()
	if got := installState(home); got != "absent" {
		t.Fatalf("fresh home: want absent, got %s", got)
	}

	// lock present -> installing
	mk := filepath.Join(home, ".mcp-steroid", "markers")
	os.MkdirAll(mk, 0o755)
	os.WriteFile(filepath.Join(mk, "bootstrap-install.lock"), []byte("1"), 0o644)
	if got := installState(home); got != "installing" {
		t.Fatalf("lock present: want installing, got %s", got)
	}

	// launcher present -> installed (wins over lock)
	bin := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(bin, 0o755)
	os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)
	if got := installState(home); got != "installed" {
		t.Fatalf("launcher present: want installed, got %s", got)
	}
}
