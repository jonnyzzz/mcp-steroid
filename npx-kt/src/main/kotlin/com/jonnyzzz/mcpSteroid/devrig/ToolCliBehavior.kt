/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

/**
 * CLI-only state for a generated tool command that has no MCP `inputSchema` parameter to carry it:
 * the `execute_code` / `execute_feedback` `--code-file` source, the `take_screenshot` `--out` target,
 * and the `open_project` `--wait` poll toggle. Held on [DevrigCommand.RunTool] beside the typed
 * `arguments` JSON so runtime behavior can act on it without threading services, presentation, or
 * handler-bound specs through parsing. It contains genuine CLI state only — never a service, a
 * `Presentation`, a handler, or an `Any?`.
 */
data class ToolCliExtras(
    val codeFile: String? = null,
    val out: String? = null,
    val wait: Boolean = false,
)
