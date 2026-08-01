# MCP Steroid — the JetBrains IDE plugin

This module is the half of the product that runs **inside** the IDE: it hosts the MCP server and exposes
the IntelliJ Platform (PSI, inspections, refactorings, the debugger, the test runner) to an AI agent.

The other half is **devrig**, the CLI that bridges the agent to every running IDE. This plugin is also the
**migration path** onto devrig — on its settings page it offers to install one by running the canonical
installer.

- Installing the plugin and connecting an agent: [repository README → Connect Your AI Agent](../README.md#connect-your-ai-agent)
- The Claude Code plugin (the other entry point): [`claude-plugin/README.md`](../claude-plugin/README.md)
- Working on this module: [`CLAUDE.md`](CLAUDE.md)

## What the user sees

### Settings page

**Settings | Tools | Devrig — MCP Steroid** is where the plugin offers anything. One line of framing, the
live server status, then two tabs — **Devrig — recommended** and **Direct HTTP** — in the order we
recommend them. They used to be stacked sections, which read as two halves of one setup instead of a
choice.

The devrig tab shows **exactly one of two states**, because only one of them is ever actionable:

| devrig | What the tab shows |
|---|---|
| missing | "devrig is not installed yet", an **Install devrig** button, and in plain words what pressing it does: downloads about 611 MB (a pinned JDK plus devrig) into `~/.mcp-steroid`, puts `devrig` on PATH, and **registers nothing with any agent** |
| installed | the version, then the next step — one row per agent (Claude, Codex, Gemini), each showing where that agent stands and a **Register** button only where pressing one would change something |

The agent rows are the second half of that: **Register** runs `devrig install <agent>` — the same verb the
docs publish, so there is one implementation of what a canonical registration is — in a background task,
then re-reads the row. Four states, and they are not interchangeable:

| Row shows | Means | Button |
|---|---|---|
| Checking… | `devrig install <agent> --check` is running | — |
| Registered | the registration is already canonical and switched on (`--check` exit 0) | none — a button on a finished step reads as an unfinished setup |
| **Register** | devrig would change something: no entry, a stale command, duplicates, a custom name (exit 1) | runs it |
| registered, but switched off — **Enable** | the entry is canonical and disabled in the agent's own config (exit 2) | runs the same verb; only the word changes |
| no `<agent>` on your PATH | the agent's CLI is not on this machine | none — the press could only fail |
| could not read the current state | the check itself failed or timed out | offered anyway; this is not the same fact as "not registered" |

**Registered is not the same as in use.** Every agent can keep an MCP server configured and switched off,
and **none of them reports that in `mcp list`** — `claude mcp list` and even `claude mcp get <name>` show a
server disabled for the current project as `✔ Connected`. That makes it the worst state to be in: every
visible answer says "all good" while the bridge does nothing. devrig therefore reads each agent's own
config for it, and `devrig install <agent>` clears it — which is the only way to undo it, since none of the
three CLIs has an enable verb.

| Agent | Off means | File |
|---|---|---|
| Claude Code | the name is in `projects.<path>.disabledMcpServers` — **per project** | `~/.claude.json` |
| Codex | `enabled = false` in the `[mcp_servers.<name>]` table | `~/.codex/config.toml` |
| Gemini | the name is in `mcp.excluded`, or `mcp.allowed` exists without it | `~/.gemini/settings.json` |

Agents whose CLI is absent are answered from a PATH lookup alone, so opening the page starts one process
per **installed** agent, not one per agent we support. Success is quiet — the row flipping to *Registered*
is the confirmation; only a failure gets a balloon, carrying devrig's own first line.

Everything else on the tab is one row each: a one-line pitch, a **What is devrig?** link, and a collapsed
**Install or update devrig by hand** group holding the same one-liners the button runs. The state block is
the only part rebuilt while the page is open — an install finishes in the background and the page follows
it (`DEVRIG_STATE_CHANGED`), instead of going on offering to install what is already there.

This page is the offer's home on purpose. It is the one surface with room to say why installing a separate
binary is worth it, and a user who opens it is asking.

### Status bar and startup notification — off by default

Both live behind the registry key **`mcp.steroid.devrig.widget.enabled`** (default `false`). Status-bar
space and a balloon at project open are the IDE's scarcest attention budget, and taking either uninvited is
what gets a plugin flagged. We also do not yet know when a startup offer is worth the interruption — every
run is clearly wrong — so the key exists to run with it on ourselves and find out.

With the key on, the **devrig** widget appears whenever there is something to act on and removes itself
once devrig is installed and current, so it never becomes a fixture. It returns if that regresses (devrig
deleted, a newer release published); the check runs when the IDE window regains focus, which is what lets
it come back within the same session rather than only after a restart. Clicking it opens a small popup: a
title, one short line, a **Learn more** link, and one button.

**Removing it for good** is the platform's own gesture, not something the plugin reimplements: right-click
the status bar and hide the **devrig** widget, or use the status-bar widget list in
**Settings | Appearance & Behavior | Appearance**. The choice is persisted by the platform, and the
plugin's own re-checks never override it.

| State | Status bar | Popup line | Button → what it does |
|---|---|---|---|
| devrig not installed | `devrig: not installed` | It lets an AI agent run, debug and refactor in this IDE. | **Install devrig** → runs the canonical installer |
| installed devrig is behind the published release | `devrig: update available` | Installed 0.100, current 0.101. | **Update devrig** → re-runs the installer (it always fetches the current release) |
| installed and current | *(no widget — it removes itself)* | devrig 0.101 can bridge your agent to this IDE. | **Open settings** → Settings \| Tools \| MCP Steroid |

The tooltip states the same situation in one line and ends with "click for details".

The copy is deliberately minimal — the title states the situation, the button states the action, and the
line adds only what neither says. Explanations, install sizes and next steps live in the docs behind
**Learn more**; `DevrigWidgetPopupContentTest` enforces a 70-character budget so they cannot creep back in.

### Notifications

Group `jonnyzzz.mcp.steroid.onboarding`, declared **`STICKY_BALLOON`**: nothing here auto-hides.

The first two rows below only appear with `mcp.steroid.devrig.widget.enabled` on; the rest report an
install the user started, and appear whether or not the key is set.

| When | Title | Body | Actions |
|---|---|---|---|
| devrig missing (startup, key on) | Install devrig to connect an AI agent | devrig bridges Claude Code, Codex or Gemini to this IDE — so an agent can run, debug, refactor and inspect it. | **Install devrig**, **Later** |
| installed devrig is stale (startup, key on) | Update devrig | devrig 0.100 is behind 0.101. Updating keeps the IDE bridge — and the plugin it carries — current. | **Update**, **Later** |
| the install succeeded | devrig is installed | Register your agent with it to bridge this IDE — see Settings \| Tools \| Devrig — MCP Steroid. | — |
| the install failed | devrig install failed | *&lt;the installer's own reason&gt;*. See the IDE log for details. | **Retry** |

`Later` only dismisses the current balloon; there is no "don't ask again". Cancelling an install produces
**no** notification — it is a choice, not a failure, and the user already knows what they did.

**No error ever reports an action the user did not start.** Every failure balloon above follows a button
press; the startup state check logs and says nothing. Keep it that way — if any of this is ever triggered
automatically, the reporting has to change with it.

Plugin updates are a separate group (`jonnyzzz.mcp.steroid.updates`, "MCP Steroid plugin update
available").

### How the install runs

It is the published installer, fetched and run the way devrig's own updater runs it: download
`install.sh` / `install.ps1` to `~/.mcp-steroid/update/`, then execute the file. Not `curl … | sh` — that
needs `curl` present and gives no way to fall back when the shell is not on PATH. Running a file lets
Windows try absolute System32 PowerShell first, then `powershell`, then `pwsh` (`installerCommands`,
shared from `:mcp-core`).

**It also takes part in devrig's update coordination** (`~/.mcp-steroid/update`, `UpdateCoordination`).
devrig self-updates by running this same installer, so without a shared view an IDE and a devrig session
could each start their own ~611 MB download of the same build. So: yield while another process holds a
live marker (the user is told devrig is already being installed, which is not an error), announce our own
while running, and on success leave an `updated-<version>` record — which is how a running devrig learns
to tell its user to restart the session onto the new build.

### Install progress

The install is a ~611 MB download, so the installer's own output drives the IDE's progress bar instead of a
static label: the phase as text, and a real fraction from the bytes staged under
`~/.mcp-steroid/binaries` against the size the installer announces.

| Installer line | Shown as |
|---|---|
| `platform: macos-arm64` | Detected platform macos-arm64… |
| `downloading jdk (~385 MB) from …` | Downloading jdk (~385 MB)… — plus `N MB of M MB` |
| `SHA-256 verified: …` | Verifying the download… |
| `attempt 2/3 failed …` | Download attempt 2/3 failed — retrying… |
| `already installed: …` | Already downloaded — reusing it… |
| `registering devrig …` | Registering devrig… |
| `devrig binary is ready.` | devrig is installed. |
| `ERROR: …` | reported as the failure reason (and written to the marker below) |

The progress bar is cancellable and the installer really does stop — the wait polls in short slices
instead of blocking for the whole 30-minute timeout.

A failure writes the reason to `~/.mcp-steroid/markers/bootstrap-install.failed` — the same marker the
Claude plugin's wrappers use — so `/devrig:status` and the agent's SessionStart hook see an IDE-side
failure too. A successful install clears it.

## Where this lives in the code

| Concern | File |
|---|---|
| State every surface reads (computed on demand, not cached) | `onboarding/DevrigConnectionState.kt` |
| Decision + version comparison (pure) | `onboarding/OnboardingDecision.kt` |
| Settings page (the offer's home) | `settings/McpSteroidConfigurable.kt` |
| Status-bar widget + popup, and the registry key gating both surfaces | `onboarding/DevrigStatusBarWidget.kt`, `onboarding/DevrigWidgetPopupContent.kt` |
| Notifications | `onboarding/DevrigOnboardingService.kt` |
| Installer run, progress, markers | `onboarding/DevrigSetup.kt`, `onboarding/InstallerProgress.kt` |
| Per-agent registration (`devrig install <agent>`, `--check` states) | `onboarding/AgentRegistration.kt` |
| Detecting and clearing a switched-off registration (devrig side) | `:npx-kt` `devrig/AgentMcpEnablement.kt`; exit codes in `:mcp-core` `devrig/InstallCheckExitCodes.kt` |
| Shared with devrig's updater: coordination markers, installer hosts, version comparison | `:mcp-core` `devrig/UpdateCoordination.kt`, `devrig/InstallerHost.kt`, `util/text/DevrigVersion.kt` |
