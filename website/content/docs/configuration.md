---
title: "MCP Steroid Configuration"
description: "Registry keys and settings for MCP Steroid"
weight: 60
group: "Reference"
---

MCP Steroid can be configured via IntelliJ's Registry (`Help > Find Action > Registry`) or via JVM system properties (`-D` flags). All settings use the `mcp.steroid.*` prefix.

## Server Settings

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.server.port` | `6315` | MCP server port. Use `0` for auto-assign. The actual URL is written to `.idea/mcp-steroid.md`. |
| `mcp.steroid.server.host` | `127.0.0.1` | MCP server bind address. Use `0.0.0.0` for Docker/remote access. |

## Execution

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.execution.timeout` | `600` | Script execution timeout in seconds. |
| `mcp.steroid.dialog.killer.enabled` | `true` | Automatically close modal dialogs before code execution. Prevents execution failures when dialogs are blocking the IDE. |

## Storage

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.storage.path` | (empty) | Custom path for MCP execution storage. Empty uses `~/.mcp-steroid/runs/`. |
| `mcp.steroid.idea.description.enabled` | `true` | Generate `.idea/mcp-steroid.md` description file in projects. |

## Demo Mode

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.demo.enabled` | `false` | Enable Demo Mode overlay during MCP execution. |
| `mcp.steroid.demo.minDisplayTime` | `3000` | Minimum display time in milliseconds. |
| `mcp.steroid.demo.maxLines` | `15` | Maximum log lines to show in overlay. |
| `mcp.steroid.demo.opacity` | `85` | Background opacity (0-100). |
| `mcp.steroid.demo.focusFrame` | `true` | Bring project frame to front when showing overlay. |

## Updates

| Registry Key | Default | Description |
|-------------|---------|-------------|
| `mcp.steroid.updates.enabled` | `true` | Enable automatic update checks. |

---

## Dialog Killer

The **Dialog Killer** feature automatically closes modal dialogs before code execution. This prevents execution failures when dialogs (like Settings, refactoring confirmations, or error messages) are blocking the IDE.

### How It Works

When you call `steroid_execute_code`:

1. **Detection**: Checks if a modal dialog is currently open using `ModalityState`
2. **Screenshot**: Captures a screenshot of the dialog for debugging (saved in `~/.mcp-steroid/runs/{execution-id}/`)
3. **Closure**: Closes all modal dialogs owned by the project frame using `doCancelAction()`
4. **Logging**: Reports the activity via IDE log and MCP progress messages
5. **Execution**: Proceeds with your code execution normally

### When to Disable

You might want to disable the dialog killer (`mcp.steroid.dialog.killer.enabled = false`) if:

- You're intentionally working with dialogs and want them to remain open
- You're testing dialog-related functionality
- You want to see what dialogs the IDE is showing during development

### Screenshot Location

When dialogs are detected and closed, a screenshot is automatically saved to the current execution:
```
~/.mcp-steroid/runs/{execution-id}/screenshot.png
```

This helps you understand what was blocking the IDE and verify the dialog killer is working correctly.

---

> **Note:** When the plugin starts, it writes the server URL to `.idea/mcp-steroid.md` in each open project. The first line contains the URL (for example, `http://127.0.0.1:6315/mcp`). This file is your MCP client's connection target.

> **Tip:** You can also set these as JVM system properties by using `-Dmcp.steroid.server.port=8080` in your IDE's VM options. System properties take precedence over Registry values.
