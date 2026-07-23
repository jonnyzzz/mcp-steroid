[![official JetBrains project](http://jb.gg/badges/incubator-flat-square.svg)](https://github.com/JetBrains#jetbrains-on-github)

# MCP Steroid

<p align="center">
  <img src="website/static/pluginIcon.svg" alt="MCP Steroid Logo" width="120" height="120">
</p>

<p align="center">
  <strong>Connect your AI coding agent to a real JetBrains IDE</strong><br>
  <em>Install <code>devrig</code>, and your agent works through the whole IntelliJ — not just the files</em>
</p>

<p align="center">
  <a href="https://github.com/jonnyzzz/mcp-steroid/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://plugins.jetbrains.com/plugin/30019-mcp-steroid"><img src="https://img.shields.io/badge/JetBrains-Marketplace-orange.svg" alt="JetBrains Marketplace"></a>
  <a href="https://discord.gg/e9qgQ7NeTC"><img src="https://img.shields.io/badge/Discord-Community-5865F2.svg" alt="Discord"></a>
</p>

<p align="center">
  <a href="https://devrig.dev">Website</a> &bull;
  <a href="https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f">Demo Videos</a> &bull;
  <a href="https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/">Blog Post</a> &bull;
  <a href="https://discord.gg/e9qgQ7NeTC">Discord</a>
</p>

---

## What is devrig?

**[`devrig`](https://devrig.dev/docs/devrig/)** is the product you install: a small command-line
tool that connects your AI coding agent (Claude Code, Codex, or Gemini) to a real JetBrains IDE.
It brings **its own runtime**, registers itself with your agent, and bridges the agent's calls to
the IDE — no manual MCP wiring.

devrig reaches the IDE through **MCP Steroid**, a JetBrains IDE plugin (this repo) that exposes the
IDE's real semantic actions — typed refactorings, inspections, the debugger, and test runs — over
the open [Model Context Protocol](https://modelcontextprotocol.io/). You install devrig; devrig
uses MCP Steroid.

Unlike file-only assistants, this gives AI agents the same capabilities developers use: semantic
code understanding, advanced refactorings, debugging, test running, visual awareness, and the
entire IntelliJ API surface — all inside the running IDE's JVM.

### One bridge, every IDE

A single `devrig` process connects your AI Agent to **all** the IntelliJ-family IDEs running on
your machine at once — each open on a different project — and can download and start more on demand.

<p align="center">
  <img src="website/static/devrig-bridge.svg" alt="One devrig bridge connects your AI Agent to all running IDEs at once — and can start more" width="720">
</p>

### What your agent gets

- **Visual IDE understanding** — screenshots + OCR + component tree
- **UI automation** — control the IDE like a human developer
- **Native IntelliJ APIs** — PSI, inspections, refactorings, and more
- **Kotlin scripting** — full platform access at runtime via `steroid_execute_code`
- **Standard MCP protocol** — connects to MCP-compatible AI agents

We continuously measure IDE-access vs plain-shell agents on real codebases. See the
[experiment findings](https://devrig.dev/docs/experiment-findings/) for the evidence-based results.

---

## Install

### 1. Install devrig — one command

**macOS / Linux**

```bash
curl -fsSL https://devrig.dev/install.sh | sh
```

**Windows**

```powershell
irm https://devrig.dev/install.ps1 | iex
```

The script does exactly two things: it installs the `devrig` CLI with its own bundled runtime into `~/.mcp-steroid`, and it registers the stable `devrig` launcher on your `PATH` (if `devrig` is not found afterwards, open a new terminal or add `~/.mcp-steroid/bin` to `PATH`). It never touches your agent configs or your IDEs — it finishes by printing the explicit next-step commands (steps 2 and 3 below). Installation is idempotent; re-run it any time to update.

### 2. Register your AI agent

```bash
devrig install claude
devrig install codex
devrig install gemini
```

`devrig install <agent>` registers devrig as the `mcp-steroid` MCP server in Claude Code, Codex, or Gemini (one of `claude`, `codex`, `gemini`). The entry lands in the user-scope config, so it is visible from every project. For any other MCP client, `devrig install config` prints the manual `mcp.json` snippet to paste. See the [devrig CLI guide](https://devrig.dev/docs/devrig/) for the full command set.

### 3. Install the MCP Steroid plugin

```bash
devrig install plugin
```

`devrig install plugin` installs (or updates) the MCP Steroid plugin into every JetBrains IDE currently running on your machine — each IDE asks for your confirmation with its own native install dialog, so nothing is installed silently. Alternatively, install **MCP Steroid** from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30019-mcp-steroid) (search **MCP Steroid** in **Settings > Plugins > Marketplace**).

**Requirements**

- A JetBrains IDE — IntelliJ IDEA, PyCharm, GoLand, WebStorm, Rider, CLion, or Android Studio.
- The IDE runs with a real display: the normal GUI on macOS/Windows, or under **Xvfb** (a virtual X display) on Linux/CI. True headless launches (`-Djava.awt.headless=true`) are unsupported (best-effort, see [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177)) — see [Running devrig in CI](https://devrig.dev/docs/running-on-ci/).
- An MCP-compatible AI agent (Claude Code, Codex, or Gemini).

**Faster plugin updates (optional):** add `https://devrig.dev/updatePlugins.xml` in **Settings > Plugins > Gear icon > Manage Plugin Repositories...**. Or install a ZIP from [GitHub Releases](https://github.com/jonnyzzz/mcp-steroid/releases) via **Install Plugin from Disk**.

### Verify the connection

When the plugin starts, it writes the connection details to `.idea/mcp-steroid.md` in each open project. Ask your agent to list the open projects:

```bash
claude -p "List all open projects using steroid_list_projects"
codex exec "List all open projects using steroid_list_projects"
gemini "List all open projects using steroid_list_projects"
```

If you see your open IntelliJ projects, the connection works. The plugin also serves the raw server URL at `http://127.0.0.1:6315/mcp` (Streamable HTTP transport) for clients that prefer to talk to the IDE directly.

### Local development loop (deploy both halves from a checkout)

Working on MCP Steroid itself? Two Gradle tasks push your checkout into the live environment — no IDE restarts, no reinstalling.

**One-time per IDE**: install the [Plugin Hot Reload](https://github.com/jonnyzzz/intellij-plugin-hot-reload) plugin into every IDE you deploy to — download the ZIP from its Releases page, then `Settings | Plugins | ⚙ | Install Plugin from Disk…`, restart once. It exposes the local hot-reload endpoint (a `~/.<pid>.hot-reload` marker per running IDE) that `deployPlugin` talks to.

```bash
# 1. devrig (the CLI): build, stage under ~/.mcp-steroid/devrig/, and regenerate the
#    ~/.mcp-steroid/bin/devrig launcher via `devrig install devrig`.
./gradlew deployDevrig

# assert: the launcher runs YOUR build — a dev version stamped with your checkout's git hash
~/.mcp-steroid/bin/devrig version
# → <base>.19999-SNAPSHOT-<git hash of your HEAD>

# 2. the IDE plugin: build the plugin ZIP and hot-reload it into every running IDE.
./gradlew :ij-plugin:deployPlugin

# assert: the task output ends with SUCCESS per IDE —
#   Installing and loading plugin: MCP Steroid (<base>.19999-SNAPSHOT-<git hash>)
#   Plugin MCP Steroid reloaded successfully
```

Both tasks fail loudly instead of half-deploying: `deployDevrig` fails when `devrig install devrig` cannot write the launcher (e.g. a `DEVRIG_BIN_NO_AUTO_REGISTER` opt-out), and `deployPlugin` fails with `No running IDEs found` when no IDE with the hot-reload plugin is up, or on anything but `SUCCESS` from an IDE.

---

## Compatible AI Agents

`devrig install` registers MCP Steroid directly with:

- **Claude** (Claude Code)
- **Codex** CLI
- **Gemini** CLI

MCP Steroid speaks the standard Model Context Protocol, so other MCP-capable clients can also connect to the plugin's server directly — see [How it works](https://devrig.dev/docs/how-it-works/).

---

## Capabilities

### Design philosophy in one breath

The MCP tool surface is **intentionally small** — power lives in
`mcp-steroid://` prompt resources that teach agents to call IntelliJ's
APIs directly inside `steroid_execute_code`. New tools and new
`McpScriptContext` methods are not the lever for "agents deliver more";
better recipes are. The full canonical statement lives in
[`docs/PHILOSOPHY.md`](docs/PHILOSOPHY.md) and is mirrored at runtime
as `mcp-steroid://skill/design-philosophy`.

### 8 MCP Tools

| Tool | Description |
|------|-------------|
| **Execute Code** (`steroid_execute_code`) | Run Kotlin code inside the IDE's JVM with full API access |
| **Execute Feedback** (`steroid_execute_feedback`) | Provide execution ratings back to agents |
| **Fetch Resource** (`steroid_fetch_resource`) | Fetch any `mcp-steroid://` skill guide / recipe by URI |
| **Vision Screenshot** (`steroid_take_screenshot`) | Capture IDE screenshots with component metadata |
| **Vision Input** (`steroid_input`) | Send keyboard/mouse events to the IDE via a sequence-string DSL |
| **List Projects** (`steroid_list_projects`) | Discover all open IntelliJ projects |
| **List Windows** (`steroid_list_windows`) | Enumerate IDE windows and components |
| **Open Project** (`steroid_open_project`) | Open projects programmatically |

### 58 MCP Resources

Comprehensive guides and examples covering:

- **LSP Operations** (11) — Go to definition, find references, hover, completion
- **IDE Power Operations** (22) — Refactorings, code generation, project analysis
- **Debugger Integration** (7) — Breakpoints, thread control, debugging workflows
- **Test Runner** (10) — Run tests, inspect results, navigate test trees
- **VCS Operations** (3) — Git annotations, file history
- **Project Workflows** (4) — Open projects with trust levels
- **Skill Guides** (3) — IntelliJ API, debugger, and test runner guides

---

## Featured Demo Videos

| Video | Description | Duration |
|-------|-------------|----------|
| [Codex Debugs in IntelliJ IDEA](https://www.youtube.com/watch?v=HtDDNyAoLak) | Full debugging session with Codex | 1:03:24 |
| [CodeDozer Demo 5](https://www.youtube.com/watch?v=6ByedA15n8Q) | Most popular demo | 1:00 |
| [CodeDozer & IntelliJ Debugger](https://www.youtube.com/watch?v=8MjogrpfXLU) | Debugger integration showcase | 8:25 |
| [Now we call tasks in IntelliJ](https://www.youtube.com/watch?v=JGcRk7Y3-Z8) | Task execution demo | 2:21 |
| [Real Work in Monorepo Part 2](https://www.youtube.com/watch?v=ibc0saTT06M) | Deep dive into real workflow | 18:37 |
| [Cursor Talks with IntelliJ](https://www.youtube.com/watch?v=QIl57FrAJtk) | Cursor integration | 0:44 |

Watch all demos: [MCP Steroid Playlist](https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f)

---

## Configuration

MCP Steroid can be configured via IntelliJ's Registry (`Help > Find Action > Registry`) or JVM system properties.

| Registry Key | Default | Description |
|--------------|---------|-------------|
| `mcp.steroid.server.port` | 6315 | MCP server port (0 for auto-assign) |
| `mcp.steroid.server.host` | 127.0.0.1 | Bind address (use 0.0.0.0 for Docker) |
| `mcp.steroid.storage.path` | (empty) | Custom storage path (default: `~/.mcp-steroid/runs/`) |

See the full [Configuration Documentation](https://devrig.dev/docs/configuration/) on the website.

---

## Architecture

- **Technology:** Kotlin on the JVM, running inside the IDE process
- **HTTP Server:** Ktor 3.3.2 (Streamable HTTP + SSE)
- **Protocol:** Model Context Protocol (MCP)
- **Default Port:** 6315
- **OCR:** Tesseract 5.5.1
- **Platform:** IntelliJ Platform Plugin SDK

The server runs **inside the IDE's JVM process** — no inter-process communication. Direct access to the project model, semantic index, PSI tree, test runner, debugger, and VCS layer.

---

## About the Project

**MCP Steroid** is an open-source project by Eugene Petrenko ([@jonnyzzz](https://linkedin.com/in/jonnyzzz)), licensed under [Apache 2.0](LICENSE).

Read more:
- [MCP Steroid Is Now Open Source](https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/) — announcement and project history
- [IntelliJ as a Skill Factory](https://jonnyzzz.com/blog/2026/04/08/mcp-steroid-skill-factory/) — build custom agent skills without plugin development
- [Project Assessment: 75 Days, 1300+ Commits](https://jonnyzzz.com/blog/2026/02/23/mcp-steroid-project-assessment/) — architecture and quality deep dive

*IntelliJ IDEA, IntelliJ Platform, PyCharm, WebStorm, and JetBrains are trademarks of JetBrains s.r.o.*

---

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on
how to get started, and [CONTRIBUTORS.md](CONTRIBUTORS.md) for the list of people
who have helped make MCP Steroid better.

---

## License

MCP Steroid is open-source software licensed under the [Apache License 2.0](LICENSE).

---

## Links

- **Website:** [devrig.dev](https://devrig.dev)
- **JetBrains Marketplace:** [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/30019-mcp-steroid)
- **Discord:** [discord.gg/e9qgQ7NeTC](https://discord.gg/e9qgQ7NeTC)
- **GitHub Issues:** [github.com/jonnyzzz/mcp-steroid/issues](https://github.com/jonnyzzz/mcp-steroid/issues)
- **GitHub Sponsors:** [github.com/sponsors/jonnyzzz](https://github.com/sponsors/jonnyzzz)
- **Blog:** [jonnyzzz.com](https://jonnyzzz.com)
- **YouTube:** [@jonnyzzz](https://youtube.com/@jonnyzzz)
- **LinkedIn:** [jonnyzzz](https://linkedin.com/in/jonnyzzz)
- **X/Twitter:** [@jonnyzzz](https://x.com/jonnyzzz)

---

<p align="center">
  <sub>Built with care for the AI agent developer community</sub>
</p>
