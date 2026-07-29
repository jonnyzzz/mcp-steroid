package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.cliMissingHint
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.vision.InputSequenceParser
import com.jonnyzzz.mcpSteroid.vision.InputStep
import com.jonnyzzz.mcpSteroid.vision.InputTarget
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_input MCP tool.
 */
class VisionInputToolSpec(val handler: () -> VisionInputToolHandler) : McpToolBase() {
    override val name = "steroid_input"

    override val description = """
        Send input events (keyboard + mouse) to the IDE using a sequence string.

        HEAVY ENDPOINT: Intended for debugging only. Prefer steroid_execute_code for regular automation.

        Sequence format (comma-separated or newline-separated steps; commas optional with newlines):
        - stick:ALT           (hold a key until the end)
        - delay:400           (milliseconds)
        - press:CTRL+P        (press key with modifiers)
        - type:hello          (type text; commas are allowed unless they look like a new step)
        - click:CTRL+Left@120,200        (click with modifiers at screenshot coords)
        - click:Right@screen:400,300     (click at screen coords)

        Comma separators are detected by ", <step>:" patterns, so avoid typing ", delay:" etc.
        Trailing commas before a newline are ignored.
        Use "#" for comments until the end of the line.

        All keys are released at the end of the sequence.

        The input is delivered to the window identified by window_id (from steroid_list_windows) and the focus is forced to that window.
        Click coordinates with the screenshot target (e.g. @120,200) are interpreted relative to the window as reported by steroid_list_windows / steroid_take_screenshot.
    """.trimIndent()
    override val cliSynopsis = "send keyboard/mouse input to the IDE"

    val projectName = CommonToolParams.projectName().registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val windowId = CommonToolParams.windowId()
        .required()
        .cliMissingHint("missing required --window_id (get it from `devrig list_windows`)")
        .registerToSchema()

    val sequence = InputSchemaElement.param("sequence")
        .description("Comma-separated input sequence (stick/press/type/click/delay)")
        .cliSynopsis("comma-separated input steps (stick/press/type/click/delay)")
        .cliMissingHint(
            "missing --sequence. Example:\n" +
                "  devrig input --project_name=\"<key>\" --window_id=\"<win>\" --task_id=t1 --reason=\"...\" \\\n" +
                "    --sequence=\"press:CTRL+P, type:Main, delay:200, press:ENTER\""
        )
        .string()
        .required()
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        val taskId = context[taskId]
        val reason = context[reason]
        val windowId = context[windowId]
        val sequence = context[sequence]

        val parsed = InputSequenceParser().parse(sequence)
        if (parsed.filterIsInstance<InputStep.Click>().any { it.target is InputTarget.Unsupported }) {
            throw IllegalArgumentException("Unsupported target in sequence. Only screenshot/screen targets are supported.")
        }

        return handler().handleInputSequence(projectName, InputParams(
            taskId = taskId,
            reason = reason,
            windowId = windowId,
            sequence = parsed,
            rawSequence = sequence,
        ))
    }
}

@Serializable
data class InputParams(
    val taskId: String,
    val reason: String,
    val windowId: String,
    val sequence: List<InputStep>,
    val rawSequence: String? = null,
)

interface VisionInputToolHandler {
    suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult
}
