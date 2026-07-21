/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * A single `devrig` subcommand generated from a metadata-only [CliToolSpec]. It PARSES ONLY: Clikt owns
 * routing and tokenization, and [run] selects an inert [DevrigCommand.RunTool]. The command and tool
 * names come from [spec] metadata ([CliToolSpec.cli] / [CliToolSpec.name]); the tool's handler, and any
 * service or backend, are never touched during parsing. Runtime execution belongs to the service layer,
 * which resolves the live spec by [DevrigCommand.RunTool.toolName].
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
    override fun run() {
        val options = options()
        select(
            DevrigCommand.RunTool(
                toolName = spec.name,
                commandName = spec.cli.name,
                arguments = JsonObject(emptyMap()),
                debug = options.debug,
                json = options.json,
            )
        )
    }
}
