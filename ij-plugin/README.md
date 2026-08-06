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

**Settings | Tools | Devrig — MCP Steroid** is where the plugin offers anything. No pitch at the top — the
reader already installed the plugin, so selling it back to them spends the one screen they came to for state
and a button; that copy lives in the Marketplace description. Two blocks, then a **Report an issue on
GitHub** link last, because it is never the next step:

| Block | Holds, top to bottom |
|---|---|
| **Devrig** | why a separate binary is worth it and a **What is devrig?** link; then the one state-dependent block — install devrig, or, once it is there, point an agent at it |
| **Direct HTTP connection (deprecated)** | collapsed, last, and deprecated in its own title and first line: the in-IDE server's state (running, and on which port — a fact only someone wiring HTTP by hand needs), the server URL, the per-agent `mcp add` commands, the generic `mcpServers` JSON, the registry keys |

Each value is a **read-only field**, not a bare label: a bare value ran together with the label naming it
and left nothing to mark which half was the answer.

The devrig block shows **exactly one of two states**, because only one of them is ever actionable:

| devrig | What the block shows |
|---|---|
| missing | "devrig is not installed yet", an **Install devrig** button, and in plain words what pressing it does: downloads about 611 MB (a pinned JDK plus devrig) into `~/.mcp-steroid`, puts `devrig` on PATH, and **registers nothing with any agent** |
| installed | "Installed", then the next step — one long read-only **copyable command per agent** (Claude, Codex, Gemini): the absolute stable launcher plus devrig's canonical `install <agent>` verb, for the user to run in a terminal |

The agent rows are **display-only** (owner direction, 2026-08-06). An earlier revision checked each
agent's registration state and ran the registration from the page — per-row `--check` probes,
Register/Enable buttons, exit-code mapping, failure notifications with Retry — and owner click-testing
found that flow broken in practice. A printed command cannot break the same way: `devrig install <agent>`
is idempotent (issue #399 — re-running repairs and consolidates, never duplicates), the terminal shows
what it did, and the IDE neither checks an agent's state nor registers anything itself. Each command is
built in `:devrig-common` (`devrigInstallAgentCommandLine`) from the same launcher-path policy devrig
registers with, so the display cannot drift from reality; the per-OS forms — `.cmd` shim, backslashes,
real absolute home (never `~`), PowerShell call-operator quoting when the profile path holds a space —
are pinned in `DevrigUserLauncherTest`.

**Copying says so.** Every copy control — both **Copy JSON** buttons and the in-field copy icons — shows a
short *Copied* balloon above itself. Copying is the only action here with no visible consequence, and a
JetBrains button cannot answer for itself: `DarculaButtonUI` paints **no pressed state at all** (the theme
defines no pressed colour, and `JBUI.CurrentTheme.Button`'s palette takes no state parameter), so a press
looks exactly like a hover. That is platform-wide behaviour, identical for a plain `JButton` — verified by
rendering both pressed and unpressed: zero differing pixels. Nothing in the Kotlin UI DSL changes it; the
DSL only creates the `JButton`. The receipt is ours to give, so we give it.

**Any other MCP client** gets the same server two ways, right after the agent rows: a copyable
`<launcher> mcp` command line for any client with an "add MCP server" dialog, and — collapsed, in
**Another MCP client (Cursor, Windsurf, …)** — the stdio `mcpServers` JSON snippet for clients configured
through a file. It is the settings-page twin of `devrig install config`, and both build the command
through `DevrigUserLauncher.invocation` in `:devrig-common`, so what you copy cannot drift from what
`devrig install <agent>` writes. Shown only once devrig is installed — before that, the snippet would name
a launcher that is not there.

That devrig block is the only part rebuilt while the page is open. It populates on show, off the EDT —
the one fact read is the `devrigInstalled()` file probe, on `Dispatchers.IO` — and the install button
awaits the background install to stop offering one that just succeeded. The completion of a button the
user pressed, not a monitoring pipeline; there is no cached state, so every show computes reality afresh.

This page is the offer's home on purpose. It is the one surface with room to say why installing a separate
binary is worth it, and a user who opens it is asking.

### Startup promotion — off by default

Behind the registry key **`mcp.steroid.devrig.widget.enabled`** (default `false`; the id predates the
since-deleted status-bar widget and stays stable so machines that flipped it keep their choice). A balloon
at startup is the IDE's scarcest attention budget, and taking it uninvited is what gets a plugin flagged;
the settings page carries the same offer with room to explain it.

With the key on, `DevrigPromotion` runs **at most once per IDE run**: started from an explicit platform
callback (never a constructor side effect), it waits out a random 12–35 s delay (past the noisy
project-open moment), probes for devrig off the EDT, and — only when devrig is missing — shows one
non-sticky balloon whose single action is the existing install flow. If it auto-hides unseen, nothing is
lost: the offer lives on the settings page, and the message stays in the Notifications tool window. There
is nothing to snooze and nothing to monitor.

### Notifications

One group (`jonnyzzz.mcp.steroid.updates`) and one owner: every balloon the plugin shows goes through
`McpSteroidNotifications.notify`, which keeps **at most one live notification per kind** — a retry's
failure supersedes the original instead of stacking next to it. Plain `BALLOON`, never sticky (owner
call): a balloon is a nudge, and anything missed stays reachable in the Notifications tool window.

| Kind | When | Actions |
|---|---|---|
| `DEVRIG_INSTALL_OFFER` | the once-per-run promotion (key on, devrig missing) | **Install devrig** |
| `DEVRIG_INSTALL` | the outcome of an install the user started: installed, already being installed by another process, or failed | **Open settings**; **Retry** on failure, carrying the installer's own reason |
| `PLUGIN_UPDATE` | the periodic plugin-update check | **Download** (the releases page) |

Cancelling an install produces **no** notification — it is a choice, not a failure, and the user already
knows what they did.

**No error ever reports an action the user did not start.** Every failure balloon follows a button press;
the startup probe logs and says nothing. Keep it that way — if any of this is ever triggered
automatically, the reporting has to change with it. And every message carries at least one action: a
notification that only points somewhere else ("see the IDE log") reports a problem and hands the user
homework, which is worse than saying nothing.

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

**The fraction depends on the installer that is published**, not on the template in this repository: the
button downloads the live `https://devrig.dev/install.sh`. The size and retry lines above arrive with
[#363](https://github.com/jonnyzzz/mcp-steroid/pull/363) and reach users only once a release republishes the
website; until then the published script prints `downloading <kind> (<url>)...`, those two rows never match,
and the bar stays indeterminate while still naming each step from the other lines. Expected degradation, not
a defect.

The progress bar is cancellable and the installer really does stop — the wait polls in short slices
instead of blocking for the whole 30-minute timeout.

A failure writes the reason to `~/.mcp-steroid/markers/bootstrap-install.failed` — the same marker the
Claude plugin's wrappers use — so `/devrig:status` and the agent's SessionStart hook see an IDE-side
failure too. A successful install clears it.

## Where this lives in the code

| Concern | File |
|---|---|
| Settings page (the offer's home; display-only agent commands) | `settings/McpSteroidConfigurable.kt` |
| Is devrig installed? (the one probe, a file check) | `onboarding/DevrigInstallProbe.kt` |
| Installer run, progress, markers | `onboarding/DevrigSetup.kt`, `onboarding/InstallerProgress.kt` |
| Once-per-run startup promotion and its registry key | `onboarding/DevrigPromotion.kt` |
| Notifications (single group, one-per-kind policy) | `notifications/McpSteroidNotifications.kt` |
| The agent registration commands the page displays (launcher path policy, per-OS quoting) | `:devrig-common` `devrig/DevrigUserLauncher.kt` |
| The registration itself, its `--check` doctor and exit codes, switched-off detection (devrig side) | `:npx-kt` `devrig/InstallCommand.kt`, `devrig/InstallCheckExitCodes.kt`, `devrig/AgentMcpEnablement.kt` |
| Shared with devrig's updater: coordination markers, installer download, version comparison | `:devrig-common` `devrig/UpdateCoordination.kt`, `devrig/InstallerHost.kt`; `:mcp-core` `util/text/DevrigVersion.kt` |
