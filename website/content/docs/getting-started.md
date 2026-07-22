---
title: "Getting Started"
description: "Install devrig, register your AI agent, and add the MCP Steroid plugin"
weight: 10
group: "Getting Started"
---

## What is devrig?

**devrig** is the product you install: a small command-line tool that connects your
AI coding agent (Claude Code, Codex, or Gemini) to a real JetBrains IDE. It brings its
own runtime, registers itself with your agent, and bridges the agent's calls to the
IDE — no manual MCP wiring.

devrig reaches the IDE through **MCP Steroid**, a JetBrains IDE plugin that exposes the
IDE's real semantic actions — typed refactors, inspections, the debugger, and test runs.
You install devrig; devrig uses MCP Steroid.

## 1. Install devrig — one command

{{< install-cta >}}

This installs the `devrig` CLI together with its own runtime into `~/.mcp-steroid` —
there is nothing else to set up by hand.

## 2. Register your AI agent

```bash
devrig install claude
devrig install codex
devrig install gemini
```

`devrig install <agent>` registers devrig as the `mcp-steroid` MCP server in Claude Code,
Codex, or Gemini. The agent must be one of `claude`, `codex`, or `gemini`. See the
[devrig CLI guide](/docs/devrig/) for the full command set.

## 3. Install the MCP Steroid plugin

In your JetBrains IDE, install **MCP Steroid** from the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/30019-mcp-steroid).

### Requirements

- A JetBrains IDE — IntelliJ IDEA, PyCharm, GoLand, WebStorm, Rider, CLion, or Android Studio
- The IDE must run with its normal UI or as a remote development backend — headless launches
  (`-Djava.awt.headless=true`) are unsupported (best-effort, see
  [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177))
- An MCP-compatible AI agent (Claude Code, Codex, Gemini, or any MCP client)

## Verify the connection

When the plugin starts, it writes a description file at `.idea/mcp-steroid.md` in each open
project with the connection details. Ask your agent to list the open projects:

```bash
claude -p "List all open projects using steroid_list_projects"
codex exec "List all open projects using steroid_list_projects"
gemini "List all open projects using steroid_list_projects"
```

If you see your open IntelliJ projects, the connection works and your agent can now use all
MCP Steroid capabilities. We recommend asking your agent to use IntelliJ APIs and the IDE
while it works.

## Troubleshooting

**MCP server not starting**

- Check that IntelliJ is running
- Verify `.idea/mcp-steroid.md` exists in your project
- Check the registry key: `Help > Find Action > Registry...` → `mcp.steroid.server.port`

**Port conflicts**

If port 6315 is in use, change it:

1. Go to `Help > Find Action > Registry...`
2. Search for `mcp.steroid.server.port`
3. Set a different port (e.g., 6316)
4. Restart IntelliJ
5. Update your MCP client with the new URL from `.idea/mcp-steroid.md`

**Headless IDE (unsupported)**

If `idea.log` contains the WARN `MCP Steroid is running in a headless IDE`, the IDE was
launched without a UI (e.g. `-Djava.awt.headless=true`). Headless mode is unsupported
(best-effort): long blocking waits and deadlocks in platform code have been observed — see
[#177](https://github.com/jonnyzzz/mcp-steroid/issues/177). Run a normal desktop IDE or a
remote development backend instead.

## Next Steps

- [devrig CLI](/docs/devrig/) — every command, and how devrig bridges your agent to the IDE
- [Configuration Options](/docs/configuration/) — customize server settings and timeouts
- [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues) — report bugs or request features
