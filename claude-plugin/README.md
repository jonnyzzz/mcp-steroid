# devrig — Claude Code plugin

Give Claude the whole JetBrains IDE: run code, debug, refactor, and inspect any
running IntelliJ-based IDE from Claude via the devrig bridge.

## Install

```
/plugin marketplace add jonnyzzz/mcp-steroid
/plugin install devrig@jonnyzzz
```

Then `/devrig:setup` (installs devrig) and restart Claude — see the workflow below.

> **Starting from the IDE instead?** If you already have the **MCP Steroid** plugin in your
> JetBrains IDE, it offers to wire Claude Code up for you (one **Enable** click installs devrig
> and enables this plugin) — you never run the two commands above by hand. The same offer also
> lives in the IDE's status bar (**devrig: not connected** → click), so it cannot be lost by
> dismissing a balloon. Both routes end in the same place; see "Connect Your AI Agent" in the
> [repository README](../README.md), and [`ij-plugin/README.md`](../ij-plugin/README.md#what-the-user-sees)
> for what the IDE side shows.

## How it works (user workflow)

The plugin is **pure scripts — no bundled native binary**. The `devrig` MCP server
**registers automatically** (bundled `.mcp.json`, launcher `bin/devrig-mcp`), but the
launcher only `exec`s an already-installed `devrig` binary at `~/.mcp-steroid/bin/devrig`;
it has no fallback.

1. **If devrig is already installed**, the MCP server starts immediately with the full
   IDE toolset — nothing else to do.
2. **If devrig is not installed yet**, the launcher exec fails and the `devrig` tools are
   unavailable. The `SessionStart` hook (`bin/check-devrig`) notices and tells the user to
   run **`/devrig:setup`**.
3. **`/devrig:setup`** runs the canonical installer (`curl -fsSL
   https://mcp-steroid.jonnyzzz.com/install.sh | sh`, or the PowerShell equivalent on
   Windows) via `bin/install-devrig(.ps1)`. This is a normal foreground download — it
   finishes (or fails) before the command returns; there is no background download, no
   progress bar, and no bootstrap process.
4. Once installed, **restart Claude** so the `devrig` MCP server can start (the launcher
   only checks for the binary at startup). `/devrig:status` verifies the install and
   registration afterward.
5. Run **`/devrig:help`** anytime for copy-paste example prompts showing what the
   whole-IDE bridge can do — Claude also shows a one-time welcome with a few examples on
   the first session after devrig goes live.

## Messages you'll see

Every user-facing string the plugin can show, by trigger. Claude prefixes hook messages
with its own label (e.g. `SessionStart:startup says:`) — that prefix is not ours to change.

**At session start** — `SessionStart` hook (`bin/check-devrig`); fires on a new session / restart / resume,
not on `/reload-plugins`:

| State | Message |
|---|---|
| devrig just installed (first session only, once) | ✅ devrig is live — Claude can now drive your JetBrains IDE. Try “run the tests in the open IDE”, “find duplicates in this file”, or “show the compilation errors”. Run /devrig:help for more. |
| devrig already installed (welcomed before) | *(silent — a model-only charter re-asserts the whole-IDE bridge every session)* |
| not installed yet | devrig is not installed yet. Run /devrig:setup to install it and unlock full IDE tools. |
| last install attempt failed | ❌ devrig install failed. Run /devrig:setup to retry. |

The failed state is the `~/.mcp-steroid/markers/bootstrap-install.failed` marker, written with the reason by
whichever half attempted the install: `bin/install-devrig(.ps1)` here, or the IDE plugin's own "Enable"
flow. Either way the next Claude session surfaces it, and a successful install clears it.

**On each message** — `UserPromptSubmit` hook (`bin/devrig-progress`): once devrig is installed, keyword-matches
the prompt (tests, debug, refactor, PSI, grep, …) and — only on a match — injects a short **model-only**
reminder to prefer the IDE tools over shell. Silent (emits `{}`) when devrig isn't installed or the prompt
doesn't match.

**Once per machine, after devrig is installed** — `SessionStart` hook (`bin/offer-ide`): probes for a
running JetBrains IDE without the MCP Steroid plugin and offers to install it there (may pop an IDE dialog
or open a browser page); silent afterward. The once-per-machine marker
(`~/.mcp-steroid/markers/ide-offered`) is written only when devrig actually made an offer — if no IDE is
running yet, or devrig is too old to know `connect ide`, a later session tries again.

**On demand** — the read-only `/devrig:status` command reports whether devrig is installed and registered
(including when this plugin's own `.mcp.json` provides the server), and any drift.

**After a devrig tool call fails recoverably** — `PostToolUse` hook (`bin/devrig-recover`), scoped to the
devrig MCP tools (matcher `mcp__.*devrig.*`, which matches both a standalone `mcp__devrig__*` registration
and this plugin's namespaced `mcp__plugin_devrig_devrig__*` tools). It scans the tool result for a known
recoverable signature and
injects a one-line, **model-only** recovery hint (never a visible banner) so the agent takes the right next
step instead of retrying blindly. Silent on success and on errors it does not own:

| Signature in the result | Hint steers the model to |
|---|---|
| no IDE reachable / transport connect failure | open the project in IntelliJ (or start a managed backend), then retry — never fall back to grep/sed |
| wrong / ambiguous / stale `project_name` | call `steroid_list_projects`, then pass the exact `project_name` |
| indexing / dumb mode | `smartReadAction { }` + `Observation.awaitConfiguration(project)`, then retry |
| modal dialog blocking the EDT | re-run with `modal=smart_non_modal`, or `closeModalDialogs()` |

Compile/threading tips inside `steroid_execute_code` results are **not** this hook's job — the plugin's own
`ExecutionSuggestionService` already appends those; `devrig-recover` only fills the routing / IDE-state gap
it cannot reach (including transport errors that carry no plugin hint at all).

The `SessionStart` hook also attaches a longer `additionalContext` that only the model sees (never shown to
you) — the whole-IDE-bridge charter that keeps the agent driving devrig instead of drifting back to shell.

## Editing rules (read before changing files here)

- **The plugin is script-only — no bundled binaries, no Go build.** `bin/devrig-mcp(.cmd)` only `exec`s
  the already-installed `~/.mcp-steroid/bin/devrig(.cmd)`; there is no fallback and nothing to keep in
  sync with a native build. If devrig isn't installed, the exec fails and `check-devrig` nudges the user
  to `/devrig:setup`.
- **`bin/devrig-mcp.cmd` is a sh/cmd polyglot.** Keep `#!/bin/sh` on line 1, LF
  endings (enforced by `.gitattributes`), and the executable bit. Claude spawns it
  via raw `execve`, so a missing shebang or exec bit breaks it.
- **stdout is the JSON-RPC channel.** `devrig-mcp.cmd` and `install-devrig*` must
  write nothing to stdout before handing off — diagnostics go to stderr only. The
  exception is `bin/check-devrig` (SessionStart hook): its stdout **is** its data
  channel (it prints JSON), and it must stay fast and `exit 0`.
- **Don't reimplement install logic** — delegate to the canonical `install.sh` /
  `install.ps1`.
- **`/devrig:setup` is surfaced to users only on failure** (or to pre-fetch/retry on demand).
- **Always run `./gradlew :claude-plugin:check`** after edits (validations +
  strict file-set lockdown). If you add/remove a bundled file, update
  `verifyPluginFiles` in `build.gradle.kts`.

## Configuration

| Change | Where | Effect |
|---|---|---|
| MCP server command | `.mcp.json` | which launcher Claude runs |
| Installed plugin source | `../.claude-plugin/marketplace.json` (`source`) | which directory gets installed |
| Install source URL | `bin/install-devrig(.ps1)` (`INSTALL_URL`) | where `/devrig:setup` fetches devrig from |
| User-facing messages | `bin/check-devrig` (hook) | what the user sees while not-installed/failed/done |
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

> **Note:** the restart is needed because `bin/devrig-mcp` resolves
> `~/.mcp-steroid/bin/devrig` when Claude starts the MCP server — repointing that
> launcher mid-session has no effect until the next start.

Revert to the released binary anytime by re-running the website installer:

```
curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh | sh    # macOS
irm https://mcp-steroid.jonnyzzz.com/install.ps1 | iex         # Windows
```
