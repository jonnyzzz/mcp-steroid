package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.cliFileSource
import com.jonnyzzz.mcpSteroid.mcp.cliMissingHint
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
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
    /**
     * Required `project_name` used to dispatch a tool call to an already-open IDE project. It is the
     * unique routing key from steroid_list_projects / `devrig list_projects`, not the raw folder name,
     * and the command line demands it like any other required parameter.
     */
    fun projectName() =
        InputSchemaElement.param("project_name")
            .description(
                "the `project_name` from steroid_list_projects (a unique routing key, NOT the raw " +
                        "folder name). steroid_list_projects returns both `project_name` (the unique key " +
                        "to pass here) and `name` (the raw folder name, informational only); they are not equal."
            )
            .cliSynopsis("routing key from `devrig list_projects`, not the folder name")
            .cliMissingHint("missing --project_name. Get the routing key from `devrig list_projects` (not the folder name).")
            .string()
            .required()

    /** Required `task_id` used to group related executions in audit logs. */
    fun taskId() =
        InputSchemaElement.param("task_id")
            .description(
                "Your task identifier — reuse the same value across related tool calls " +
                        "to group them in audit logs."
            )
            .cliSynopsis("your task id; reuse it across related calls for audit logs")
            .cliMissingHint("missing --task_id. Any string works; reuse it across related calls.")
            .string()
            .required()

    /**
     * `window_id` identifying a specific IDE window — the `windowId` value from a
     * steroid_list_windows entry (#456: camelCase on output, snake_case as this input).
     * Returned un-required: callers chain `.required()` when mandatory (steroid_input)
     * or `.registerToSchema()` directly when optional (steroid_take_screenshot).
     */
    fun windowId() =
        InputSchemaElement.param("window_id")
            .description("Window id identifying the target IDE window — the `windowId` value from a steroid_list_windows entry.")
            .cliSynopsis("window id from `devrig list_windows` to target")
            .string()

    /** Required `reason` string with the audit-log convention: `Reason for $action. Required for audit logs.` */
    fun reason() =
        InputSchemaElement.param("reason")
            .description("Provide the FULL TASK DESCRIPTION of your intent and expected outcomes. " +
                "On subsequent calls, attach what this specific execution aims to achieve. " +
                "This helps us learn and improve. " +
                "Use steroid_execute_feedback to share improvements, suggestions, and feedback."
            )
            .cliSynopsis("your intent and expected outcome, for the audit log")
            .cliMissingHint("missing --reason. Describe your intent and expected outcome for the audit log.")
            .string()
            .required()
}

/**
 * Declares the `--code-file` alternate source shared by the `code` parameter of `execute_code` and
 * `execute_feedback`: the CLI reads the script/snippet from a file (or standard input when the path is
 * `-`) and uses it as `code`. Chain it after `.string()`, alongside `.cliOptional()`.
 */
fun <R> InputSchemaElement<R>.cliCodeFileSource() =
    cliFileSource(flag = "--code-file", synopsis = "path to a script file; pass \"-\" to read from stdin")
