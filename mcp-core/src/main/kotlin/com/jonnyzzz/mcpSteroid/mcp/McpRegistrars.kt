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
 * The value shape a CLI frontend must parse for a [CliExtraOption]. Holds only the shapes a declared
 * extra option actually uses: a value-taking shape gets a constant when — and only when — some tool
 * declares one, so the CLI's parsing branch and the declaration that needs it always arrive together
 * and no branch is written against a shape nothing declares.
 */
enum class CliOptionType {
    /** A switch: present or absent, no argument. */
    BOOLEAN,
}

/**
 * A CLI option scoped to one tool's subcommand that is **not** one of the tool's inputs: the CLI acts
 * on it itself — orchestrating around the call, e.g. polling the IDE after the tool has returned — and
 * never sends it to the tool. It lives on [CliCommandSpec], not on [ToolSchema], precisely because it
 * is not a parameter: there is no value for the tool to receive.
 */
data class CliExtraOption(
    /** The CLI option, e.g. `--wait`. */
    val flag: String,
    /** What the CLI must parse for [flag]. */
    val type: CliOptionType,
    /** Short one-line help for [flag]. */
    val synopsis: String,
)

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
    /** Tool-scoped CLI options the CLI handles itself rather than passing to the tool; see [CliExtraOption]. */
    val extraOptions: List<CliExtraOption> = emptyList(),
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

    /**
     * Tool-scoped CLI options the CLI acts on itself instead of sending to the tool (see
     * [CliExtraOption]); none by default. Declared here rather than on [schema] because they are not
     * parameters — the tool never receives their value.
     */
    protected open val cliExtraOptions: List<CliExtraOption> get() = emptyList()

    override val cli: CliCommandSpec
        get() = CliCommandSpec(
            name = defaultCliName(name),
            synopsis = cliSynopsis,
            aliases = cliAliases,
            hidden = cliHidden,
            extraOptions = cliExtraOptions,
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
