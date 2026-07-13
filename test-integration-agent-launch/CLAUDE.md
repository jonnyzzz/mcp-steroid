# test-integration-agent-launch — Agent Guide

**Cross-OS** behaviour tests (Windows **and** Linux) for the *script-only, minimal-dependency* way an
agent plugin launches things — its stdio **MCP server** and its **hooks** — validating how Claude Code
resolves each candidate script form per OS. Uses **mock probe scripts** (no real devrig). Live
counterpart to jonnyzzz/mcp-steroid#253.

| Test | Verifies |
|---|---|
| `ClaudeAgentLaunchTest` | Downloads the host-OS Claude build, loads a mock probe plugin via `--plugin-dir`, and asserts the **per-OS** launch outcome of the MCP `command` + **every candidate hook form** (exec ×3 + shell ×3). |
| `InstallerScriptTest` | The generated installer for the host OS is syntactically valid: `install.ps1` parses under Windows PowerShell 5.1; `install.sh` parses under `sh -n`. (#254) |

## OS gating (no runtime skips)

The suite is structurally tied to a real Windows/Linux agent (it downloads + runs the OS Claude build).
Per the root `CLAUDE.md` rule, it is gated at the **task level**: `build.gradle.kts` →
`tasks.test { enabled = isWindows || isLinux }`. On macOS the task is skipped; `compileTestKotlin` still
runs. Tests that DO run use **OS-conditional assertions** (assert the per-OS-correct outcome) — that is
not a skip.

## Confirmed matrix (cross-validated on TeamCity + GitHub, Windows + Linux)

Probe log: `LAUNCHED tag=<tag> via=<cmd|sh|shell|script>`.

**MCP stdio (script-only, zero deps):** extensionless `${CLAUDE_PLUGIN_ROOT}/bin/<stem>` resolves to
`<stem>.cmd` via cross-spawn/PATHEXT on **Windows** (`via=cmd`) and to the `#!/bin/sh` `<stem>` on
**Unix** (`via=sh`); a **bare** name resolves on neither (plugin `bin/` isn't on the subprocess PATH).

**Hooks:**

| Hook form | Windows | Linux |
|---|:-:|:-:|
| exec-form, extensionless path | ❌ | ✅ (`+x` shebang script) |
| exec-form, `.cmd` path | ❌ | ❌ |
| exec-form, `cmd /c <.cmd>` | ✅ | ❌ (no `cmd`) |
| shell-form, default shell | ✅ (Git Bash) | ✅ (`sh`) |
| shell-form, `"shell":"powershell"` | ✅ | pwsh-dependent (recorded) |
| **shell-form → `#!/bin/sh` script (devrig pattern)** | ✅ (Git Bash) | ✅ |

**Recommended (cross-platform, minimal deps):** shell-form pointing at a `#!/bin/sh` script — runs
natively on Linux/macOS and via **Git Bash** on Windows; exactly how devrig ships its hooks. The single
dependency is **Git Bash on Windows** for `sh` hooks; MCP has none.

## Running

```bash
# On Windows or Linux (real behaviour):
./gradlew :test-integration-agent-launch:test
./gradlew ciAgentLaunchTests        # CI aggregator (TeamCity per-OS builds + GH win/ubuntu matrix)

# On macOS: skipped. Verify it still compiles anywhere:
./gradlew :test-integration-agent-launch:compileTestKotlin
```

No API key needed — Claude spawns plugin MCP servers + hooks during session init, before the `-p` turn
fails auth. TeamCity wiring: `~/Work/mcp-steroid-teamcity` → `builds/_18_agent_launch_tests.kt`.
