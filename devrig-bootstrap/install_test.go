package main

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
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

func TestEnsureInstallReclaimsStaleLock(t *testing.T) {
	// --- stale lock: ensureInstall must reclaim and start ---
	homeStale := t.TempDir()
	mk := filepath.Join(homeStale, ".mcp-steroid", "markers")
	if err := os.MkdirAll(mk, 0o755); err != nil {
		t.Fatal(err)
	}
	lp := filepath.Join(mk, "bootstrap-install.lock")
	if err := os.WriteFile(lp, []byte("1"), 0o644); err != nil {
		t.Fatal(err)
	}
	// Back-date the lock to 2 hours ago — well past installLockStaleAfter (1 h).
	twoHrsAgo := time.Now().Add(-2 * time.Hour)
	if err := os.Chtimes(lp, twoHrsAgo, twoHrsAgo); err != nil {
		t.Fatal(err)
	}

	var mu sync.Mutex
	runs := 0
	mockRunner := func() error { mu.Lock(); runs++; mu.Unlock(); return nil }

	started, err := ensureInstall(homeStale, mockRunner)
	if err != nil {
		t.Fatalf("stale lock: unexpected error: %v", err)
	}
	if !started {
		t.Fatal("stale lock: ensureInstall must reclaim and return started=true")
	}

	// --- fresh lock: ensureInstall must NOT start a new install ---
	homeFresh := t.TempDir()
	mkFresh := filepath.Join(homeFresh, ".mcp-steroid", "markers")
	if err := os.MkdirAll(mkFresh, 0o755); err != nil {
		t.Fatal(err)
	}
	lpFresh := filepath.Join(mkFresh, "bootstrap-install.lock")
	if err := os.WriteFile(lpFresh, []byte("1"), 0o644); err != nil {
		t.Fatal(err)
	}
	// Leave mtime at now — fresh lock must block.

	started2, err2 := ensureInstall(homeFresh, mockRunner)
	if err2 != nil {
		t.Fatalf("fresh lock: unexpected error: %v", err2)
	}
	if started2 {
		t.Fatal("fresh lock: ensureInstall must not start a new install while lock is fresh")
	}
}

func TestRunInstallAttemptRecordsOutcome(t *testing.T) {
	// failure -> lock released, failure marker written with the reason
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	lp := lockPath(home)
	os.WriteFile(lp, []byte("1"), 0o644)
	runInstallAttempt(home, lp, func() error { return errFake("network down") })
	if _, err := os.Stat(lp); !os.IsNotExist(err) {
		t.Fatal("failure: lock must be released")
	}
	if b, err := os.ReadFile(failedMarkerPath(home)); err != nil || string(b) != "network down" {
		t.Fatalf("failure: marker want 'network down', got %q err=%v", string(b), err)
	}

	// success -> lock released, any prior failure marker cleared
	os.WriteFile(lp, []byte("1"), 0o644)
	runInstallAttempt(home, lp, func() error { return nil })
	if _, err := os.Stat(lp); !os.IsNotExist(err) {
		t.Fatal("success: lock must be released")
	}
	if _, err := os.Stat(failedMarkerPath(home)); !os.IsNotExist(err) {
		t.Fatal("success: failure marker must be cleared")
	}
}

func TestEnsureInstallSkipsWhenFailed(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	os.WriteFile(failedMarkerPath(home), []byte("boom"), 0o644) // terminal failed state

	started, _ := ensureInstall(home, func() error { t.Fatal("must not retry a failed install"); return nil })
	if started {
		t.Fatal("failed state must not auto-retry; user runs /devrig:setup")
	}
}

type errFake string

func (e errFake) Error() string { return string(e) }
