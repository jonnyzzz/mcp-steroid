package main

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestEnsureInstallSingleFlight(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".mcp-steroid", "markers"), 0o755)

	var mu sync.Mutex
	runs := 0
	runner := func() error { mu.Lock(); runs++; mu.Unlock(); return nil }

	started, err := ensureInstall(home, runner)
	if err != nil || !started {
		t.Fatalf("first call: started=%v err=%v", started, err)
	}
	// lock now exists -> second call must NOT start a second install
	started2, _ := ensureInstall(home, runner)
	if started2 {
		t.Fatalf("second call started a duplicate install")
	}
}

func TestEnsureInstallSkipsWhenInstalled(t *testing.T) {
	home := t.TempDir()
	bin := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(bin, 0o755)
	os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)

	started, _ := ensureInstall(home, func() error { t.Fatal("must not run"); return nil })
	if started {
		t.Fatalf("must not start install when devrig already present")
	}
}
