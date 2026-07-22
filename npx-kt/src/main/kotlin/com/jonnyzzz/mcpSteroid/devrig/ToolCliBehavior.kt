/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The canonical MCP tool names whose generated CLI command carries CLI-only parse behavior. Kept as
 * literals (matching `ExecuteCodeToolSpec.name` / `ExecuteFeedbackToolSpec.name`) so this module has no
 * dependency edge on the concrete tool classes — the generator consumes `CliToolSpec` metadata only.
 */
const val EXECUTE_CODE_TOOL_NAME: String = "steroid_execute_code"
const val EXECUTE_FEEDBACK_TOOL_NAME: String = "steroid_execute_feedback"

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

/**
 * The CLI-only parse behavior for one generated tool command (issue #284). It registers the tool's
 * CLI-only options (its [ToolCliExtras], which have no MCP-schema parameter) and runs the parse-only
 * validations Clikt's typed grammar cannot express — the `--code`/`--code-file` one-of rule, a positive
 * `--timeout`, and a finite `--success_rating` (a `NaN` slips past a numeric `restrictTo` because every
 * `NaN` comparison is false).
 *
 * These are PARSE-time concerns, raised as a [UsageError] so they ride the parse-error `--json` envelope
 * (exit 64) rather than degrading into a backend tool error. Enum and numeric-range validation stay
 * schema-generated in [SchemaCliBinding]; only rules the schema cannot encode live here. A tool with no
 * such behavior uses [None].
 */
class ToolCliParseBehavior private constructor(
    private val extrasBinder: (CliktCommand) -> () -> ToolCliExtras,
    private val validator: (JsonObject, ToolCliExtras) -> Unit,
) {
    /** Registers this behavior's CLI-only options on [command]; the returned reader yields the parsed [ToolCliExtras]. */
    fun bindExtras(command: CliktCommand): () -> ToolCliExtras = extrasBinder(command)

    /** Runs the parse-only validations against the already-typed [arguments] and the parsed [extras]; throws [UsageError]. */
    fun validate(arguments: JsonObject, extras: ToolCliExtras): Unit = validator(arguments, extras)

    companion object {
        /** The behavior for [toolName], or [None] when a tool needs no CLI-only options or extra validation. */
        fun forTool(toolName: String): ToolCliParseBehavior = when (toolName) {
            EXECUTE_CODE_TOOL_NAME -> ExecuteCode
            EXECUTE_FEEDBACK_TOOL_NAME -> ExecuteFeedback
            else -> None
        }

        val None: ToolCliParseBehavior = ToolCliParseBehavior({ { ToolCliExtras() } }, { _, _ -> })

        private val ExecuteCode: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = codeFileExtrasBinder(),
            validator = { arguments, extras ->
                val code = arguments.stringOrNull("code")
                if (code.isNullOrBlank() && extras.codeFile.isNullOrBlank()) {
                    throw UsageError(
                        "missing code. Pass --code-file=<path> (preferred) or --code=\"...\". Example:\n" +
                            "  devrig execute_code --project_name=\"<key>\" --code-file=repro.kts --task_id=t1 --reason=\"reproduce issue\"",
                    )
                }
                if (!code.isNullOrBlank() && !extras.codeFile.isNullOrBlank()) {
                    throw UsageError("pass only one of --code / --code-file, not both")
                }
                // Reject a non-positive timeout up front: dispatching 0/-1 would start the script and then
                // immediately fail with "timed out after 0 seconds". The schema has no integer minimum and
                // the MCP inputSchema is frozen, so this is a parse extension rather than a schema bound.
                arguments["timeout"]?.jsonPrimitive?.intOrNull?.let {
                    if (it <= 0) throw UsageError("--timeout must be a positive number of seconds (got $it)")
                }
            },
        )

        private val ExecuteFeedback: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = codeFileExtrasBinder(),
            validator = { arguments, extras ->
                if (!arguments.stringOrNull("code").isNullOrBlank() && !extras.codeFile.isNullOrBlank()) {
                    throw UsageError("pass only one of --code / --code-file, not both")
                }
                // restrictTo(0.0, 1.0) rejects every finite out-of-range rating, but NaN passes it (NaN
                // comparisons are all false); reject it here so an unusable rating is a parse error.
                arguments["success_rating"]?.jsonPrimitive?.doubleOrNull?.let {
                    if (it.isNaN()) throw UsageError("--success_rating must be a number in 0.00..1.00")
                }
            },
        )

        /** The `--code-file` option shared by execute_code and execute_feedback; `-` reads the script from stdin at runtime. */
        private fun codeFileExtrasBinder(): (CliktCommand) -> () -> ToolCliExtras = { command ->
            val codeFile = command.option("--code-file", help = "path to a script file; pass \"-\" to read from stdin")
            command.registerOption(codeFile)
            val reader: () -> ToolCliExtras = { ToolCliExtras(codeFile = codeFile.value) }
            reader
        }
    }
}

/** The string content of [key] in this object, or null when the key is absent or not a JSON string. */
fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
