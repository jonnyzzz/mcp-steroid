---
title: "Documentation"
description: "Learn how to install, configure, and use MCP Steroid"
---

**Start with `devrig`** — a standalone CLI that gives your AI Agent a whole JetBrains IDE. One `devrig` process registers MCP Steroid with your agent, bridges it to **every IntelliJ-family IDE running on your machine at once**, and can download and start a managed IDE backend on demand — no manual MCP configuration, even on a headless box.

```bash
# 1. Register devrig with your agent (once)
devrig install claude

# 2. Download a managed IntelliJ IDEA Community backend
devrig backend download idea-community

# 3. Start it in detached mode — prints pid, log, and config paths
devrig backend start idea-community

# 4. Confirm it's discoverable and see its open projects
devrig backend
```

Get the `devrig-*.zip` from the [latest release](https://github.com/jonnyzzz/mcp-steroid/releases/latest) (requires Java 25), then follow [Getting Started](/docs/devrig/). Prefer installing the plugin into an IDE you already run? See [Manual plugin installation](/docs/getting-started/).

With the bridge in place, your agents run tests, analyze code, drive real refactorings, and orchestrate complex development workflows through the IDE — instead of guessing from plain files.

**New to agent-driven development?** Read about [coding in English with AI](https://jonnyzzz.com/blog/2026/01/27/coding-in-english-with-ai/) and [orchestrating AI fleets](https://jonnyzzz.com/blog/2026/01/30/orchestrating-ai-fleets/).

**See AI Agents in action:** The [How to Debug an IDE](/docs/how-to-debug-ide/) guide was written entirely by AI Agents using MCP Steroid while working on the IntelliJ Platform codebase - a real example of what autonomous agents can discover and document when given full IDE access.

**Build reusable skills:** Learn how to turn one-off API explorations into reusable AI Agent capabilities in [IntelliJ as a Skill Factory](/docs/skill-factory/).
