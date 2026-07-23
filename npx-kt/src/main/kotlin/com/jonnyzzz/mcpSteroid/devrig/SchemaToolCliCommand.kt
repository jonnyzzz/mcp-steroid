/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.UsageError
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single `devrig` subcommand generated from a metadata-only [CliToolSpec]. It PARSES ONLY: Clikt owns
 * routing and tokenization, and [run] selects an inert [DevrigCommand.RunTool]. The command and tool
 * names come from [spec] metadata ([CliToolSpec.cli] / [CliToolSpec.name]); the tool's handler, and any
 * service or backend, are never touched during parsing. Runtime execution belongs to the service layer,
 * which resolves the live spec by [DevrigCommand.RunTool.toolName].
 *
 * Each non-hidden schema parameter is a typed [SchemaCliBinding]: Clikt converts the token once and the
 * binding serializes the already-typed value directly into [DevrigCommand.RunTool.arguments], so nothing
 * is re-parsed downstream. MCP-required parameters are bound WITHOUT Clikt `.required()`
 * (`optionalizeRequired = true`) so `--help` short-circuits before finalization would abort on a missing
 * required arg; [run] re-checks their presence after that short-circuit and raises a [UsageError].
 * CLI-only inputs with no schema parameter (`--code-file`) and the parse-only rules the schema cannot
 * encode live in the tool's [ToolCliParseBehavior].
 */
class SchemaToolCliCommand(
    private val spec: CliToolSpec,
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand?,
) : DevrigCliktCommand(
    name = spec.cli.name,
    selected = selected,
    parent = parent,
    hidden = spec.cli.hidden,
) {
    private val behavior: ToolCliParseBehavior = ToolCliParseBehavior.forTool(spec.name)
    private val bindings: List<SchemaCliBinding> = SchemaCliBinding.bindAll(
        this, spec.schema.asCliParams(), optionalizeRequired = true, cliValidatedParams = behavior.cliValidatedParams,
    )
    private val readExtras: () -> ToolCliExtras = behavior.bindExtras(this)

    override fun run() {
        val options = options()
        // Layered per-command help (e.g. `devrig execute_code --help` → the execute_code topic); an
        // unregistered topic falls back to the global banner in printTopicHelp.
        if (options.help) { selectHelpTopic(spec.cli.name); return }

        val arguments = bindings.toJsonObject()
        val extras = readExtras()
        // Parse-only rules Clikt's typed grammar can't express, then MCP-required presence (checked here,
        // after the --help short-circuit, since the bindings are optionalized). Both raise a UsageError.
        behavior.validate(arguments, extras)
        requireBoundParams(arguments)

        select(
            DevrigCommand.RunTool(
                toolName = spec.name,
                commandName = spec.cli.name,
                arguments = arguments,
                extras = extras,
                debug = options.debug,
                json = options.json,
            )
        )
    }

    /**
     * Fails when an MCP-required, non-CLI-optional parameter is absent from the parsed [arguments]. The
     * tool's [ToolCliParseBehavior] may supply a curated, agent-facing message (a runnable example or a
     * "get it from `devrig …`" hint) for the parameter; a generic tool falls back to the plain default.
     */
    private fun requireBoundParams(arguments: JsonObject) {
        for (binding in bindings) {
            val param = binding.spec
            val value = arguments[param.name]
            val missing = value == null ||
                (param.type == "string" && value.jsonPrimitive.contentOrNull?.isBlank() != false)
            if (param.required && !param.cliOptional && missing) {
                throw UsageError(behavior.missingRequiredMessage(param.name) ?: "missing required ${param.cliToken()}")
            }
        }
    }

    private fun InputSchemaParamSpec.cliToken(): String = if (cliPositional) "<$name>" else cliFlag
}
