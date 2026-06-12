---
title: "Getting Started"
description: "Install and set up MCP Steroid with your AI Agent"
weight: 10
group: "Getting Started"
---

## What is MCP Steroid?

MCP Steroid gives AI Agents **visual understanding and full control** of your IntelliJ-based IDE through the Model Context Protocol. Unlike traditional code assistants
that only see text, MCP Steroid lets agents SEE your IDE: dialogs, toolbars, debugger state, test results, and visual elements.

**[Read the full introduction →](https://jonnyzzz.com/blog/2026/01/04/mcp-steroids-intellij/)**

## Installation

MCP Steroid is distributed as an IntelliJ plugin. Currently, in early access - [message Eugene Petrenko on LinkedIn](https://linkedin.com/in/jonnyzzz) to get access.

### Requirements

- IntelliJ IDEA 2025.3+ (or any IntelliJ-based IDE: Rider, Android Studio, GoLand, WebStorm, PyCharm, etc.)
- An MCP-compatible AI Agent (Claude Code, Codex, Gemini, or any MCP client)

### Install the Plugin

1. Download the plugin ZIP file (provided via LinkedIn)
2. In IntelliJ, go to **Settings > Plugins > Gear icon > Install Plugin from Disk**
3. Select the downloaded ZIP file
4. Restart IntelliJ

## Connecting Your AI Agent

When the plugin starts, it automatically creates a description file at `.idea/mcp-steroid.md` in each open project.
This file contains all necessary instruction for you to continue.

Verify the connection:
```bash
claude -p "List all open projects using steroid_list_projects"
codex exec "List all open projects using steroid_list_projects"
gemini "List all open projects using steroid_list_projects"
```

Any MCP-compatible client can connect using the HTTP/SSE transport at the server URL.

We recommend you to aks your AI Agent to use IntelliJ APIs and use IntelliJ while it is working.

## Use Cases & Examples

MCP Steroid enables powerful agentic workflows:

- **Multi-Agent Orchestration**: Use a primary agent to coordinate multiple AI Agents working on different aspects of your codebase. [Read about orchestrating AI fleets →](https://jonnyzzz.com/blog/2026/01/30/orchestrating-ai-fleets/)
- **Natural Language Development**: Instruct agents in plain English while they handle the technical implementation. [Learn about coding in English with AI →](https://jonnyzzz.com/blog/2026/01/27/coding-in-english-with-ai/)
- **Documentation Refactoring**: See how 16 agents improved documentation quality by 15% and reduced time-to-value by 5x. [Read the case study →](https://jonnyzzz.com/blog/2026/01/24/16-ai-agents-documentation-refactor/)

## Troubleshooting

### Connection Issues

**MCP Server Not Starting**
- Check if IntelliJ is running with a project open
- Verify `.idea/mcp-steroid.md` exists in your project
- Check registry key: `Help > Find Action > Registry...` → `mcp.steroid.server.port`

**Port Conflicts**

If port 6315 is in use, change it:
1. Go to `Help > Find Action > Registry...`
2. Search for `mcp.steroid.server.port`
3. Set a different port (e.g., 6316)
4. Restart IntelliJ
5. Update your MCP client configuration with the new URL from `.idea/mcp-steroid.md`


After connecting your AI Agent, you should see a list of currently open IntelliJ projects when testing with the `steroid_list_projects` tool. If the connection works, your AI Agent can now access all MCP Steroid capabilities.

For more troubleshooting help, see [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues).

## Next Steps

- [Configuration Options](/docs/configuration/) - Customize server settings and timeouts
- [GitHub Issues](https://github.com/jonnyzzz/mcp-steroid/issues) - Report bugs or request features
