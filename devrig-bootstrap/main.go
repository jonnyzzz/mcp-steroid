package main

import (
	"fmt"
	"os"
)

func main() {
	home := homeDir()
	// Kick off the background download (single-flight via the lock).
	if _, err := ensureInstall(home, func() error { return runInstaller(home) }); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: ensureInstall: %v\n", err)
	}
	// Serve as an MCP proxy: answers locally until devrig is downloaded, then hot-swaps
	// to `devrig mcp` and fires tools/list_changed so Claude activates it without a restart.
	if err := runProxy(os.Stdin, os.Stdout, home); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}
