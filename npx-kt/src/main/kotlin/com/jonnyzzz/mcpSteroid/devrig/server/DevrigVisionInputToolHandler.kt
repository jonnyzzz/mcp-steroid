package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import kotlinx.serialization.json.put

class DevrigVisionInputToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : VisionInputToolHandler {
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val route = routing.requireProject(projectName)
        val rawSequence = inputParams.rawSequence
            ?: return ToolCallResult.errorResult("Input sequence cannot be forwarded without the original sequence string")
        return bridge.callProjectTool(route, "steroid_input") {
            put("task_id", inputParams.taskId)
            put("reason", inputParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            put("window_id", inputParams.windowId)
            put("sequence", rawSequence)
        }
    }
}
