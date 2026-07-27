---
description: Install the devrig IDE bridge (~611 MB). Required before the IDE tools work.
disable-model-invocation: true
allowed-tools:
  - Bash
---

The **devrig** plugin already registers its MCP server automatically (via the
bundled `.mcp.json`), but the server cannot start until devrig itself is
installed — there is no automatic background download. Run this command to
**install the ~611 MB devrig binary now**.

Do the following:

1. Detect the operating system.

2. **Install devrig.** Run the bundled installer wrapper for that OS with the
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

4. On success, tell the user devrig is installed and that they should **restart
   Claude** (or start a new session) so the plugin's `devrig` MCP server can start
   now that devrig is installed. IDE tools activate after that.

Do not attempt to download devrig yourself or reimplement the installer — only run
the wrapper script and the cleanup above.
