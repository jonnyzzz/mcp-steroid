package main

import (
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"
)

const (
	installShURL  = "https://mcp-steroid.jonnyzzz.com/install.sh"
	installPs1URL = "https://mcp-steroid.jonnyzzz.com/install.ps1"
)

// ensureInstall starts runner() once, guarded by an atomically-created lock file.
// Returns started=false if devrig is already installed or an install is in progress.
func ensureInstall(home string, runner func() error) (bool, error) {
	if installState(home) != "absent" {
		return false, nil
	}
	lp := lockPath(home)
	if err := os.MkdirAll(filepath.Dir(lp), 0o755); err != nil {
		return false, err
	}
	// Reclaim a stale lock left by a crashed/killed install before the O_EXCL gate.
	if lockIsStale(home) {
		// Log (never swallow) a reclaim failure; the O_EXCL open below then fails
		// loudly with a distinct error, leaving the stale lock for diagnosis.
		if rerr := os.Remove(lp); rerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: failed to remove stale lock: " + rerr.Error() + "\n")
		}
	}
	// O_EXCL makes lock creation the single-flight gate across concurrent bootstraps.
	f, err := os.OpenFile(lp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
	if err != nil {
		return false, nil // someone else holds the lock
	}
	f.Close()
	go runInstallAttempt(home, lp, runner)
	return true, nil
}

// runInstallAttempt runs one install, always releasing the lock, and records the outcome:
// a failure writes the failure marker (read back by status/hook); success clears it.
// While the install runs it heartbeats the lock so a crash/Claude-restart (which kills this
// non-detached install) is detected as a stale lock within installLockStaleAfter and retried.
// Extracted from the goroutine so it can be tested synchronously.
func runInstallAttempt(home, lp string, runner func() error) {
	defer os.Remove(lp)
	stop := make(chan struct{})
	go heartbeatLock(lp, stop)
	defer close(stop)
	if rerr := runner(); rerr != nil {
		// Fail loudly to stderr; never silent.
		os.Stderr.WriteString("devrig-bootstrap: install failed: " + rerr.Error() + "\n")
		writeFailedMarker(home, rerr.Error())
		return
	}
	// Success: drop any stale failure record from a prior attempt.
	if err := os.Remove(failedMarkerPath(home)); err != nil && !os.IsNotExist(err) {
		os.Stderr.WriteString("devrig-bootstrap: failed to clear failure marker: " + err.Error() + "\n")
	}
}

// heartbeatLock refreshes the lock's mtime every heartbeatInterval until stopped, so a live
// install keeps the lock "fresh" and a dead one (mtime frozen) is reclaimed as stale.
func heartbeatLock(lp string, stop <-chan struct{}) {
	t := time.NewTicker(heartbeatInterval)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			touchLock(lp)
		}
	}
}

// touchLock sets the lock's mtime to now (the heartbeat). A missing lock is not an error here.
func touchLock(lp string) {
	now := time.Now()
	if err := os.Chtimes(lp, now, now); err != nil && !os.IsNotExist(err) {
		os.Stderr.WriteString("devrig-bootstrap: lock heartbeat failed: " + err.Error() + "\n")
	}
}

// runInstaller runs the canonical installer to completion, tee'ing its output to the install
// log (so progress/diagnostics are inspectable) and to OUR stderr — never stdout (JSON-RPC).
func runInstaller(home string) error {
	out := io.Writer(os.Stderr)
	if lf, err := os.OpenFile(logPath(home), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644); err != nil {
		os.Stderr.WriteString("devrig-bootstrap: cannot open install log (continuing without it): " + err.Error() + "\n")
	} else {
		defer lf.Close()
		out = io.MultiWriter(os.Stderr, lf)
	}
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
			"-Command", "Invoke-RestMethod -Uri "+installPs1URL+" | Invoke-Expression")
	} else {
		// curl|sh, mirroring bin/install-devrig
		cmd = exec.Command("sh", "-c", "curl -fsSL "+installShURL+" | sh")
	}
	cmd.Stdout = out
	cmd.Stderr = out
	return cmd.Run()
}
