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
   binary (~500 MB) starts **downloading in the background**. No restart needed to
   begin.
3. **The download runs detached — you can keep working, close, or restart Claude**
   and it keeps going in the background. **Progress shows automatically** — no command
   to run: if you have no Claude status line, a `devrig 41% · 210/500 MB` bar appears
   (and disappears once devrig is live); if you already have a status line, a short
   `⏳ devrig …%` line shows on each turn instead. Your existing status line is never
   modified. You can also ask for "devrig status" anytime.
4. **If an IDE with the MCP Steroid plugin is already open, its tools activate
   within seconds — no download wait.** The bootstrap bridges to the running
   IDE's built-in MCP endpoint and fires `notifications/tools/list_changed`, so
   you get the full IDE toolset immediately while the ~500 MB download continues.
5. When the download **finishes, the full devrig toolset activates automatically on
   your next message** — no restart needed. (The bootstrap fires
   `notifications/tools/list_changed` a second time, swapping to the full backend
   for cross-backend/managed features. If no IDE was open, this is the point the
   tools first appear.)
6. `/devrig:setup` is only needed to **retry a failed download** or to fetch the
   binary immediately instead of waiting.

The downloaded binary lands at `~/.mcp-steroid/bin/devrig`; once present, the
launcher routes to it and the bootstrap is no longer used.

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
