# test-integration-windows — Agent Guide

**Windows-only** behaviour tests. They verify things that can only be observed on a real Windows agent
against the real Windows build of the tools:

| Test | Verifies |
|---|---|
| `ClaudeWindowsLaunchTest` | How Claude Code's **Windows** build resolves + launches a plugin stdio MCP `command` and hooks: an extensionless `${CLAUDE_PLUGIN_ROOT}/bin/probe` resolves via cross-spawn/PATHEXT to the `.cmd` sibling (script-only launcher, no native binary); a bare name does NOT resolve (plugin `bin/` not on the MCP subprocess PATH); the extensionless Unix script is never run. Live counterpart to jonnyzzz/mcp-steroid#253. |
| `InstallerScriptWindowsTest` | The generated `install.ps1` (from `:installer-gen`) is ASCII-only and **parses** under Windows PowerShell 5.1. See #254. |

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
`build/windows-test-cache/`) and needs **no API key** — Claude spawns plugin MCP servers + hooks during
session init, before the `-p` turn fails auth. TeamCity wiring lives in the separate
`~/Work/mcp-steroid-teamcity` repo (`builds/_18_windows_tests.kt`).
