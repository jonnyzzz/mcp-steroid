/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonObject

/**
 * A single MCP tool, bundling the metadata and the invocation logic. Replaces the
 * previous parameter-list passed to [McpToolRegistrar.registerTool] so each tool is
 * a self-describing object.
 *
 * It carries only what the MCP wire needs. The CLI projection ([CliToolSpec.cli] and [CliToolSpec.schema])
 * lives on [CliToolSpec], so a plain MCP tool double never has to supply CLI metadata.
 */
interface McpTool {
    val name: String
    val description: String?
    val inputSchema: JsonObject

    suspend fun call(context: ToolCallContext): ToolCallResult
}

/**
 * An [McpTool] that also describes its devrig subcommand. CLI consumers read [cli] and [schema]
 * directly, while MCP clients continue to see only the narrow [McpTool] surface.
 */
interface CliToolSpec : McpTool {
    /** CLI command descriptor for this tool. */
    val cli: CliCommandSpec

    /** The parameter owner, offering the MCP-JSON (`asMcpJson`) and CLI-param (`asCliParams`) projections. */
    val schema: ToolSchema
}

/**
 * Describes how a tool appears as a `devrig` subcommand. This metadata carries no behavior and is not
 * part of the MCP wire protocol.
 */
data class CliCommandSpec(
    /** Subcommand name; default = [McpTool.name] with the `steroid_` prefix stripped. */
    val name: String,
    /** One-line synopsis for CLI help, distinct from the full MCP description. */
    val synopsis: String,
    /** Extra subcommand aliases (e.g. `prompt` for `fetch_resource`). */
    val aliases: List<String> = emptyList(),
    /** Exclude from the CLI if ever needed. */
    val hidden: Boolean = false,
)

/** Derives the default CLI subcommand name from an MCP tool [toolName] by stripping the `steroid_` prefix. */
fun defaultCliName(toolName: String): String = toolName.removePrefix("steroid_")

abstract class McpToolBase : CliToolSpec {
    /** Single owner of the registered parameters, exposing the MCP-JSON and CLI-param projections. */
    final override val schema = ToolSchema()

    protected fun <R> InputSchemaElement<R>.registerToSchema(): InputSchemaElement<R> = schema.register(this)

    final override val inputSchema: JsonObject
        get() = schema.asMcpJson()

    /** One-line CLI synopsis for this tool's `devrig` subcommand, distinct from [description]. */
    protected abstract val cliSynopsis: String

    /** Extra CLI subcommand aliases; empty by default. */
    protected open val cliAliases: List<String> get() = emptyList()

    /** When true the tool is not exposed as a CLI subcommand; false by default. */
    protected open val cliHidden: Boolean get() = false

    override val cli: CliCommandSpec
        get() = CliCommandSpec(
            name = defaultCliName(name),
            synopsis = cliSynopsis,
            aliases = cliAliases,
            hidden = cliHidden,
        )
}


/**
 * Narrow role interface exposed by [McpToolRegistry] for registering a single MCP tool.
 * Callers pass an [McpTool] instance — name/description/schema/handler live on that object.
 */
fun interface McpToolRegistrar {
    fun registerTool(tool: McpTool)
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for registering a single MCP resource.
 */
fun interface McpResourceRegistrar {
    fun registerResource(
        uri: String,
        name: String,
        description: String?,
        mimeType: String,
        contentProvider: () -> String,
    )
}

/**
 * Narrow role interface exposed by [McpPromptRegistry] for registering a single MCP prompt.
 */
fun interface McpPromptRegistrar {
    fun registerPrompt(
        prompt: Prompt,
        renderer: (PromptGetParams) -> PromptGetResult,
    )
}

/**
 * Narrow role interface exposed by [McpResourceRegistry] for reading a resource at runtime.
 * Separate from [McpResourceRegistrar] because tools that *read* resources at call time don't
 * need the ability to register new ones.
 */
fun interface McpResourceReader {
    fun readResource(uri: String): ResourceReadResult?
}
