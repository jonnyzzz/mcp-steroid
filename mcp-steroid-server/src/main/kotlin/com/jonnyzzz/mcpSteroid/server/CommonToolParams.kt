package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string

/**
 * Shared schema-element factories for parameters that recur across multiple `*ToolSpec`s.
 * Each factory returns a fully-built, required [InputSchemaElement]; callers chain
 * `.registerToSchema()` to attach it to their tool's input schema.
 */
object CommonToolParams {
    /** Required `project_name` used to dispatch a tool call to an already-open IDE project. */
    fun projectName() =
        InputSchemaElement.param("project_name")
            .description(
                "the `project_name` from steroid_list_projects (a unique routing key, NOT the raw " +
                        "folder name). steroid_list_projects returns both `project_name` (the unique key " +
                        "to pass here) and `name` (the raw folder name, informational only); they are not equal."
            )
            .string()
            .required()

    /** Required `task_id` used to group related executions in audit logs. */
    fun taskId() =
        InputSchemaElement.param("task_id")
            .description(
                "Your task identifier — reuse the same value across related tool calls " +
                        "to group them in audit logs."
            )
            .string()
            .required()

    /**
     * `window_id` identifying a specific IDE window (from steroid_list_windows).
     * Returned un-required: callers chain `.required()` when mandatory (steroid_input)
     * or `.registerToSchema()` directly when optional (steroid_take_screenshot).
     */
    fun windowId() =
        InputSchemaElement.param("window_id")
            .description("Window id from steroid_list_windows identifying the target IDE window.")
            .string()

    /** Required `reason` string with the audit-log convention: `Reason for $action. Required for audit logs.` */
    fun reason() =
        InputSchemaElement.param("reason")
            .description("Provide the FULL TASK DESCRIPTION of your intent and expected outcomes. " +
                "On subsequent calls, attach what this specific execution aims to achieve. " +
                "This helps us learn and improve. " +
                "Use steroid_execute_feedback to share improvements, suggestions, and feedback."
            )
            .string()
            .required()
}
