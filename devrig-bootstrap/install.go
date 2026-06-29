package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
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
	go func() {
		defer os.Remove(lp)
		if rerr := runner(); rerr != nil {
			// Fail loudly to stderr; never silent.
			os.Stderr.WriteString("devrig-bootstrap: install failed: " + rerr.Error() + "\n")
		}
	}()
	return true, nil
}

// spawnInstaller runs the canonical installer to completion (it is what runner wraps).
func spawnInstaller() error {
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		cmd = exec.Command("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
			"-Command", "Invoke-RestMethod -Uri "+installPs1URL+" | Invoke-Expression")
	} else {
		// curl|sh, mirroring bin/install-devrig
		cmd = exec.Command("sh", "-c", "curl -fsSL "+installShURL+" | sh")
	}
	cmd.Stdout = os.Stderr // installer chatter must never hit OUR stdout (JSON-RPC)
	cmd.Stderr = os.Stderr
	return cmd.Run()
}
