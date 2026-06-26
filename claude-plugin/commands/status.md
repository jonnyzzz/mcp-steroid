---
description: Check whether devrig is installed and registered with Claude, and report any drift (read-only).
disable-model-invocation: true
allowed-tools:
  - Bash
---

Report the devrig installation/registration status. This is **read-only** — it changes
nothing. It runs devrig's own `install claude --check` doctor, which lists Claude's MCP
servers and compares them against the canonical `devrig` registration.

Do the following:

1. Detect the operating system.

2. If the devrig launcher is missing, devrig is not installed — tell the user to run
   `/devrig:setup`, and stop. Check the launcher path for the OS:
   - **macOS / Linux:** `~/.mcp-steroid/bin/devrig`
   - **Windows:** `%USERPROFILE%\.mcp-steroid\bin\devrig.cmd`

3. Otherwise run the read-only check with the Bash tool, showing its output:
   - **macOS / Linux:** `"$HOME/.mcp-steroid/bin/devrig" install claude --check`
   - **Windows:** `"%USERPROFILE%\.mcp-steroid\bin\devrig.cmd" install claude --check`

4. Interpret the exit code for the user:
   - **exit 0** — devrig is installed and the `devrig` MCP server is registered canonically.
     Nothing to do. (If the tools still aren't available, suggest restarting Claude.)
   - **exit 1** — drift detected (not registered, stale launch command, duplicate, or
     registered under a different name). Tell the user to run `/devrig:setup` to repair it,
     then restart Claude. Show the repair plan the check printed.
   - **other non-zero** — show the error and suggest re-running `/devrig:setup`.

Do not modify any configuration — only run the `--check` command above. Use the explicit
launcher path, not a bare `devrig`, since a freshly PATH-updated shell may not see it yet.
