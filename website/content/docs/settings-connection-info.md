---
title: "Connect your AI Agents"
description: "How to find and copy your MCP server connection details from the IntelliJ Settings page"
weight: 50
group: "Reference"
---

> **Available from 0.89.0.** Earlier versions only show connection info while a project is open.

## Overview

MCP Steroid includes a built-in **Settings panel** — **Tools → MCP Steroid — devrig** (reinstated in 0.101) — that explains how your **AI Agents** drive the IDE, promotes the recommended [devrig CLI](/docs/devrig/) (the one bridge that reaches every running IDE and survives restarts and port changes — see the docs to get started), shows the live MCP server status (port and URL), and keeps the legacy direct-HTTP connection details: ready-to-paste CLI commands for Claude, Codex, and Gemini, and a full JSON config block you can drop directly into your MCP client's config file.

Starting from **0.89.0**, the MCP server starts as soon as IntelliJ starts — before any project opens. The Settings page reflects this: the connection URL is always there, whether or not you have a project open.

## Opening the Settings Panel

1. In IntelliJ, go to **Settings** (`⌘,` on macOS / `Ctrl+Alt+S` on Windows/Linux)
2. Navigate to **Tools → MCP Steroid — devrig**

You will see the connection panel immediately, no project needs to be open.

## What You'll See

### Server URL

The URL your MCP clients connect to. Click the **copy icon** inside the field to copy it to the clipboard.

```
http://127.0.0.1:6315/mcp
```

### Quick Start Commands

Ready-to-run CLI commands for each supported agent — click the copy icon to grab the exact command for your tool:

| Agent  | Command |
|--------|---------|
| Claude | `claude mcp add --transport http --scope user mcp-steroid http://...` |
| Codex  | `codex mcp add mcp-steroid --url http://...` |
| Gemini | `mcp add mc --type http ...` |

### JSON Config

A complete JSON block ready to paste into your MCP client's config file (e.g. `claude_desktop_config.json`). Use the **Copy JSON Config** button below the text area.

```json
{
  "mcpServers": {
    "mcp-steroid": {
      "type": "http",
      "url": "http://127.0.0.1:6315/mcp"
    }
  }
}
```

## Always-On Server

Before 0.89.0, the MCP server only started when a project was opened. This meant:

- The Settings panel showed no URL if you opened it from the Welcome Screen
- Agents couldn't connect until at least one project was open

From **0.89.0** onwards, the server starts at IDE startup via an application lifecycle hook. The port is reserved immediately, and all connected MCP clients keep the same URL for the lifetime of the IDE session.

> **Note:** If you restart IntelliJ, connected MCP clients (Claude CLI, Codex, Gemini, etc.) will need to reconnect or be reconfigured with the new URL. The server URL may change between IDE restarts if `mcp.steroid.server.port` is set to `0` (auto-assign mode).

## Port Configuration

If the default port `6315` is in use, you can change it:

1. Go to `Help > Find Action > Registry…`
2. Search for `mcp.steroid.server.port`
3. Set a different port number
4. Restart IntelliJ — the new URL will appear in the Settings panel

See [Configuration](/docs/configuration/) for the full list of server settings.

## Related

- [Getting Started](/docs/getting-started/) — initial plugin setup and first connection
- [Configuration](/docs/configuration/) — registry keys for port and host
