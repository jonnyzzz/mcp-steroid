package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.get
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_take_screenshot MCP tool.
 */
class VisionScreenshotToolSpec(
    val projectNameRequired: Boolean = true,
    val handler: () -> VisionScreenshotToolHandler,
) : McpToolBase() {
    override val name = "steroid_take_screenshot"

    override val description = """
        Capture a screenshot of the IDE and return an image payload.

        HEAVY ENDPOINT: This is intended for debugging and tricky configuration only.
        Prefer steroid_execute_code for regular automation.

        Use steroid_list_windows when multiple IDE windows are open and pass window_id to target a specific window.

        The screenshot and component tree are saved under the execution folder:
        - screenshot.png
        - screenshot-tree.md
        - screenshot-meta.json

        After execution, call steroid_execute_feedback to log your feedback.
    """.trimIndent()

    val projectName = CommonToolParams.projectName(projectNameRequired).registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val windowId = CommonToolParams.windowId()
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName].orEmpty()
        val taskId = context[taskId]
        val reason = context[reason]
        val windowId = context[windowId]

        return handler().screenshotWindow(projectName, ScreenshotParams(taskId, reason, windowId), context.mcpProgressReporter)
    }
}

@Serializable
data class ScreenshotParams(
    val taskId: String,
    val reason: String,
    val windowId: String? = null
)

interface VisionScreenshotToolHandler {
    suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult
}

