package main

import (
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestEnsureInstallSingleFlight(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(filepath.Join(home, ".mcp-steroid", "markers"), 0o755)

	var mu sync.Mutex
	spawns := 0
	spawn := func(_, _ string) error { mu.Lock(); spawns++; mu.Unlock(); return nil }

	started, err := ensureInstall(home, spawn)
	if err != nil || !started {
		t.Fatalf("first call: started=%v err=%v", started, err)
	}
	// lock now exists (the stub spawn leaves it — the real detached supervisor owns its lifecycle)
	// -> second call must NOT start a second install.
	started2, _ := ensureInstall(home, spawn)
	if started2 {
		t.Fatalf("second call started a duplicate install")
	}
	if spawns != 1 {
		t.Fatalf("spawn must run exactly once, ran %d", spawns)
	}
}

func TestEnsureInstallSkipsWhenInstalled(t *testing.T) {
	home := t.TempDir()
	bin := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(bin, 0o755)
	os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)

	started, _ := ensureInstall(home, func(_, _ string) error { t.Fatal("must not spawn"); return nil })
	if started {
		t.Fatalf("must not start install when devrig already present")
	}
}

func TestEnsureInstallSpawnFailureReleasesLock(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)

	started, err := ensureInstall(home, func(_, _ string) error { return errFake("no exe") })
	if started || err == nil {
		t.Fatalf("spawn failure must return started=false, err!=nil (got started=%v err=%v)", started, err)
	}
	// A failed spawn must not wedge the state: the lock is released...
	if _, e := os.Stat(lockPath(home)); !os.IsNotExist(e) {
		t.Fatal("spawn failure must release the lock so it doesn't wedge the state")
	}
	// ...and the failure is recorded so the user is routed to /devrig:setup.
	if b, e := os.ReadFile(failedMarkerPath(home)); e != nil || !strings.Contains(string(b), "no exe") {
		t.Fatalf("spawn failure must write the .failed marker with the reason, got %q err=%v", string(b), e)
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
	spawns := 0
	spawn := func(_, _ string) error { mu.Lock(); spawns++; mu.Unlock(); return nil }

	started, err := ensureInstall(homeStale, spawn)
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

	started2, err2 := ensureInstall(homeFresh, spawn)
	if err2 != nil {
		t.Fatalf("fresh lock: unexpected error: %v", err2)
	}
	if started2 {
		t.Fatal("fresh lock: ensureInstall must not start a new install while lock is fresh")
	}
	if spawns != 1 {
		t.Fatalf("only the stale-lock reclaim should have spawned; got %d spawns", spawns)
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

	started, _ := ensureInstall(home, func(_, _ string) error { t.Fatal("must not retry a failed install"); return nil })
	if started {
		t.Fatal("failed state must not auto-retry; user runs /devrig:setup")
	}
}

func TestTouchLockRefreshesMtime(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	lp := lockPath(home)
	os.WriteFile(lp, []byte("1"), 0o644)
	// Back-date well past the stale threshold, then heartbeat once.
	old := time.Now().Add(-10 * time.Minute)
	if err := os.Chtimes(lp, old, old); err != nil {
		t.Fatal(err)
	}
	if !lockIsStale(home) {
		t.Fatal("precondition: back-dated lock should read as stale")
	}
	touchLock(lp)
	if lockIsStale(home) {
		t.Fatal("after heartbeat, lock must be fresh again")
	}
}

type errFake string

func (e errFake) Error() string { return string(e) }
