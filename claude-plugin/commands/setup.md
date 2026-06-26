---
description: Install the devrig MCP bridge and register it with Claude (one-time, ~300 MB).
disable-model-invocation: true
allowed-tools:
  - Bash
---

The **devrig** plugin does not ship an MCP server itself. It needs the `devrig`
binary installed at `~/.mcp-steroid/bin/devrig` (POSIX) or
`~/.mcp-steroid/bin/devrig.cmd` (Windows), and `devrig` then registers itself as the
`devrig` MCP server in Claude (user scope). This is a one-time download (devrig +
a matching JDK, ~300 MB). Claude Code has no install-time hook, so this command does it.

Do the following:

1. Detect the operating system.

2. **Install devrig.** Run the bundled installer wrapper for that OS with the Bash tool,
   showing the user its output as it runs:
   - **macOS / Linux:** `sh "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig"`
   - **Windows:** `powershell -NoProfile -ExecutionPolicy Bypass -File "${CLAUDE_PLUGIN_ROOT}/bin/install-devrig.ps1"`

   If this command exits **non-zero** (download interrupted, timed out, or Ctrl+C): tell
   the user the install did not complete and to run `/devrig:setup` again — re-running
   is safe and resumes where it left off. Stop here on failure.

3. **Register devrig with Claude.** Once the install succeeded, run the freshly installed
   launcher with `install claude` so devrig writes the user-scope `devrig` MCP server
   entry (it picks the OS-correct launch command itself). Use the explicit launcher path,
   not a bare `devrig` — a freshly PATH-updated shell may not see it yet:
   - **macOS / Linux:** `"$HOME/.mcp-steroid/bin/devrig" install claude`
   - **Windows:** `"%USERPROFILE%\.mcp-steroid\bin\devrig.cmd" install claude`

   This step is idempotent (it consolidates any existing devrig entries into one). If it
   exits non-zero, show the error and tell the user to run `/devrig:setup` again.

4. On success, tell the user devrig is installed and registered, and that they should
   **restart Claude** so the `devrig` MCP server is picked up.

Do not attempt to download devrig yourself or reimplement the installer or the registration
— only run the wrapper script and the `devrig install claude` command above.
