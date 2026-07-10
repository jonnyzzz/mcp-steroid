# test-integration-windows — Agent Guide

**Windows-only** behaviour tests. They verify things that can only be observed on a real Windows agent
against the real Windows build of the tools:

| Test | Verifies |
|---|---|
| `ClaudeWindowsLaunchTest` | How Claude Code's **Windows** build resolves + launches a plugin stdio MCP `command` (extensionless → `.cmd` via cross-spawn/PATHEXT; bare name doesn't resolve) **and every candidate hook form** (exec x3 + shell x3). Live counterpart to jonnyzzz/mcp-steroid#253. |
| `InstallerScriptWindowsTest` | The generated `install.ps1` (from `:installer-gen`) is ASCII-only and **parses** under Windows PowerShell 5.1. See #254. |

## Confirmed on real Windows — cross-validated on TeamCity **and** GitHub `windows-latest` (#253)

Probe log format: `LAUNCHED tag=<tag> via=<cmd|sh|shell|script>`.

**MCP stdio (script-only, zero deps):**
- ✅ extensionless `${CLAUDE_PLUGIN_ROOT}/bin/<stem>` resolves to `<stem>.cmd` via cross-spawn/PATHEXT
  (`via=cmd`); the extensionless Unix script is NOT run on Windows.
- ✅ bare command name does NOT resolve (plugin `bin/` isn't on the MCP subprocess PATH).

**Hooks — the full matrix (corrects the earlier "hooks don't run on Windows" claim: they DO):**

| Hook form | Fires on Windows? |
|---|:-:|
| exec-form, extensionless path | ❌ (direct spawn, not runnable) |
| exec-form, `.cmd` path | ❌ (direct spawn can't launch a `.cmd`) |
| exec-form, `cmd /c <.cmd>` | ✅ (`cmd.exe` is a real exe) |
| shell-form, default (Git Bash) inline cmd | ✅ |
| shell-form, `"shell":"powershell"` | ✅ |
| **shell-form → `#!/bin/sh` script (the devrig hook form)** | ✅ (runs via Git Bash) |

**Key:** devrig's shipped hooks (`check-devrig`, `devrig-progress`, `devrig-recover`) are shell-form
`sh` scripts → they **work on Windows via Git Bash**. The one dependency is **Git Bash on Windows**
(default hook shell for `sh`); absent it, an `sh` hook fails (and PowerShell can't run it). MCP has no
such dependency. Exec-form hooks are only viable for scripts via the `cmd /c` wrapper — prefer
shell-form.

## Why the whole suite is gated at the Gradle task level

This suite is **structurally incompatible** with macOS/Linux (Windows Claude build, `cmd.exe`/PATHEXT
resolution, Windows PowerShell). Per the root `CLAUDE.md` rule — *"The only acceptable skip is at the
Gradle task level (`enabled = !condition`) when an entire suite is structurally incompatible with the
platform"* — `build.gradle.kts` sets `tasks.test { enabled = OperatingSystem.current().isWindows }`.
There are deliberately **no** runtime `assumeTrue` / `@EnabledOnOs` / `TestAbortedException` skips.

So on macOS/Linux, `./gradlew :test-integration-windows:test` and `./gradlew ciWindowsTests` are
no-ops (the task is skipped); `compileTestKotlin` still runs, so the tests stay compilation-checked
everywhere.

## Running

```bash
# On Windows (real behaviour):
./gradlew :test-integration-windows:test
./gradlew ciWindowsTests           # the CI aggregator TeamCity invokes

# On macOS/Linux: both are skipped (see above). Verify it still compiles:
./gradlew :test-integration-windows:compileTestKotlin
```

`ClaudeWindowsLaunchTest` downloads the official `win32-x64` `claude.exe` (cached under
`build/windows-test-cache/`) and needs **no API key** — Claude spawns plugin MCP servers during
session init, before the `-p` turn fails auth. TeamCity wiring lives in the separate
`~/Work/mcp-steroid-teamcity` repo (`builds/_18_windows_tests.kt`).
