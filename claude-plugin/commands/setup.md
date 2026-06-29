---
description: Pre-download the devrig IDE bridge now (~300 MB). Optional — the plugin downloads it automatically in the background.
disable-model-invocation: true
allowed-tools:
  - Bash
---

The **devrig** plugin already registers its MCP server automatically (via the
bundled `.mcp.json`) and downloads the devrig binary in the background on first
use. Run this command only to **fetch the ~300 MB binary now** instead of waiting
for the background download.

Do the following:

1. Detect the operating system.

2. **Pre-download devrig.** Run the bundled installer wrapper for that OS with the
   Bash tool, showing its output as it runs:
   - **macOS / Linux:** `sh "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig"`
   - **Windows:** `powershell -NoProfile -ExecutionPolicy Bypass -File "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig.ps1"`

   If this exits **non-zero** (download interrupted/timed out): tell the user to
   re-run `/devrig:setup` — re-running is safe and resumes. Stop here on failure.

3. **Remove any legacy user-scope registration** so it does not duplicate the
   plugin's own MCP server. The plugin registers `devrig` itself; a leftover
   user-scope entry from an older devrig version would create a second server:
   - Run `claude mcp remove devrig --scope user` (ignore "not found"; it is
     idempotent and only removes a stale duplicate). The plugin's `.mcp.json`
     handles MCP server registration, so do not attempt to register devrig manually.

4. On success, tell the user devrig is downloaded and that they should **restart
   Claude** so the plugin's `devrig` MCP server switches from the bootstrap to the
   full IDE bridge.

Do not attempt to download devrig yourself or reimplement the installer — only run
the wrapper script and the cleanup above.
