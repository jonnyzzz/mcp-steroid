/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.serialization.json.JsonObject

/**
 * Dispatches a CLI tool-backed command through the SAME `*ToolSpec.call()` path the `devrig mcp` stdio
 * proxy uses, instead of the CLI hand-building each tool's `*Params` and calling a specific handler
 * method (reviewer comments C9 + C15). The CLI's job shrinks to "map CLI flags → tool-call JSON
 * [arguments]"; the spec owns arg parsing and the handler invocation — one source of truth.
 *
 * Design constraint (must hold): this calls [McpTool.call] DIRECTLY, NOT through
 * `McpToolRegistry.callTool`. The registry catches every exception into a generic `isError` result,
 * which would collapse the CLI's frozen exit-code contract (`ProjectRouteNotFoundException` → USAGE 64,
 * a bridge failure → UNAVAILABLE 69, …). Calling `spec.call()` directly lets those exceptions propagate
 * so the caller's existing `try/catch → renderCliError(...)` mapping assigns the right [com.jonnyzzz.mcpSteroid.devrig.CliExit]
 * code. The spec is constructed with the SAME handler wiring the proxy uses (e.g.
 * `ExecuteCodeToolSpec { tools.handler<ExecuteCodeToolHandler>() }`), so it reaches the real devrig
 * handler.
 *
 * The [McpSession] is a fresh no-arg session (no SSE/sampling is used from the CLI); [progress] is the
 * CLI's stderr progress reporter so tool progress stays off stdout.
 */
suspend fun callToolViaSpec(
    spec: McpTool,
    arguments: JsonObject,
    progress: McpProgressReporter,
): ToolCallResult {
    val params = ToolCallParams(name = spec.name, arguments = arguments)
    val context = ToolCallContext(params, McpSession(), progress)
    return spec.call(context)
}
