package main

import (
	"fmt"
	"os"
)

func main() {
	if err := Serve(os.Stdin, os.Stdout); err != nil {
		fmt.Fprintf(os.Stderr, "devrig-bootstrap: %v\n", err)
		os.Exit(1)
	}
}
