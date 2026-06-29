package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

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
// Order matters: an installed launcher always wins over a stale lock.
func installState(home string) string {
	if launcherPresent(home) {
		return "installed"
	}
	if _, err := os.Stat(lockPath(home)); err == nil {
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
