//go:build windows

package main

import "syscall"

// Windows process-creation flags (not all are exported by the syscall package across Go versions, so
// they are defined here). Together they detach the installer from the bootstrap's console and, if
// Claude placed the bootstrap in a kill-on-close Job Object, break the installer out of it so it
// survives Claude exiting.
const (
	createNewProcessGroup  = 0x00000200
	detachedProcess        = 0x00000008
	createBreakawayFromJob = 0x01000000
)

// detachSysProcAttr detaches the installer so it outlives the bootstrap (and thus Claude).
func detachSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{
		CreationFlags: createNewProcessGroup | detachedProcess | createBreakawayFromJob,
	}
}
