//go:build !windows

package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// TestSpawnDetachedInstallerStartsChild proves the detached-spawn mechanics — Start, stdio
// redirection off the inherited fds, and Release — actually launch the child. It points the
// selfExecutable seam at a harmless script (not the real re-exec) so no download runs. Process
// survival past a real parent death is covered by the manual e2e check in the plan; it is not
// honestly unit-testable in-process. POSIX-only: the fake self is a shell script.
func TestSpawnDetachedInstallerStartsChild(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)

	marker := filepath.Join(home, "child-ran")
	script := filepath.Join(home, "fake-self.sh")
	if err := os.WriteFile(script, []byte("#!/bin/sh\ntouch "+marker+"\n"), 0o755); err != nil {
		t.Fatal(err)
	}

	orig := selfExecutable
	selfExecutable = func() (string, error) { return script, nil }
	defer func() { selfExecutable = orig }()

	lp := lockPath(home)
	if err := os.WriteFile(lp, []byte("1"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := spawnDetachedInstaller(home, lp); err != nil {
		t.Fatalf("spawnDetachedInstaller: %v", err)
	}

	// The child runs detached; poll briefly for the marker it touches.
	var ran bool
	for i := 0; i < 200; i++ {
		if _, err := os.Stat(marker); err == nil {
			ran = true
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if !ran {
		t.Fatal("detached child did not run (marker missing) — Start/stdio/Release wiring is broken")
	}
}
