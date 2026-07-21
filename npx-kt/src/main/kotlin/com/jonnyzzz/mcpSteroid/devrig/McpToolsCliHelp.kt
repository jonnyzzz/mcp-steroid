/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.devrigToolSpecs

/**
 * The single source of truth for the devrig `steroid_*`-as-CLI surface: the SAME tool specs the
 * `devrig mcp` stdio server advertises (see [runStubStdioMcpServer] and `DevrigDescriptorParityTest`),
 * built from the shared `devrigToolSpecs(...)` factory. Reused here so the generated CLI help can never
 * list a different set of tools than the server exposes.
 *
 * The handlers are never resolved — [CliToolSpec.cli] and [CliToolSpec.schema] read only metadata — so a
 * throwing stub is safe.
 */
fun devrigCliTools(): List<CliToolSpec> = devrigToolSpecs(HelpOnlyMcpSteroidTools())

/**
 * An [McpSteroidTools] whose handlers are never resolved: it exists only to construct the tool specs so
 * their metadata ([CliToolSpec.cli] / [CliToolSpec.schema]) can be read. A handler is resolved only on
 * [McpTool.call], which reading metadata never triggers, so resolving one signals misuse — fail loudly.
 */
private class HelpOnlyMcpSteroidTools : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T =
        error("handler ${type.name} must not be resolved while generating CLI help")
}

/**
 * Renders the "MCP tools as CLI" block of the global `devrig --help` banner, generated entirely from the
 * tool [CliToolSpec.cli] descriptors and their `asCliParams()` — the fix for PR #272 review r3579479002, which
 * called out that the hand-written block duplicated (and silently diverged from) each tool's metadata.
 *
 * Per tool: a usage line (`devrig <name>` + a token per non-hidden parameter) followed by the curated
 * command synopsis and one short line per parameter. Positional params render as `<name>`, flags as
 * `--flag=<name>` (bare `--flag` for booleans, `<a | b | c>` for enums), optional ones bracketed. Aliases
 * (e.g. `prompt` for `fetch_resource`) trail the usage line.
 */
fun renderMcpToolsCliSection(tools: List<CliToolSpec>): String = buildString {
    appendLine("MCP tools as CLI (same tools as the `devrig mcp` server, callable from the shell):")
    appendLine()
    for (tool in tools.filterNot { it.cli.hidden }) {
        val params = tool.schema.asCliParams().filterNot { it.cliHidden }
        val usage = params.joinToString(" ") { usageToken(it) }
        val aliases = if (tool.cli.aliases.isEmpty()) {
            ""
        } else {
            val label = if (tool.cli.aliases.size == 1) "alias" else "aliases"
            "   ($label: ${tool.cli.aliases.joinToString(", ")})"
        }
        appendLine("  devrig ${tool.cli.name}${if (usage.isEmpty()) "" else " $usage"}$aliases")
        appendLine("      ${tool.cli.synopsis}")
        for (param in params) {
            val label = if (param.cliPositional) "<${param.name}>" else param.cliFlag
            appendLine("        $label  ${flagBlurb(param)}")
        }
        appendLine()
    }
    append(commonCliFlagsFooter())
}

/**
 * A short, FIXED (not per-tool generated) footer documenting the framework/hook flags the generated
 * per-tool blocks intentionally omit: universal `--json`, and the CLI-only affordances `--out`, `--wait`,
 * `--code-file`/stdin. Also notes that `--project_name` is inferred from the current directory when omitted.
 * These flags already work in the current CLI, so this text is honest today.
 */
private fun commonCliFlagsFooter(): String = buildString {
    appendLine("  Common CLI flags (framework-level; not shown per-tool above):")
    appendLine("    --json                         emit the unified {tool, command, isError, data} envelope (every tool)")
    appendLine("    --code-file=<path> | -         execute_code: read the script from a file, or `-` for stdin")
    appendLine("    --out=<path>                   take_screenshot: write the PNG to this path")
    appendLine("    --wait                         open_project: poll until the project is fully opened")
    appendLine("    --project_name is inferred from the current directory when omitted (project-scoped tools).")
    appendLine()
}

/** The usage-line token for [spec]: positional `<name>`, boolean `--flag`, enum `--flag=<a | b | c>`, else `--flag=<name>`; bracketed when optional. */
private fun usageToken(spec: InputSchemaParamSpec): String {
    val enumValues = spec.enumValues
    val core = when {
        spec.cliPositional -> "<${spec.name}>"
        spec.type == "boolean" -> spec.cliFlag
        enumValues != null -> "${spec.cliFlag}=<${enumValues.joinToString(" | ")}>"
        else -> "${spec.cliFlag}=<${spec.name}>"
    }
    // Bracket a flag when the CLI treats it as optional: either not MCP-required, or MCP-required but
    // CLI-optional (`cliOptional`, e.g. cwd-inferred `project_name`, issue #266). `--code` renders as
    // required per its schema; the --code / --code-file / stdin alternatives are documented in
    // printExecuteCodeHelp and the Common CLI flags footer.
    return if (spec.required && !spec.cliOptional) core else "[$core]"
}

/** Short per-parameter help: the curated [InputSchemaParamSpec.cliSynopsis] or, failing that, a trimmed [InputSchemaParamSpec.description]. */
private fun flagBlurb(spec: InputSchemaParamSpec): String =
    spec.cliSynopsis ?: shortDescription(spec.description)

/** Collapses whitespace and clips [description] to its first sentence (or ~72 chars) so a flag stays one line. */
private fun shortDescription(description: String): String {
    val flat = description.replace(Regex("\\s+"), " ").trim()
    val firstSentence = flat.substringBefore(". ", flat)
    return if (firstSentence.length <= 72) firstSentence else firstSentence.take(71).trimEnd() + "…"
}
