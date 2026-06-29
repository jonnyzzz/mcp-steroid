package main

import (
	"fmt"
	"os"
)

func main() {
	// Auto-download in the background (Q1=i); single-flight via the lock.
	if _, err := ensureInstall(homeDir(), spawnInstaller); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: ensureInstall: %v\n", err)
	}
	if err := Serve(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}
