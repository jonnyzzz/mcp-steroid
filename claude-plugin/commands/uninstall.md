---
description: Unregister devrig from Claude and remove the installed devrig binary and JDK (destructive).
disable-model-invocation: true
allowed-tools:
  - Bash
---

Completely remove devrig: unregister the `devrig` MCP server from Claude and delete the
installed binary + bundled JDK under `~/.mcp-steroid`. This is **destructive and not
reversible** — reinstalling later means downloading devrig again (~300 MB).

Do the following:

1. **Confirm first.** Before changing anything, tell the user exactly what will be removed
   (the Claude `devrig` MCP registration and the entire `~/.mcp-steroid` directory) and ask
   them to confirm. If they do not explicitly confirm, stop and do nothing.

2. Detect the operating system.

3. **Unregister from Claude** (user scope). Run both, ignoring a "not found" error — the
   legacy name is cleaned up too:
   - `claude mcp remove --scope user devrig`
   - `claude mcp remove --scope user mcp-steroid`

4. **Delete the install directory** with the Bash tool:
   - **macOS / Linux:** `rm -rf "$HOME/.mcp-steroid"`
   - **Windows:** `Remove-Item -Recurse -Force "$env:USERPROFILE\.mcp-steroid"`

5. Tell the user devrig has been removed and to **restart Claude**. To reinstall later, they
   can run `/devrig:setup`.

Only run the commands above. Do not touch any directory other than `~/.mcp-steroid`, and do
not remove unrelated MCP servers from Claude.
