package main

import (
	"fmt"
	"os"
)

func main() {
	home := homeDir()

	// Status-line mode: print the one-line progress segment and exit. Invoked by Claude's statusLine
	// command every refresh; must be fast, stdout-only, exit 0.
	if len(os.Args) > 1 && os.Args[1] == statuslineFlag {
		fmt.Fprintln(os.Stdout, statusLineRender(home, os.Getenv("NO_COLOR") == ""))
		return
	}

	// Detached supervisor role: run a single install attempt (heartbeating the lock and recording the
	// outcome) and exit. Spawned by spawnDetachedInstaller so the download outlives this Claude session.
	if os.Getenv(installerRoleEnv) == "1" {
		runInstallAttempt(home, lockPath(home), func() error { return runInstaller(home) })
		return
	}

	// Kick off the background download in a detached supervisor (single-flight via the lock).
	if _, err := ensureInstall(home, spawnDetachedInstaller); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: ensureInstall: %v\n", err)
	}
	// Serve as an MCP proxy: answers locally until devrig is downloaded, then hot-swaps
	// to `devrig mcp` and fires tools/list_changed so Claude activates it without a restart.
	if err := runProxy(os.Stdin, os.Stdout, home); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}
