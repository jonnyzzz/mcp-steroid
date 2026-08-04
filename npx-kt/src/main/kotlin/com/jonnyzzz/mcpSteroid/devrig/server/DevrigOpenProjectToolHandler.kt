package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.put

class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val backends: DevrigBackendService,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(
        openProjectParams: OpenProjectParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val requested = openProjectParams.backendName?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = backends.candidates()
        val chosen = when {
            requested != null -> candidates.firstOrNull { it.backendName == requested }
                ?: return ToolCallResult.errorResult(unknownBackendMessage(requested, candidates))
            candidates.size == 1 -> candidates.single()
            else -> return ToolCallResult.errorResult(chooseBackendMessage(candidates))
        }
        val ide = try {
            backends.ensureBackendRunning(chosen, progress = callProgress)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return ToolCallResult.errorResult(e.message ?: e.toString())
        }
        return bridge.callTool(ide, "steroid_open_project", callProgress) {
            put("project_path", openProjectParams.projectPath)
            put("trust_project", openProjectParams.trustProject)
            put("task_id", "open-project")
            put("reason", "Open project through devrig")
        }
    }
}

private fun unknownBackendMessage(requested: String, candidates: List<BackendCandidate>): String {
    val list = candidateList(candidates)
    return "Unknown backend_name '$requested'. $list"
}

private fun chooseBackendMessage(candidates: List<BackendCandidate>): String {
    val list = candidateList(candidates)
    return "open_project requires exactly one candidate or an explicit backend_name. $list"
}

private fun candidateList(candidates: List<BackendCandidate>): String {
    // Tool payloads are served verbatim to BOTH surfaces (MCP clients over `devrig mcp`, and the CLI,
    // which never rewrites tool output) — so this text must stay surface-neutral: naming either
    // `steroid_list_projects` or `devrig list_projects` would be wrong for the other reader.
    if (candidates.isEmpty()) {
        return "No candidates are currently available. Run devrig backend download --json to discover " +
            "and install an IDE, then retry this open-project command."
    }
    val items = candidates.joinToString("\n") { c ->
        val tag = if (c.startable != null) " (startable)" else ""
        "  ${c.backendName} — ${c.displayName}$tag"
    }
    return "Available candidates:\n$items"
}
