package main

import (
	"fmt"
	"os"
)

func main() {
	// Auto-download in the background (Q1=i); single-flight via the lock.
	home := homeDir()
	if _, err := ensureInstall(home, func() error { return runInstaller(home) }); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: ensureInstall: %v\n", err)
	}
	if err := Serve(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}
