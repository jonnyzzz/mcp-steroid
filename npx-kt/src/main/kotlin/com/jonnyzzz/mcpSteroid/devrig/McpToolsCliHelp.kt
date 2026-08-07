/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec

/**
 * Help for devrig's own `--debug`, stated once and read by both the option declaration and the footer.
 * It names `DEVRIG_DEBUG` because that is the variable [devrigDebugEnvEnabled] actually reads; the banner
 * used to carry a second, hand-written copy of this line naming a `DEBUG` variable that has never existed.
 */
const val DEVRIG_DEBUG_FLAG_HELP: String =
    "enable verbose stderr logging (also enabled by the DEVRIG_DEBUG env var)"

/** Help for devrig's own `--json`, stated once and read by both the option declaration and the footer. */
const val DEVRIG_JSON_FLAG_HELP: String = "emit one machine-readable JSON document where supported"

/** Help for devrig's own `--out`, stated once and read by both the option declaration and the footer. */
const val DEVRIG_OUT_FLAG_HELP: String =
    "write the image the command returns to this path instead of the devrig temp dir"

/** The column no generated help line may pass, so the banner survives a 100-column terminal unwrapped. */
private const val HELP_WIDTH = 100

/**
 * Renders the "MCP tools as CLI" reference that `devrig tools` prints from the tools' own
 * declarations — [CliToolSpec.cli] and the parameters `asCliParams()` exposes — and from nothing else.
 * Adding a tool, a parameter, a file source or a tool-scoped option surfaces here with no edit to this
 * file; conversely, nothing here can describe a flag that the command line does not actually accept.
 *
 * Per tool: a usage line naming every token the parser accepts, the tool's own command synopsis, then one
 * line per accepted flag carrying that flag's own declared synopsis. The trailing footer holds only the
 * facts that belong to no parameter: devrig's own framework flags, split by the scope each one really has
 * (`--debug` everywhere, `--json` where advertised, `--out` on image-producing tool commands — see
 * [DevrigToolCliktCommand]).
 * Nothing may be stated there that the command line does not actually do — the footer once advertised a cwd
 * inference of `project_name` that no code performed, and later listed `--out` as universal while every
 * lifecycle verb ignored it.
 */
fun renderMcpToolsCliSection(tools: List<CliToolSpec>): String = buildString {
    appendLine("MCP tools as CLI (the same tools the `devrig mcp` server exposes, callable from a shell):")
    appendLine()
    for (tool in tools.filterNot { it.cli.hidden }) {
        appendToolBlock(tool)
        appendLine()
    }
    appendLine("  Global CLI flag (accepted by every command, tool and lifecycle alike):")
    appendLine("    --debug       $DEVRIG_DEBUG_FLAG_HELP")
    appendLine("  Accepted by commands that advertise structured output:")
    appendLine("    --json        $DEVRIG_JSON_FLAG_HELP")
    val imageCommands = tools.filterNot { it.cli.hidden }.filter { it.cli.producesImage }.map { it.cli.name }
    appendLine("  Accepted only by ${imageCommands.joinToString(", ")} — the commands whose result carries an image:")
    appendLine("    --out=<path>  $DEVRIG_OUT_FLAG_HELP")
    appendLine("    Run `devrig <command> --help` for one command's full option list.")
}

/** One tool's block: the wrapped usage line, the command synopsis, then the per-flag lines. */
private fun StringBuilder.appendToolBlock(tool: CliToolSpec) {
    val params = tool.schema.asCliParams().filterNot { it.cliHidden }
    appendUsageLine(
        prefix = "  devrig ${tool.cli.name}",
        tokens = params.map { it.usageToken() } +
            tool.cli.extraOptions.map { "[${it.flag}]" } +
            listOfNotNull(if (tool.cli.producesImage) "[--out=<path>]" else null) +
            listOfNotNull(tool.cli.aliases.aliasNote()),
    )
    appendLine("      ${tool.cli.synopsis}")
    // The per-flag detail line keeps the bare `cliFlag` as its aligned label: a boolean's `--flag / --no-flag`
    // pair (36 columns for `--trust_project`) would pad every sibling line and push a long synopsis past
    // HELP_WIDTH. The negative spelling is advertised on the usage line above (see [usageToken]), where it
    // wraps as a whole token instead of stretching the alignment column.
    val entries = params.flatMap { param ->
        listOfNotNull(
            HelpEntry(if (param.cliPositional) "<${param.name}>" else param.cliFlag, param.cliSynopsis),
            param.cliFileSource?.let { HelpEntry(it.flag, it.synopsis) },
        )
    } + tool.cli.extraOptions.map { HelpEntry(it.flag, it.synopsis) }
    val labelWidth = entries.maxOfOrNull { it.label.length } ?: 0
    for (entry in entries) appendLine("        ${entry.label.padEnd(labelWidth)}  ${entry.synopsis}")
}

/** One rendered flag line: how the CLI spells the thing, and what the thing's own declaration says of it. */
private class HelpEntry(val label: String, val synopsis: String)

/**
 * Appends `prefix` followed by [tokens], breaking to a new line — indented under the first token — before
 * a token would pass [HELP_WIDTH]. Wrapping happens between whole tokens and never inside one: a token such
 * as `[--modal=<smart_non_modal | non_modal | unleashed>]` contains spaces but is a single alternation that
 * would read as two flags if it were split.
 */
private fun StringBuilder.appendUsageLine(prefix: String, tokens: List<String>) {
    val indent = " ".repeat(prefix.length + 1)
    var line = StringBuilder(prefix)
    for (token in tokens) {
        if (line.length > prefix.length && line.length + 1 + token.length > HELP_WIDTH) {
            appendLine(line)
            line = StringBuilder(indent).append(token)
        } else {
            line.append(' ').append(token)
        }
    }
    appendLine(line)
}

/**
 * The usage-line spelling of one parameter: a positional as `<name>`, a boolean switch as the pair
 * `--flag / --no-flag` (see [cliFlagLabel]), an
 * enum as `--flag=<a | b | c>`, anything else as `--flag=<name>`. A declared
 * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] is a second way to supply the SAME value, so it renders as an
 * alternation with the direct form.
 *
 * Both branches test `required` alone, and `cliOptional` is deliberately NOT consulted. What the brackets
 * claim is that the INVOCATION is legal without the token — not that some particular layer would let it
 * through.
 *
 * A required parameter that ALSO declares a file source is parenthesized rather than left bare, because its
 * two spellings are alternatives of which exactly one is mandatory: `SchemaCliBinding.parsed()` raises
 * `MissingCliValue` on `value == null && path == null && spec.required`. Such a parameter is `cliOptional`
 * by construction (`ToolSchema.register` enforces the pairing) purely so Clikt stops demanding the direct
 * flag — testing `cliRequired` here would wrongly bracket it as omissible, so `cliOptional` must not weaken
 * the rule above.
 *
 * `CliFileSourceUsageTokenTest` pins both claims by driving the real parser, so the day the binding changes
 * which rule it enforces, the help does not quietly keep promising the old one.
 */
private fun InputSchemaParamSpec.usageToken(): String {
    val values = enumValues
    val direct = when {
        cliPositional -> "<$name>"
        type == "boolean" -> cliFlagLabel
        values != null -> "$cliFlag=<${values.joinToString(" | ")}>"
        else -> "$cliFlag=<$name>"
    }
    val fileSource = cliFileSource ?: return if (required) direct else "[$direct]"
    val alternation = "$direct | ${fileSource.flag}=<path>"
    return if (required) "($alternation)" else "[$alternation]"
}

/**
 * The flag spelling shown in help. A boolean switch is shown as the pair `--flag / --no-flag`, because
 * `false` is reachable only through the negative spelling ([negativeCliFlag]); a banner that named only
 * `--trust_project` hid that half of the switch. Every other parameter shows its single [cliFlag].
 */
private val InputSchemaParamSpec.cliFlagLabel: String
    get() = negativeCliFlag?.let { "$cliFlag / $it" } ?: cliFlag

/** `(alias: prompt)` / `(aliases: a, b)` for a tool that declares any, and null for one that does not. */
private fun List<String>.aliasNote(): String? = when {
    isEmpty() -> null
    size == 1 -> "(alias: ${single()})"
    else -> "(aliases: ${joinToString(", ")})"
}
