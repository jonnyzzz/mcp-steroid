package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

const installLockStaleAfter = 1 * time.Hour

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
// Returns one of "installed", "installing", or "absent".
// Order matters: an installed launcher always wins over a stale lock.
// A lock that is older than installLockStaleAfter is treated as absent so
// retries are unblocked after a crashed/killed install.
func installState(home string) string {
	if launcherPresent(home) {
		return "installed"
	}
	if _, err := os.Stat(lockPath(home)); err == nil {
		if lockIsStale(home) {
			return "absent"
		}
		return "installing"
	}
	return "absent"
}

func toolCall(_ json.RawMessage) any {
	var msg string
	switch installState(homeDir()) {
	case "installed":
		msg = "devrig is installed. Restart Claude to activate the full IDE bridge."
	case "installing":
		msg = "devrig is downloading in the background (~300 MB). Re-run devrig_setup to re-check; restart Claude once it completes."
	default:
		msg = "devrig is not installed yet. The download will start automatically; re-run devrig_setup to check progress."
	}
	return map[string]any{"content": []any{map[string]any{"type": "text", "text": msg}}}
}
