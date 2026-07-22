/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec

/**
 * A single `devrig` subcommand generated from a metadata-only [CliToolSpec]. It PARSES ONLY: Clikt owns
 * routing and tokenization, and [run] selects an inert [DevrigCommand.RunTool]. The command and tool
 * names come from [spec] metadata ([CliToolSpec.cli] / [CliToolSpec.name]); the tool's handler, and any
 * service or backend, are never touched during parsing. Runtime execution belongs to the service layer,
 * which resolves the live spec by [DevrigCommand.RunTool.toolName] (issue #284).
 *
 * Each non-hidden schema parameter is a typed [SchemaCliBinding]: Clikt converts the token once and the
 * binding serializes the already-typed value directly into [DevrigCommand.RunTool.arguments], so nothing
 * is re-parsed downstream.
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
    private val bindings: List<SchemaCliBinding> = SchemaCliBinding.bindAll(this, spec.schema.asCliParams())

    override fun run() {
        val options = options()
        select(
            DevrigCommand.RunTool(
                toolName = spec.name,
                commandName = spec.cli.name,
                arguments = bindings.toJsonObject(),
                debug = options.debug,
                json = options.json,
            )
        )
    }
}
