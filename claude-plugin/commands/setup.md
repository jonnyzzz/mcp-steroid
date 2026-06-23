---
description: Install the devrig MCP bridge that mcp-steroid requires (one-time, ~300 MB).
disable-model-invocation: true
allowed-tools:
  - Bash
---

The **mcp-steroid** plugin needs the `devrig` binary installed at
`~/.mcp-steroid/bin/devrig` (POSIX) or `~/.mcp-steroid/bin/devrig.cmd` (Windows)
before its MCP server can start. This is a one-time download (devrig + a matching
JDK, ~300 MB). Claude Code has no install-time hook, so this command does it.

Do the following:

1. Detect the operating system.
2. Run the bundled installer wrapper for that OS, using the Bash tool, and show
   the user its output as it runs:
   - **macOS / Linux:** `sh "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig"`
   - **Windows:** `powershell -NoProfile -ExecutionPolicy Bypass -File "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig.ps1"`
3. Check the command's exit code:
   - **Non-zero** (e.g. the download was interrupted, timed out, or hit Ctrl+C):
     tell the user the install did not complete and that they should run
     `/mcp-steroid:setup` again — re-running is safe and resumes where it left off.
   - **Zero:** tell the user devrig is installed and they should **restart Claude**
     so the mcp-steroid MCP server picks it up.

Do not attempt to download devrig yourself or reimplement the installer — only run
the wrapper script above.
