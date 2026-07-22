/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.jonnyzzz.mcpSteroid.server.ModalMode
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
const val VISION_INPUT_TOOL_NAME: String = "steroid_input"
const val VISION_SCREENSHOT_TOOL_NAME: String = "steroid_take_screenshot"
const val OPEN_PROJECT_TOOL_NAME: String = "steroid_open_project"
const val FETCH_RESOURCE_TOOL_NAME: String = "steroid_fetch_resource"

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
 * The CLI-only parse behavior for one generated tool command (issue #284). It carries three tool-local
 * concerns the generic schema binding cannot: the tool's CLI-only options (its [ToolCliExtras], which have
 * no MCP-schema parameter); the parse-only validations Clikt's typed grammar cannot express (the
 * `--code`/`--code-file` one-of rule, a positive `--timeout`); and the curated, agent-facing error wording
 * — runnable-example missing-required messages ([missingRequiredMessage]) plus the `--modal` valid-set and
 * `--success_rating` range messages, which are raised here from [cliValidatedParams] so the agent sees the
 * curated help instead of Clikt's terse default.
 *
 * These are all PARSE-time concerns, raised as a [UsageError] so they ride the parse-error `--json`
 * envelope (exit 64) rather than degrading into a backend tool error. A parameter listed in
 * [cliValidatedParams] has its generated Clikt `choice`/`restrictTo` suppressed in [SchemaCliBinding] so
 * the same value is not validated twice with conflicting wording. A tool with no such behavior uses [None].
 */
class ToolCliParseBehavior private constructor(
    private val extrasBinder: (CliktCommand) -> () -> ToolCliExtras,
    private val validator: (JsonObject, ToolCliExtras) -> Unit,
    private val missingRequiredMessages: Map<String, String> = emptyMap(),
    /**
     * Schema parameters this behavior validates itself with a curated [UsageError] (e.g. `modal`,
     * `success_rating`). Their generated Clikt `choice`/`restrictTo` is suppressed in [SchemaCliBinding] so
     * validation is not doubled and the agent reads the curated valid-set / range wording, not Clikt's
     * terser default. Validation strength is unchanged — the curated check covers the same value set.
     */
    val cliValidatedParams: Set<String> = emptySet(),
) {
    /** Registers this behavior's CLI-only options on [command]; the returned reader yields the parsed [ToolCliExtras]. */
    fun bindExtras(command: CliktCommand): () -> ToolCliExtras = extrasBinder(command)

    /** Runs the parse-only validations against the already-typed [arguments] and the parsed [extras]; throws [UsageError]. */
    fun validate(arguments: JsonObject, extras: ToolCliExtras): Unit = validator(arguments, extras)

    /**
     * The curated, agent-facing "missing required [paramName]" message (a runnable `devrig …` example or a
     * "get it from `devrig …`" hint), or null when the tool wants the generic `missing required <flag>`
     * default. Kept near the tool so a generic tool's missing-required message stays plain.
     */
    fun missingRequiredMessage(paramName: String): String? = missingRequiredMessages[paramName]

    companion object {
        /** The behavior for [toolName], or [None] when a tool needs no CLI-only options or extra validation. */
        fun forTool(toolName: String): ToolCliParseBehavior = when (toolName) {
            EXECUTE_CODE_TOOL_NAME -> ExecuteCode
            EXECUTE_FEEDBACK_TOOL_NAME -> ExecuteFeedback
            VISION_SCREENSHOT_TOOL_NAME -> Screenshot
            VISION_INPUT_TOOL_NAME -> Input
            OPEN_PROJECT_TOOL_NAME -> OpenProject
            FETCH_RESOURCE_TOOL_NAME -> FetchResource
            else -> None
        }

        val None: ToolCliParseBehavior = ToolCliParseBehavior({ { ToolCliExtras() } }, { _, _ -> })

        private val ExecuteCode: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = codeFileExtrasBinder(),
            cliValidatedParams = setOf("modal"),
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
                // --modal is validated here (its schema `choice` is suppressed via cliValidatedParams) so a
                // bad value gets the curated valid-set message on the parse-error envelope, naming the flag
                // and listing every wire value, rather than Clikt's terser default.
                arguments.stringOrNull("modal")?.let { wire ->
                    if (ModalMode.entries.none { it.wire == wire }) {
                        throw UsageError(
                            "invalid --modal '$wire'. Valid: ${ModalMode.entries.joinToString(" | ") { it.wire }}",
                        )
                    }
                }
            },
        )

        private val ExecuteFeedback: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = codeFileExtrasBinder(),
            cliValidatedParams = setOf("success_rating"),
            missingRequiredMessages = mapOf(
                "success_rating" to "missing --success_rating (number 0.00..1.00). Example:\n" +
                    "  devrig execute_feedback --project_name=\"<key>\" --task_id=t1 --success_rating=0.9 --explanation=\"...\"",
            ),
            validator = { arguments, extras ->
                if (!arguments.stringOrNull("code").isNullOrBlank() && !extras.codeFile.isNullOrBlank()) {
                    throw UsageError("pass only one of --code / --code-file, not both")
                }
                // --success_rating range is validated here (its schema `restrictTo` is suppressed via
                // cliValidatedParams) so the curated range+example wording rides the envelope and names the
                // flag. The check also catches NaN/Infinity — every NaN comparison is false, so NaN is never
                // "in" the range, and an infinite value falls outside it.
                arguments["success_rating"]?.jsonPrimitive?.doubleOrNull?.let {
                    if (it !in 0.0..1.0) {
                        throw UsageError("--success_rating=$it is out of range (must be 0.00..1.00)")
                    }
                }
            },
        )

        /**
         * `input`'s curated missing-required wording: `--window_id` points forward to `devrig list_windows`,
         * and `--sequence` shows a full runnable example. It has no CLI-only options and no extra validation
         * — devrig is not a second source of truth for input syntax, so an unknown step is forwarded raw.
         */
        private val Input: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = { { ToolCliExtras() } },
            validator = { _, _ -> },
            missingRequiredMessages = mapOf(
                "window_id" to "missing required --window_id (get it from `devrig list_windows`)",
                "sequence" to "missing --sequence. Example:\n" +
                    "  devrig input --project_name=\"<key>\" --window_id=\"<win>\" --task_id=t1 --reason=\"...\" \\\n" +
                    "    --sequence=\"press:CTRL+P, type:Main, delay:200, press:ENTER\"",
            ),
        )

        /**
         * `fetch_resource`'s curated missing-`--uri` message, with a runnable example built from a live
         * canonical entry-point URI (never a literal). The `prompt` positional-`<uri>` alias keeps its own
         * curated missing-`<uri>` message in [PromptCliCommand].
         */
        private val FetchResource: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = { { ToolCliExtras() } },
            validator = { _, _ -> },
            missingRequiredMessages = mapOf(
                "uri" to "missing --uri. Example:\n  devrig fetch_resource --uri=${canonicalResourceEntryPointOrPlaceholder()}",
            ),
        )

        /**
         * `take_screenshot`'s only CLI-only extra: `--out`, the file path the decoded PNG is written to
         * at runtime ([ToolCliExtras.out]). It has no MCP-schema parameter, so it rides in the extras.
         */
        private val Screenshot: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = { command ->
                val out = command.option("--out", help = "write the PNG to this file path")
                command.registerOption(out)
                val reader: () -> ToolCliExtras = { ToolCliExtras(out = out.value) }
                reader
            },
            validator = { _, _ -> },
        )

        /**
         * `open_project`'s only CLI-only extra: `--wait` ([ToolCliExtras.wait]), which makes the runtime
         * poll `list_windows` until the project is initialized before rendering one final envelope. It is a
         * CLI orchestration toggle with no MCP-schema parameter.
         */
        private val OpenProject: ToolCliParseBehavior = ToolCliParseBehavior(
            extrasBinder = { command ->
                val wait = command.option(
                    "--wait", help = "poll until the project is initialized (no modal, indexing done)",
                ).flag()
                command.registerOption(wait)
                val reader: () -> ToolCliExtras = { ToolCliExtras(wait = wait.value) }
                reader
            },
            validator = { _, _ -> },
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
