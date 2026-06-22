package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import kotlinx.serialization.json.put

class DevrigVisionScreenshotToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : VisionScreenshotToolHandler {
    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        val route = routing.requireProject(projectName)
        return bridge.callProjectTool(route, "steroid_take_screenshot", mcpProgressReporter) {
            put("task_id", screenshotParams.taskId)
            put("reason", screenshotParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            screenshotParams.windowId?.let { put("window_id", it) }
        }
    }
}
