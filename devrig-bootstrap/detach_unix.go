//go:build !windows

package main

import "syscall"

// detachSysProcAttr detaches the installer into its own session so that when the bootstrap (a child
// of Claude) exits, the installer is reparented to init and keeps running. No Pdeathsig is set, so
// the kernel does not kill it with the parent.
func detachSysProcAttr() *syscall.SysProcAttr {
	return &syscall.SysProcAttr{Setsid: true}
}
