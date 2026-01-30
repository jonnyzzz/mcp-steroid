# MCP Steroid

<p align="center">
  <img src="docs/pluginIcon.svg" alt="MCP Steroid Logo" width="120" height="120">
</p>

<p align="center">
  <strong>Give AI the whole IntelliJ, not just the files</strong><br>
  <em>AI agents can finally SEE IntelliJ - not just read code</em>
</p>

<p align="center">
  <a href="https://mcp-steroid.jonnyzzz.com">Website</a> •
  <a href="https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f">Demo Videos</a> •
  <a href="https://jonnyzzz.com/blog/2026/01/04/mcp-steroids-intellij/">Blog Post</a> •
  <a href="https://linkedin.com/in/jonnyzzz">Get Trial Access</a>
</p>

---

## About MCP Steroid

**MCP Steroid** is an independent research project that brings the full power of the IntelliJ Platform to AI agents through the Model Context Protocol (MCP).

### The Mission

**IntelliJ platform works for AI agents as great as for human developers.**

Unlike LSP-only solutions, MCP Steroid gives AI agents the same powerful IDE capabilities that developers use: semantic understanding, advanced refactorings, debugging, test running, and visual awareness.

### What Makes It Unique

MCP Steroid is the **only MCP server** offering ALL of:

- ✨ **Visual IDE understanding** - Screenshots + OCR + component tree
- 🎯 **UI automation capabilities** - Control the IDE like a human
- 🔧 **Native IntelliJ APIs** - PSI, inspections, refactorings, and more
- ⚡ **Kotlin scripting** - Full platform access at runtime
- 🛡️ **Human-in-the-loop safety** - 3 review modes (ALWAYS/TRUSTED/NEVER)
- 🌐 **Standard MCP protocol** - Works with ANY AI agent

### Verified Capabilities

- **9 MCP Tools** - Control IntelliJ programmatically
- **58 MCP Resources** - Comprehensive guides and examples
- **All IntelliJ Platform IDEs** - IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, and more
- **Requirements** - IntelliJ 2025.3 or newer (build 253.*)

---

## Get Started

### 🎯 Get Trial Access

The plugin is currently in early access. [Message Eugene Petrenko on LinkedIn](https://linkedin.com/in/jonnyzzz) to get access to the latest build.

### 📖 Learn More

- **Website:** [mcp-steroid.jonnyzzz.com](https://mcp-steroid.jonnyzzz.com)
- **Blog Post:** [MCP Steroids for IntelliJ](https://jonnyzzz.com/blog/2026/01/04/mcp-steroids-intellij/)
- **Demo Videos:** [YouTube Playlist](https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f)

---

## Featured Demo Videos

| Video | Description | Duration |
|-------|-------------|----------|
| [CodeDozer Demo 5](https://www.youtube.com/watch?v=6ByedA15n8Q) | Most popular demo | 1:00 |
| [CodeDozer & IntelliJ Debugger](https://www.youtube.com/watch?v=8MjogrpfXLU) | Debugger integration showcase | 8:25 |
| [Now we call tasks in IntelliJ](https://www.youtube.com/watch?v=JGcRk7Y3-Z8) | Task execution demo | 2:21 |
| [Real Work in Monorepo Part 2](https://www.youtube.com/watch?v=ibc0saTT06M) | Deep dive into real workflow | 18:37 |
| [Cursor Talks with IntelliJ](https://www.youtube.com/watch?v=QIl57FrAJtk) | Cursor integration | 0:44 |
| [Antigravity Mandelbrot Window](https://www.youtube.com/watch?v=JNtYCWlRYak) | Visual capabilities demo | 0:39 |

Watch all demos: [MCP Steroid Playlist](https://www.youtube.com/playlist?list=PLitZWClhc4Qgz3w8qrtctMR_lpIc81n0f)

---

## Compatible AI Agents

Works with ANY MCP-compatible client:

- 🤖 **Claude** (Claude Code, Claude Desktop)
- 💬 **ChatGPT** with MCP support
- 🌟 **Gemini** CLI
- 🦙 **Llama** models via MCP clients
- 📝 **Codex CLI**
- ✏️ **Cursor** IDE
- 🎨 **Junie**
- 🔓 **OpenCode**
- 🔌 Any other MCP-compatible client

---

## Key Features

### 9 MCP Tools

1. **List Projects** - Discover all open IntelliJ projects
2. **List Windows** - Enumerate IDE windows and components
3. **Execute Code** - Run Kotlin code in IDE runtime
4. **Execute Feedback** - Provide execution results to agents
5. **Capabilities Discovery** - Explore available IDE features
6. **Action Discovery** - Find and invoke IDE actions
7. **Vision Screenshot** - Capture IDE screenshots with metadata
8. **Vision Input** - OCR and component tree analysis
9. **Open Project** - Open projects programmatically

### 58 MCP Resources

Comprehensive documentation and examples covering:

- **LSP Operations** (11 resources) - Go to definition, find references, hover, completion, and more
- **IDE Power Operations** (22 resources) - Refactorings, code generation, project analysis
- **Debugger Integration** (7 resources) - Breakpoints, thread control, debugging workflows
- **Test Runner** (10 resources) - Run tests, inspect results, navigate test trees
- **VCS Operations** (3 resources) - Git annotations, file history
- **Project Workflows** (4 resources) - Open projects with different trust levels
- **Skill Guides** (3 resources) - IntelliJ API, debugger, and test runner guides

---

## Architecture

- **Technology:** Kotlin 2.2.21 on Java 21
- **HTTP Server:** Ktor 3.1.0
- **Transport:** HTTP + SSE (Server-Sent Events)
- **Protocol:** Model Context Protocol (MCP)
- **Port:** 6315 (default)
- **OCR:** Tesseract 5.5.1
- **Platform:** IntelliJ Platform Plugin SDK

---

## About the Project

**MCP Steroid** is an independent research project by Eugene Petrenko ([@jonnyzzz](https://linkedin.com/in/jonnyzzz)).

Not affiliated with, endorsed by, or supported by JetBrains s.r.o.

*IntelliJ IDEA, IntelliJ Platform, PyCharm, WebStorm, and JetBrains are trademarks of JetBrains s.r.o.*

---

## Links

- 🌐 **Website:** [mcp-steroid.jonnyzzz.com](https://mcp-steroid.jonnyzzz.com)
- 📝 **Blog:** [jonnyzzz.com](https://jonnyzzz.com)
- 🎥 **YouTube:** [@jonnyzzz](https://youtube.com/@jonnyzzz)
- 💼 **LinkedIn:** [jonnyzzz](https://linkedin.com/in/jonnyzzz)
- 🐦 **X/Twitter:** [@jonnyzzz](https://x.com/jonnyzzz)
- 💻 **Source Code:** Available upon request for trial participants

---

## Feedback & Issues

Found a bug or have a feature request? Please report it in the [Issues](https://github.com/jonnyzzz/mcp-steroid/issues) section.

---

<p align="center">
  <sub>Built with ❤️ for the AI agent developer community</sub>
</p>
