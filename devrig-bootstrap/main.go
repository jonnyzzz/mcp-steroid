package main

import (
	"fmt"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	home := homeDir()

	// Status-line mode: print the one-line progress segment and exit. Invoked by Claude's statusLine
	// command every refresh; must be fast, stdout-only, exit 0.
	if len(os.Args) > 1 && os.Args[1] == statuslineFlag {
		fmt.Fprintln(os.Stdout, statusLineRender(home, os.Getenv("NO_COLOR") == ""))
		return
	}

	// Cleanup mode: remove our status-line bar if present, then exit. Invoked by the SessionStart hook
	// as a self-heal when devrig is installed but a stale bar remains (e.g. after a hard kill).
	if len(os.Args) > 1 && os.Args[1] == "--remove-statusline" {
		if err := removeStatusLine(home); err != nil {
			fmt.Fprintf(os.Stderr, "devrig-bootstrap: remove-statusline: %v\n", err)
		}
		return
	}

	// Install mode: decide bar vs hook and (in bar mode) install the bar now, then exit. Invoked by the
	// SessionStart hook so a freshly-started instance writes the bar as EARLY as possible — before its
	// first interaction — so the instance that triggered the download shows the bar itself, not just
	// other already-running instances. Idempotent with the MCP server's own manageStatusLine.
	if len(os.Args) > 1 && os.Args[1] == "--install-statusline" {
		applyStatusLineMode(home)
		return
	}

	// Print the total install size in MB (single source: approxInstallMB) for the SessionStart hook copy.
	if len(os.Args) > 1 && os.Args[1] == "--install-size-mb" {
		fmt.Fprintf(os.Stdout, "%d\n", approxInstallMB)
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
	// Decide how to surface download progress and (in bar mode) install the transient status line.
	cleanup := manageStatusLine(home)
	defer cleanup()

	// Remove the bar on termination too (Claude sends SIGTERM/SIGINT when closing the MCP server).
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sig
		cleanup()
		os.Exit(0)
	}()

	// Serve as an MCP proxy: answers locally until devrig is downloaded, then hot-swaps to `devrig mcp`
	// and fires tools/list_changed so Claude activates it without a restart. Remove the bar once devrig
	// is live (Tier-2 swap).
	p := newProxy(os.Stdin, os.Stdout, home)
	p.onSwapToDevrig = func() { _ = removeStatusLine(home) }
	if err := p.run(); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}

// applyStatusLineMode decides bar vs hook mode and applies it: in bar mode it installs the bar into
// ~/.claude/settings.json (when devrig is not yet installed AND the user has no status line anywhere);
// when devrig is already installed it self-heals any stale bar. Records the chosen mode in the owner
// marker and returns it. Idempotent — safe to call from both the SessionStart hook and the MCP server.
func applyStatusLineMode(home string) string {
	cwd, _ := os.Getwd()
	mode := "hook"
	if installState(home) != "installed" && !shouldUseHookMode(home, cwd) {
		if self, err := os.Executable(); err == nil {
			if ierr := installStatusLine(home, self); ierr == nil {
				mode = "bar"
			} else {
				fmt.Fprintf(os.Stderr, "devrig-bootstrap: install statusLine: %v\n", ierr)
			}
		}
	} else if installState(home) == "installed" {
		// Self-heal: clean any stale bar we may have left before devrig finished.
		_ = removeStatusLine(home)
	}
	writeStatuslineOwner(home, mode)
	return mode
}

// manageStatusLine applies the status-line mode and returns an idempotent cleanup func that removes the
// bar (bar mode only) when the MCP server exits or devrig goes live.
func manageStatusLine(home string) func() {
	mode := applyStatusLineMode(home)
	return func() {
		if mode == "bar" {
			_ = removeStatusLine(home)
		}
	}
}
