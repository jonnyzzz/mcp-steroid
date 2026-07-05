package main

import "fmt"

const statuslineFlag = "--statusline"

const (
	ansiReset  = "\x1b[0m"
	ansiYellow = "\x1b[33m"
	ansiRed    = "\x1b[31m"
	ansiGreen  = "\x1b[32m"
)

// statusLineText returns the plain progress segment for the current install state, or "" when there is
// nothing to show. One source of truth for both the status-line bar and the UserPromptSubmit hook.
func statusLineText(home string) string {
	switch installState(home) {
	case "installing":
		done := installedMB(home)
		pct := done * 100 / approxInstallMB
		if pct > 99 {
			pct = 99
		}
		return fmt.Sprintf("devrig %d%% · %d/%d MB", pct, done, approxInstallMB)
	case "failed":
		return "devrig ⚠ /devrig:setup"
	case "installed":
		return "devrig ✓"
	default: // absent
		return ""
	}
}

// statusLineRender wraps statusLineText in an ANSI color appropriate to the state when color is enabled.
func statusLineRender(home string, color bool) string {
	txt := statusLineText(home)
	if txt == "" || !color {
		return txt
	}
	var c string
	switch installState(home) {
	case "installing":
		c = ansiYellow
	case "failed":
		c = ansiRed
	case "installed":
		c = ansiGreen
	default:
		return txt
	}
	return c + txt + ansiReset
}
