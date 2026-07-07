# devrig — Claude Code plugin

Give Claude the whole JetBrains IDE: run code, debug, refactor, and inspect any
running IntelliJ-based IDE from Claude via the devrig bridge.

## Install

```
/plugin marketplace add jonnyzzz/mcp-steroid
/plugin install devrig@jonnyzzz
```

## How it works (user workflow)

1. The `devrig` MCP server **registers automatically** (bundled `.mcp.json`) — no
   command to run.
2. When the plugin activates (after `/plugin install`, `/reload-plugins`, or a new
   session), a small bundled **bootstrap** server starts: `devrig` shows up
   **connected (green)** with one tool, `devrig_status`, and the real devrig
   binary (~611 MB) starts **downloading in the background**. No restart needed to
   begin.
3. **The download runs detached — you can keep working, close, or restart Claude**
   and it keeps going in the background. **Progress shows automatically** — no command
   to run: if you have no Claude status line, a `devrig 41% · 250/611 MB` bar appears
   (and disappears once devrig is live); if you already have a status line, a short
   `⏳ devrig …%` line shows on each turn instead. Your existing status line is never
   modified. You can also ask for "devrig status" anytime.
4. **If an IDE with the MCP Steroid plugin is already open, its tools activate
   within seconds — no download wait.** The bootstrap bridges to the running
   IDE's built-in MCP endpoint and fires `notifications/tools/list_changed`, so
   you get the full IDE toolset immediately while the ~611 MB download continues.
5. When the download **finishes, the full devrig toolset activates automatically on
   your next message** — no restart needed. (The bootstrap fires
   `notifications/tools/list_changed` a second time, swapping to the full backend
   for cross-backend/managed features. If no IDE was open, this is the point the
   tools first appear.)
6. `/devrig:setup` is only needed to **retry a failed download** or to fetch the
   binary immediately instead of waiting. Run **`/devrig:help`** anytime for
   copy-paste example prompts showing what the whole-IDE bridge can do — Claude
   also shows a one-time welcome with a few examples on the first session after
   devrig goes live.

The downloaded binary lands at `~/.mcp-steroid/bin/devrig`; once present, the
launcher routes to it and the bootstrap is no longer used.

## Messages you'll see

Every user-facing string the plugin can show, by trigger. The `~611 MB` figure and the `%`/MB numbers
come from the single `approxInstallMB` constant (`../devrig-bootstrap/progress.go`). Claude prefixes hook
messages with its own label (e.g. `SessionStart:startup says:`) — that prefix is not ours to change.

**At session start** — `SessionStart` hook (`bin/check-devrig`); fires on a new session / restart / resume,
not on `/reload-plugins`:

| State | Message |
|---|---|
| devrig just installed (first session only, once) | ✅ devrig is live — Claude can now drive your JetBrains IDE. Try “run the tests in the open IDE”, “find duplicates in this file”, or “show the compilation errors”. Run /devrig:help for more. |
| devrig already installed (welcomed before) | *(silent)* |
| downloading, an IDE with the MCP Steroid plugin is open | ⚡ IDE tools ready now — full devrig toolset (~611 MB) still downloading in the background, activates automatically when done. |
| downloading, no IDE open | ⏳ devrig downloading (~611 MB) in the background — activates automatically when done, no restart needed. |
| last install failed | ❌ devrig install failed. Run /devrig:setup to retry. |

**On each message while downloading** — `UserPromptSubmit` hook (`bin/devrig-progress`):

| Mode | Message |
|---|---|
| hook mode (you already have a status line) | ⏳ devrig 41% · 250/611 MB |
| bar mode (you have no status line) | *(silent — the status-line bar shows it instead)* |

**Always-visible status-line bar** — bar mode only (`bootstrap --statusline`), refreshed ~every 2s:

| State | Bar |
|---|---|
| downloading | `devrig 41% · 250/611 MB` (yellow) |
| just finished | `devrig ✓` (green, briefly, then removed) |
| failed | `devrig ⚠ /devrig:setup` (red) |

**On demand** — the `devrig_status` tool (ask "devrig status" or run `/devrig:status`):

| State | Message |
|---|---|
| installed | ✅ devrig active — full IDE toolset available. |
| installing | ⏳ devrig 210/611 MB (12s) — downloading in the background, activates automatically when done. |
| installing, IDE open | *(same, plus)* … IDE tools available now. |
| failed | ❌ devrig install failed: `<reason>`. Run /devrig:setup to retry. |
| starting (nothing downloaded yet) | ⏳ devrig starting — activates automatically when ready. |

The `SessionStart` and `UserPromptSubmit` hooks also attach a longer `additionalContext` that only the
model sees (never shown to you) — guidance such as "the download is detached, don't tell the user to run
manual commands."

## Editing rules (read before changing files here)

- **Bootstrap binaries are committed to `bin/bootstrap-*`.** After any change to
  `../devrig-bootstrap/*.go`, run `./gradlew :claude-plugin:updateBundledBinaries`
  and commit them. `:claude-plugin:check` fails if they're stale. See
  [../devrig-bootstrap/README.md](../devrig-bootstrap/README.md).
- **`bin/devrig-mcp.cmd` is a sh/cmd polyglot.** Keep `#!/bin/sh` on line 1, LF
  endings (enforced by `.gitattributes`), and the executable bit. Claude spawns it
  via raw `execve`, so a missing shebang or exec bit breaks it.
- **stdout is the JSON-RPC channel.** `devrig-mcp.cmd` and `install-devrig*` must
  write nothing to stdout before handing off — diagnostics go to stderr only. The
  exception is `bin/check-devrig` (SessionStart hook): its stdout **is** its data
  channel (it prints JSON), and it must stay fast and `exit 0`.
- **Don't reimplement install logic** — delegate to the canonical `install.sh` /
  `install.ps1`.
- **`/devrig:setup` is surfaced to users only on failure** (not while downloading).
- **Always run `./gradlew :claude-plugin:check`** after edits (validations +
  strict file-set lockdown). If you add/remove a bundled file, update
  `verifyPluginFiles` in `build.gradle.kts`.

## Configuration

| Change | Where | Effect |
|---|---|---|
| MCP server command | `.mcp.json` | which launcher Claude runs |
| Installed plugin source | `../.claude-plugin/marketplace.json` (`source`) | which directory gets installed |
| Download URLs | `../devrig-bootstrap/install.go` | where devrig is fetched from |
| OS/arch targets | `../devrig-bootstrap/build.gradle.kts` (`targets`) | which platforms get a bootstrap binary |
| Progress total ("~N MB") | `../devrig-bootstrap/progress.go` (`approxInstallMB`) | the number shown in `devrig_status` |
| Wedge reclaim timing | `../devrig-bootstrap/status.go` (`heartbeatInterval`, `installLockStaleAfter`) | how fast a dead/interrupted download is retried |
| User-facing messages | `bin/check-devrig` (hook) + `../devrig-bootstrap/status.go` | what the user sees while installing/failed/done |
| First-run welcome + example prompts | `bin/check-devrig` (installed branch) + `commands/help.md` | the one-time welcome (gated by `~/.mcp-steroid/markers/welcomed`) and the `/devrig:help` command |

## Run a locally built devrig (your code changes, not the release)

After editing the repo, build devrig and point Claude's stable launcher at your
build. Claude's MCP registration always targets `~/.mcp-steroid/bin/devrig(.cmd)`,
so you never re-register — `install devrig` just repoints that wrapper.

Run from the repo root (needs JDK 25):

```
./gradlew :npx-kt:installDist
```

Then repoint the launcher at the freshly built binary:

**macOS**
```
./npx-kt/build/install/devrig/bin/devrig install devrig
```

**Windows**
```
npx-kt\build\install\devrig\bin\devrig.bat install devrig
```

**Restart Claude.** `/devrig:status` now runs your local build. Repeat both steps
after each code change.

> **Note:** this "Restart Claude" step is a separate developer flow — you are
> repointing the launcher at a local build. It is distinct from the automatic
> no-restart activation that happens when the first background download finishes.

Revert to the released binary anytime by re-running the website installer:

```
curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh    # macOS
irm https://mcp-steroid.jonnyzzz.com/install.ps1 | iex         # Windows
```
