package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.cliMissingHint
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.maximum
import com.jonnyzzz.mcpSteroid.mcp.minimum
import com.jonnyzzz.mcpSteroid.mcp.number
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Handler for the steroid_execute_feedback MCP tool.
 *
 * Allows agents to provide feedback on execution results, including:
 * - Success rating (0.00 to 1.00)
 * - Explanation of the rating
 * - Association with a task_id
 */
class ExecuteFeedbackToolSpec(val handler: () -> ExecuteFeedbackToolHandler) : McpToolBase() {
    override val name = "steroid_execute_feedback"
    override val description = """
            Provide feedback on the result of a steroid_execute_code call and suggestions to improve the service.

            Use this tool to rate execution results and track what worked or didn't work.

            PARAMETERS:
            - project_name: The project where execution occurred
            - task_id: The same task_id you used in steroid_execute_code
            - execution_id: The execution_id returned in the steroid_execute_code result
            - success_rating: Rate from 0.00 (complete failure) to 1.00 (complete success)
              - 0.00-0.25: Complete failure, nothing worked
              - 0.25-0.50: Partial failure, some errors occurred
              - 0.50-0.75: Partial success, achieved some goals
              - 0.75-1.00: Success, achieved the intended goal
            - explanation: Describe what worked, what didn't, and what you'll try next
            - code (optional): The code snippet that illustrates the feedback the best way

            Feedback helps track execution history and identify patterns for improvement.
        """.trimIndent()
    override val cliSynopsis = "rate an execution and suggest improvements"

    val projectName = CommonToolParams.projectName().registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val executionId = InputSchemaElement.param("execution_id")
        .description("The execution_id returned from the most recent steroid_execute_code call for this task")
        .cliSynopsis("execution_id from the most recent execute_code call")
        .string()
        .registerToSchema()

    val successRating = InputSchemaElement.param("success_rating")
        .description("Rate the success of the execution from 0.00 (complete failure) to 1.00 (complete success)")
        .cliSynopsis("success rating from 0.00 (fail) to 1.00 (success)")
        .cliMissingHint(
            "missing --success_rating (number 0.00..1.00). Example:\n" +
                "  devrig execute_feedback --project_name=\"<key>\" --task_id=t1 --success_rating=0.9 --explanation=\"...\""
        )
        .number()
        .minimum(0.0)
        .maximum(1.0)
        .required()
        .registerToSchema()

    val explanation = InputSchemaElement.param("explanation")
        .description("Explain why you gave this rating. Provide improvements, suggestions, and critical thinking to improve this tool")
        .cliSynopsis("what worked, what didn't, and what you'll try next")
        .cliMissingHint("missing --explanation. Describe what worked, what didn't, and what you'll try next.")
        .string()
        .required()
        .registerToSchema()

    val code = InputSchemaElement.param("code")
        .description("Optional: The code snippet that was executed. Useful for tracking what code produced which results.")
        .cliSynopsis("code snippet the feedback refers to (optional)")
        .string()
        .cliCodeFileSource()
        .registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectName = context[projectName]
        if (projectName.isBlank()) {
            return ToolCallResult.errorResult("project_name is required (from steroid_list_projects)")
        }
        val taskId = context[taskId]
        if (taskId.isBlank()) {
            return ToolCallResult.errorResult("task_id is required (same id you passed to steroid_execute_code)")
        }
        // Pre-check the raw arg so we can emit the helpful "do NOT send `rating`" hint
        // before the DSL required()-error replaces it with the generic message.
        if (context.params.arguments["success_rating"] == null) {
            return ToolCallResult.errorResult("success_rating is required (number in 0.00..1.00 — do NOT send `rating`)")
        }
        val successRating = context[successRating]
        if (successRating !in 0.0..1.0) {
            return ToolCallResult.errorResult("success_rating=$successRating is out of range (must be 0.00..1.00)")
        }
        val explanation = context[explanation]
        if (explanation.isBlank()) {
            return ToolCallResult.errorResult("explanation is required (free-form: what worked, what didn't, what you'll try next)")
        }
        // execution_id is optional — noted for context but value is not currently used
        val code = context[code]

        val params = FeedbackParams(
            taskId = taskId,
            successRating = successRating,
            explanation = explanation,
            code = code,
            executionBackend = context.executionBackendProvenance(),
        )

        return handler().handleFeedback(projectName, params)
    }
}

@Serializable
data class FeedbackParams(
    val taskId: String,
    val successRating: Double,
    val explanation: String?,
    val code: String?,
    @Transient val executionBackend: ExecutionBackendProvenance? = null,
)

interface ExecuteFeedbackToolHandler {
    suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult
}
