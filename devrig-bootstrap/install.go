package main

import (
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

// installerRoleEnv, when set to "1" in the environment, makes main() run as the detached install
// supervisor (heartbeat the lock, run the installer, record the outcome) instead of the MCP proxy.
const installerRoleEnv = "DEVRIG_BOOTSTRAP_ROLE_INSTALLER"

// selfExecutable resolves the bootstrap binary path. A seam so tests can point the detached spawn at
// a harmless fake instead of re-execing the real installer.
var selfExecutable = os.Executable

// ensureInstall starts the install in a DETACHED supervisor once, guarded by an atomically-created
// lock file. Returns started=false if devrig is already installed or an install is in progress.
// The lock's freshness is the liveness signal: the detached supervisor heartbeats it (see
// runInstallAttempt), so a stale lock means the owner died and a new bootstrap reclaims it.
func ensureInstall(home string, spawn func(home, lp string) error) (bool, error) {
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
	// Hand the lock to a detached supervisor that heartbeats it, runs the install, and records the
	// outcome — so the download continues even if Claude quits before it finishes.
	if serr := spawn(home, lp); serr != nil {
		// Release the lock so it doesn't wedge the state for installLockStaleAfter, and record the
		// failure so the user is routed to /devrig:setup instead of a silent stall.
		if rerr := os.Remove(lp); rerr != nil {
			os.Stderr.WriteString("devrig-bootstrap: failed to release lock after spawn error: " + rerr.Error() + "\n")
		}
		writeFailedMarker(home, "could not start background installer: "+serr.Error())
		return false, serr
	}
	return true, nil
}

// spawnDetachedInstaller re-execs this bootstrap binary as a detached supervisor (installerRoleEnv=1)
// so the ~500 MB download survives Claude quitting. Its stdio is redirected off the inherited fds —
// stdout is Claude's JSON-RPC channel, so a detached child must NOT hold it open — into the install
// log. cmd.Process.Release() detaches it fully: we never wait on or reap it.
func spawnDetachedInstaller(home, _ string) error {
	exe, err := selfExecutable()
	if err != nil {
		return err
	}
	logf, err := os.OpenFile(logPath(home), os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return err
	}
	defer logf.Close()
	devnull, err := os.Open(os.DevNull)
	if err != nil {
		return err
	}
	defer devnull.Close()

	cmd := exec.Command(exe)
	cmd.Env = append(os.Environ(), installerRoleEnv+"=1")
	cmd.Stdin = devnull
	cmd.Stdout = logf
	cmd.Stderr = logf
	cmd.SysProcAttr = detachSysProcAttr()
	if err := cmd.Start(); err != nil {
		return err
	}
	return cmd.Process.Release()
}

// runInstallAttempt runs one install, always releasing the lock, and records the outcome:
// a failure writes the failure marker (read back by status/hook); success clears it.
// It heartbeats the lock while the install runs so a crash (frozen mtime) is detected as a stale
// lock within installLockStaleAfter and retried. Runs inside the detached supervisor (see main).
// Extracted so it can be tested synchronously.
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

// runInstaller runs the canonical installer to completion. It runs ONLY inside the detached
// supervisor, whose stdout+stderr the parent already pointed at the install log (see
// spawnDetachedInstaller); inheriting os.Stderr tees the installer's output there with a single
// writer (no second file handle, no clobbering).
func runInstaller(_ string) error {
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
			"-Command", "Invoke-RestMethod -Uri "+installPs1URL+" | Invoke-Expression")
	} else {
		// curl|sh, mirroring bin/install-devrig
		cmd = exec.Command("sh", "-c", "curl -fsSL "+installShURL+" | sh")
	}
	cmd.Stdout = os.Stderr
	cmd.Stderr = os.Stderr
	return cmd.Run()
}
