package main

import (
	"runtime"
	"testing"
)

// detachSysProcAttr must return the platform-specific attributes that reparent the installer so it
// outlives the bootstrap (and thus Claude). On unix that is a new session (Setsid); on Windows it is
// the detached-process creation flags (exercised via cross-compile, asserted non-nil here).
func TestDetachSysProcAttr(t *testing.T) {
	attr := detachSysProcAttr()
	if attr == nil {
		t.Fatal("detachSysProcAttr must not be nil — the installer would die with the bootstrap")
	}
	if runtime.GOOS != "windows" && !attr.Setsid {
		t.Fatal("unix: detach must set Setsid so the installer survives parent death")
	}
}
