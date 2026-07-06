package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// The lock is heartbeated (mtime touched) every heartbeatInterval while an install runs, so a lock
// whose mtime is older than installLockStaleAfter means the owning bootstrap died (e.g. Claude was
// restarted mid-download) -- a new bootstrap then reclaims it and retries instead of waiting forever.
const (
	heartbeatInterval     = 20 * time.Second
	installLockStaleAfter = 60 * time.Second
)

// lockIsStale returns true if the lock file exists and its mtime is older than installLockStaleAfter.
func lockIsStale(home string) bool {
	info, err := os.Stat(lockPath(home))
	if err != nil {
		return false
	}
	return time.Since(info.ModTime()) > installLockStaleAfter
}

func homeDir() string {
	if h := os.Getenv("HOME"); h != "" {
		return h
	}
	return os.Getenv("USERPROFILE")
}

func lockPath(home string) string {
	return filepath.Join(home, ".mcp-steroid", "markers", "bootstrap-install.lock")
}

func launcherPresent(home string) bool {
	bin := filepath.Join(home, ".mcp-steroid", "bin")
	for _, n := range []string{"devrig", "devrig.cmd"} {
		if st, err := os.Stat(filepath.Join(bin, n)); err == nil && !st.IsDir() {
			return true
		}
	}
	return false
}

// installState reports the current bootstrap-visible state.
// Returns one of "installed", "installing", "failed", or "absent".
// Order matters: an installed launcher wins; a fresh lock means a download is
// in progress; a failure marker (with no active download) is a terminal "failed"
// the user clears via /devrig:setup; otherwise nothing has started yet.
// A lock older than installLockStaleAfter is ignored so a crashed install
// doesn't wedge the state forever.
func installState(home string) string {
	if launcherPresent(home) {
		return "installed"
	}
	if _, err := os.Stat(lockPath(home)); err == nil && !lockIsStale(home) {
		return "installing"
	}
	if _, err := os.Stat(failedMarkerPath(home)); err == nil {
		return "failed"
	}
	return "absent"
}

// statusMessage is the user-facing text the devrig_status tool returns for the current state.
func statusMessage(home string) string {
	switch installState(home) {
	case "installed":
		return "✅ devrig active — full IDE toolset available."
	case "installing":
		fast := ""
		if len(discoverIdeEndpoints(home)) > 0 {
			fast = " IDE tools available now."
		}
		return fmt.Sprintf(
			"⏳ devrig %d/%d MB (%s) — downloading in the background, activates automatically when done.%s",
			installedMB(home), approxInstallMB, fmtElapsed(installElapsed(home)), fast)
	case "failed":
		reason := readFailedReason(home)
		if reason == "" {
			reason = "unknown error"
		}
		return "❌ devrig install failed: " + reason + ". Run /devrig:setup to retry."
	default:
		return "⏳ devrig starting — activates automatically when ready."
	}
}

func toolCall(_ json.RawMessage) any {
	return map[string]any{"content": []any{map[string]any{"type": "text", "text": statusMessage(homeDir())}}}
}
