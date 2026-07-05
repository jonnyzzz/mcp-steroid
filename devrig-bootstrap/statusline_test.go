package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStatusLineTextByState(t *testing.T) {
	// installing: lock present, no launcher
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	txt := statusLineText(home)
	if !strings.HasPrefix(txt, "devrig ") || !strings.Contains(txt, "MB") || !strings.Contains(txt, "%") {
		t.Fatalf("installing: want 'devrig NN%% · X/500 MB', got %q", txt)
	}

	// failed
	home2 := t.TempDir()
	os.MkdirAll(markersDir(home2), 0o755)
	os.WriteFile(failedMarkerPath(home2), []byte("boom"), 0o644)
	if !strings.Contains(statusLineText(home2), "/devrig:setup") {
		t.Fatalf("failed: want a /devrig:setup hint, got %q", statusLineText(home2))
	}

	// installed
	home3 := t.TempDir()
	bin := filepath.Join(home3, ".mcp-steroid", "bin")
	os.MkdirAll(bin, 0o755)
	os.WriteFile(filepath.Join(bin, "devrig"), []byte("#!/bin/sh\n"), 0o755)
	if !strings.Contains(statusLineText(home3), "✓") {
		t.Fatalf("installed: want a ✓, got %q", statusLineText(home3))
	}

	// absent -> empty
	if statusLineText(t.TempDir()) != "" {
		t.Fatalf("absent: want empty")
	}
}

func TestStatusLinePercentCappedAt99(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	// Fabricate > total bytes on disk under ~/.mcp-steroid so the naive ratio would exceed 100%.
	big := filepath.Join(home, ".mcp-steroid", "big.bin")
	f, _ := os.Create(big)
	_ = f.Truncate(int64(approxInstallMB+50) * 1024 * 1024)
	f.Close()
	txt := statusLineText(home)
	if strings.Contains(txt, "100%") || strings.Contains(txt, "105%") {
		t.Fatalf("percent must cap at 99%% while installing, got %q", txt)
	}
	if !strings.Contains(txt, "99%") {
		t.Fatalf("want 99%% cap, got %q", txt)
	}
}

func TestStatusLineRenderColorToggle(t *testing.T) {
	home := t.TempDir()
	os.MkdirAll(markersDir(home), 0o755)
	os.WriteFile(lockPath(home), []byte("1"), 0o644)
	if strings.Contains(statusLineRender(home, false), "\x1b[") {
		t.Fatal("color=false must not emit ANSI")
	}
	if !strings.Contains(statusLineRender(home, true), "\x1b[") {
		t.Fatal("color=true must emit ANSI")
	}
}
