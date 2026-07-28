# MCP Steroid — the JetBrains IDE plugin

This module is the half of the product that runs **inside** the IDE: it hosts the MCP server and exposes
the IntelliJ Platform (PSI, inspections, refactorings, the debugger, the test runner) to an AI agent.

The other half is **devrig**, the CLI that bridges the agent to every running IDE. This plugin is also the
**migration path** onto devrig — if it finds no bridge, it offers to install one by running the canonical
installer.

- Installing the plugin and connecting an agent: [repository README → Connect Your AI Agent](../README.md#connect-your-ai-agent)
- The Claude Code plugin (the other entry point): [`claude-plugin/README.md`](../claude-plugin/README.md)
- Working on this module: [`CLAUDE.md`](CLAUDE.md)

## What the user sees

### Status bar

The **devrig** widget is always present, so the state of the bridge can never be lost by dismissing a
balloon. Clicking it opens a small popup: a title, one short line, a **Learn more** link, and one button.

| State | Status bar | Popup line | Button → what it does |
|---|---|---|---|
| devrig missing, or the Claude plugin not enabled | `devrig: not connected` | Let Claude Code run, debug and refactor in this IDE. | **Download and connect** → runs the canonical installer, then `devrig connect claude` |
| installed devrig is behind the published release | `devrig: update available` | Installed 0.100, current 0.101. | **Update devrig** → re-runs the installer (it always fetches the current release) |
| fully wired | `devrig: connected` | Claude Code can drive this IDE through devrig 0.101. | **Open settings** → Settings \| Tools \| MCP Steroid |
| no `claude` CLI on this machine | `devrig: no agent` | Install Claude Code — devrig bridges it to this IDE. | **How to get one** → opens the docs |
| state not computed yet (first seconds) | `devrig: …` | *(falls back to the not-connected copy — the click must never be a no-op)* | **Download and connect** |

The tooltip states the same situation in one line and ends with "click for details".

The copy is deliberately minimal — the title states the situation, the button states the action, and the
line adds only what neither says. Explanations, install sizes and next steps live in the docs behind
**Learn more**; `DevrigWidgetPopupContentTest` enforces a 70-character budget so they cannot creep back in.

### Notifications

Group `jonnyzzz.mcp.steroid.onboarding`, declared **`STICKY_BALLOON`**: the offer does not auto-hide. It is
shown once per IDE run and returns on the next run until the bridge is connected. There is **no "don't ask
again"** — `Later` only dismisses the current balloon, and the status-bar widget carries the state between
offers.

| When | Title | Body | Actions |
|---|---|---|---|
| devrig missing / plugin not enabled | Connect Claude Code to this IDE | Enable devrig so Claude Code can drive this IDE — run, debug, refactor, and inspect it. | **Enable**, **Later** |
| installed devrig is stale | Update devrig | devrig 0.100 is behind 0.101. Updating keeps the IDE bridge — and the plugin it carries — current. | **Update**, **Later** |
| no `claude` CLI found | Connect an AI agent to this IDE | Install a coding agent (e.g. Claude Code), then devrig can bridge it to this IDE. | **Learn how** |
| install + connect succeeded | Claude Code connected to this IDE | Restart Claude Code (or start a new session) to drive this IDE with devrig. | — |
| the installer failed | devrig install failed | *&lt;the installer's own reason&gt;*. See the IDE log for details. | — |
| `devrig connect claude` failed | Could not connect Claude Code | `devrig connect claude` exited with code N (or timed out). See the IDE log for details. | — |
| anything else threw | devrig setup failed | Setting up devrig failed: *&lt;message&gt;*. See the IDE log for details. | — |

A fully wired IDE sees none of these. Plugin updates are a separate group
(`jonnyzzz.mcp.steroid.updates`, "MCP Steroid plugin update available").

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

A failure writes the reason to `~/.mcp-steroid/markers/bootstrap-install.failed` — the same marker the
Claude plugin's wrappers use — so `/devrig:status` and the agent's SessionStart hook see an IDE-side
failure too. A successful install clears it.

## Where this lives in the code

| Concern | File |
|---|---|
| State both surfaces read | `onboarding/DevrigConnectionState.kt` |
| Decision + version comparison (pure) | `onboarding/OnboardingDecision.kt` |
| Status-bar widget + popup | `onboarding/DevrigStatusBarWidget.kt`, `onboarding/DevrigWidgetPopupContent.kt` |
| Notifications | `onboarding/DevrigOnboardingService.kt` |
| Installer run, progress, markers | `onboarding/DevrigSetup.kt`, `onboarding/InstallerProgress.kt` |
