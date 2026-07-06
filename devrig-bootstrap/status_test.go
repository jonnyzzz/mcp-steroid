package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestInstallState(t *testing.T) {
	home := t.TempDir()
	if got := installState(home); got != "absent" {
		t.Fatalf("fresh home: want absent, got %s", got)
	}

	// lock present -> installing
	mk := filepath.Join(home, ".mcp-steroid", "markers")
	os.MkdirAll(mk, 0o755)
	os.WriteFile(filepath.Join(mk, "bootstrap-install.lock"), []byte("1"), 0o644)
	if got := installState(home); got != "installing" {
		t.Fatalf("lock present: want installing, got %s", got)
	}

	// launcher present -> installed (wins over lock)
	bin := filepath.Join(home, ".mcp-steroid", "bin")
	os.MkdirAll(bin, 0o755)
	os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)
	if got := installState(home); got != "installed" {
		t.Fatalf("launcher present: want installed, got %s", got)
	}
}

func TestInstallStateFailed(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	// failure marker, no lock, no launcher -> failed
	os.WriteFile(failedMarkerPath(home), []byte("boom"), 0o644)
	if got := installState(home); got != "failed" {
		t.Fatalf("failure marker: want failed, got %s", got)
	}
	// a fresh lock means an install is running again -> installing wins over the marker
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	if got := installState(home); got != "installing" {
		t.Fatalf("fresh lock over marker: want installing, got %s", got)
	}
}

func TestStatusMessage(t *testing.T) {
	cases := []struct {
		name   string
		setup  func(home string)
		expect []string // all substrings must be present
	}{
		{"installing", func(h string) {
			os.MkdirAll(markersDir(h), 0o755)
			os.WriteFile(lockPath(h), []byte("1"), 0o644)
		}, []string{"devrig", itoa(approxInstallMB) + " MB", "downloading", "activates automatically"}},
		{"failed", func(h string) {
			os.MkdirAll(markersDir(h), 0o755)
			os.WriteFile(failedMarkerPath(h), []byte("network down"), 0o644)
		}, []string{"network down", "/devrig:setup"}},
		{"installed", func(h string) {
			bin := filepath.Join(h, ".mcp-steroid", "bin")
			os.MkdirAll(bin, 0o755)
			os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)
		}, []string{"active"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			home := t.TempDir()
			c.setup(home)
			msg := statusMessage(home)
			for _, sub := range c.expect {
				if !strings.Contains(msg, sub) {
					t.Fatalf("%s message %q missing %q", c.name, msg, sub)
				}
			}
		})
	}
}

func TestStatusMessageMentionsRunningIdeFastPath(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755) // installing state
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	writeMarker(t, home, 555, "2026-07-03T12:00:00Z", "http://127.0.0.1:6315/mcp")
	msg := statusMessage(home)
	if !strings.Contains(msg, "IDE tools available now") {
		t.Fatalf("installing message must mention the open-IDE fast path, got: %s", msg)
	}
}
