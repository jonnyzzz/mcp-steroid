[![official JetBrains project](http://jb.gg/badges/incubator-flat-square.svg)](https://github.com/JetBrains#jetbrains-on-github)

# MCP Steroid

<p align="center">
  <img src="website/static/pluginIcon.svg" alt="MCP Steroid Logo" width="120" height="120">
</p>

<p align="center">
  <strong>Give AI the whole IntelliJ, not just the files</strong><br>
  <em>AI agents can finally SEE your IDE — not just read code</em>
</p>

<p align="center">
  <a href="https://github.com/jonnyzzz/mcp-steroid/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="https://plugins.jetbrains.com/plugin/30019-mcp-steroid"><img src="https://img.shields.io/badge/JetBrains-Marketplace-orange.svg" alt="JetBrains Marketplace"></a>
  <a href="https://discord.gg/e9qgQ7NeTC"><img src="https://img.shields.io/badge/Discord-Community-5865F2.svg" alt="Discord"></a>
</p>

<p align="center">
  <a href="https://mcp-steroid.jonnyzzz.com">Website</a> &bull;
  <a href="https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f">Demo Videos</a> &bull;
  <a href="https://jonnyzzz.com/blog/2026/04/07/mcp-steroid-open-source/">Blog Post</a> &bull;
  <a href="https://discord.gg/e9qgQ7NeTC">Discord</a>
</p>

---

## About MCP Steroid

**MCP Steroid** is an open-source [Model Context Protocol](https://modelcontextprotocol.io/) server that runs inside JetBrains IDEs and exposes the full power of the IntelliJ Platform to AI agents.

Unlike file-only assistants, MCP Steroid gives AI agents the same capabilities developers use: semantic code understanding, advanced refactorings, debugging, test running, visual awareness, and the entire IntelliJ API surface.

There are **two ways to run it**. Start with the new **[`devrig`](https://mcp-steroid.jonnyzzz.com/docs/devrig/) CLI** (preview): a standalone tool that bridges any MCP-capable agent into a real IntelliJ-family IDE over stdio — through one bridge it connects to **every IDE you already have open at once** (across projects), or **downloads, installs, and starts one for the agent** (great for headless, CI, and fresh machines). Or run the **JetBrains IDE plugin** in-IDE over HTTP. The project is gradually moving toward the **Devrig** name.

### One bridge, every IDE

A single `devrig` process connects your AI Agent to **all** the IntelliJ-family IDEs running on your machine at once — each open on a different project — and can download and start more on demand.

<p align="center">
  <img src="website/static/devrig-bridge.svg" alt="One devrig bridge connects your AI Agent to all running IDEs at once — and can start more" width="720">
</p>

### What Makes It Unique

MCP Steroid is the **only MCP server** offering ALL of:

- **Visual IDE understanding** — Screenshots + OCR + component tree
- **UI automation** — Control the IDE like a human developer
- **Native IntelliJ APIs** — PSI, inspections, refactorings, and more
- **Kotlin scripting** — Full platform access at runtime
- **Standard MCP protocol** — Works with ANY MCP-compatible AI agent

### Benchmarks

On [DPAIA](https://dpaia.org) Spring Boot tasks, agents with MCP Steroid are **20–54% faster** than file-only workflows on complex multi-file operations:

| Case | Task | MCP Time | No-MCP Time | &Delta; |
|------|------|----------|-------------|---------|
| dpaia\_jhipster\_sample\_app-3 | Rename ROLE_ADMIN across app (9 files) | 202s | 440s | **-54%** |
| dpaia\_empty\_maven\_springboot3-1 | JWT auth from scratch (5+ new files) | 288s | 396s | **-27%** |
| dpaia\_feature\_service-25 | Parent-child JPA + Flyway (10 files) | 382s | 523s | **-27%** |
| dpaia\_feature\_service-125 | Multi-layer JPA+service+controller (15 files) | 788s | 1002s | **-21%** |
| dpaia\_spring\_petclinic\_rest-14 | Simple URL prefix replace (7 files) | 188s | 181s | +4% |
| dpaia\_train\_ticket-1 | Extend OrderRepository JPQL (4 files) | 727s | 633s | +15% |

Tasks requiring semantic understanding show the largest speed gains. Simple text replacements perform similarly with or without IDE access.

---

## Install

**Requirements:** IntelliJ IDEA 2026.1+ (or any IntelliJ-based IDE: Rider, Android Studio, GoLand, WebStorm, PyCharm, CLion, etc.)

### JetBrains Marketplace

Search for **MCP Steroid** in **Settings > Plugins > Marketplace**, or install from [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/30019-mcp-steroid).

### Custom Plugin Repository (recommended for faster updates)

Add this URL in **Settings > Plugins > Gear icon > Manage Plugin Repositories...**:

```
https://mcp-steroid.jonnyzzz.com/updatePlugins.xml
```

### Manual Download

Download the latest ZIP from [GitHub Releases](https://github.com/jonnyzzz/mcp-steroid/releases) and install via **Settings > Plugins > Gear icon > Install Plugin from Disk**.

---

## Connect Your AI Agent

**[`devrig`](https://mcp-steroid.jonnyzzz.com/docs/devrig/)** is the standalone CLI for connecting agents to MCP Steroid. It runs a stdio MCP bridge and connects your agent to **every running IntelliJ instance at once** (across projects), discovered automatically — and when none is open it can **download, prepare, and start a managed IDE backend** for the agent (IDEA Community/Ultimate, PyCharm, Android Studio). It's the recommended way to wire up Claude Code, Codex CLI, and Gemini CLI — one registration per agent, applies to every project on the machine.

> **Requirement: JDK 25.** `devrig` is a Kotlin/JVM application compiled for **Java 25** (class-file v69) and is **not** bundled with a JRE. A **JDK/JRE 25 or newer** must be available on the machine that runs it — either on `PATH` or via `JAVA_HOME` (the `devrig` launcher honours `JAVA_HOME` first). An older Java (21, etc.) fails at startup with `UnsupportedClassVersionError ... class file version 69.0`. Install e.g. [Amazon Corretto 25](https://aws.amazon.com/corretto/) or [Eclipse Temurin 25](https://adoptium.net/), and point `JAVA_HOME` at it if your default `java` is older.

### One-time setup (from a checkout of this repo)

```bash
# Build :npx-kt:installDist and stage the launcher under ~/.mcp-steroid/devrig/
./gradlew deployNpx

# Register `mcp-steroid` (stdio) at user scope for each agent — once per machine.
# The first call (run by full path, since devrig is not on PATH yet) self-installs the stable
# launcher ~/.mcp-steroid/bin/devrig, links it onto PATH as `devrig`, and registers THAT — so
# registrations survive devrig upgrades. Use `devrig` (a fresh shell picks it up) thereafter.
~/.mcp-steroid/devrig/bin/devrig install claude
devrig install codex
devrig install gemini
```

Each `install` call delegates to the agent's own `mcp add` CLI, so the entry lands in the user-scope config (`~/.claude.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`) and is visible from every project.

### Refreshing the launcher

After pulling new commits, run `./gradlew deployNpx` again. It rebuilds `:npx-kt:installDist` and re-syncs `~/.mcp-steroid/devrig/` — the launcher path stays the same, so no re-registration is needed. Runtime state under `~/.mcp-steroid/{backends,caches,logs,markers,state}` is preserved.

### Verifying it works

```bash
claude mcp list                                 # mcp-steroid: ... - ✓ Connected
claude -p "List all open projects using steroid_list_projects"
```

The plugin also writes the raw server URL to `.idea/mcp-steroid.md` in each open project at `http://127.0.0.1:6315/mcp` (Streamable HTTP transport) for clients that prefer to talk to the IDE directly.

---

## Compatible AI Agents

Works with ANY MCP-compatible client:

- **Claude** (Claude Code, Claude Desktop)
- **ChatGPT** with MCP support
- **Gemini** CLI
- **Codex** CLI
- **Cursor** IDE
- **Junie**
- **OpenCode**
- Any other MCP-compatible client

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
| `mcp.steroid.storage.path` | (empty) | Custom storage path (default: .idea/mcp-steroid/) |

See the full [Configuration Documentation](https://mcp-steroid.jonnyzzz.com/docs/configuration/) on the website.

---

## Architecture

- **Technology:** Kotlin 2.3.20 on Java 25
- **HTTP Server:** Ktor 3.1.0 (Streamable HTTP + SSE)
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

- **Website:** [mcp-steroid.jonnyzzz.com](https://mcp-steroid.jonnyzzz.com)
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
