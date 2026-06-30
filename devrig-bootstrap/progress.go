package main

import (
	"io/fs"
	"os"
	"path/filepath"
	"time"
)

// Approximate total install size (devrig binary + bundled JDK) for the progress readout.
// Measured ~440 MB on macOS/arm64; rounded up so the readout doesn't exceed the total.
const approxInstallMB = 500

func markersDir(home string) string { return filepath.Join(home, ".mcp-steroid", "markers") }

func failedMarkerPath(home string) string {
	return filepath.Join(markersDir(home), "bootstrap-install.failed")
}

// logPath is where the installer's output is tee'd so progress/diagnostics are inspectable.
func logPath(home string) string {
	return filepath.Join(markersDir(home), "bootstrap-install.log")
}

// writeFailedMarker records why the last install attempt failed (read back by status/hook).
func writeFailedMarker(home, reason string) {
	_ = os.MkdirAll(markersDir(home), 0o755)
	if err := os.WriteFile(failedMarkerPath(home), []byte(reason), 0o644); err != nil {
		os.Stderr.WriteString("devrig-bootstrap: failed to write failure marker: " + err.Error() + "\n")
	}
}

func readFailedReason(home string) string {
	b, err := os.ReadFile(failedMarkerPath(home))
	if err != nil {
		return ""
	}
	return string(b)
}

// installedMB is the size of ~/.mcp-steroid so far, in whole MB — a coarse download-progress proxy
// (the canonical installer downloads silently via curl, so byte-on-disk growth is the signal we have).
func installedMB(home string) int64 {
	var total int64
	root := filepath.Join(home, ".mcp-steroid")
	_ = filepath.WalkDir(root, func(_ string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, ierr := d.Info(); ierr == nil {
			total += info.Size()
		}
		return nil
	})
	return total / (1024 * 1024)
}

// installElapsed is how long the current attempt has been running, derived from the lock's mtime.
func installElapsed(home string) time.Duration {
	info, err := os.Stat(lockPath(home))
	if err != nil {
		return 0
	}
	return time.Since(info.ModTime())
}

func fmtElapsed(d time.Duration) string {
	if d <= 0 {
		return "0s"
	}
	return d.Round(time.Second).String()
}
