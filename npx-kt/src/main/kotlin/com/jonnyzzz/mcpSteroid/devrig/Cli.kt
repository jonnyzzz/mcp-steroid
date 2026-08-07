/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ContextCliktError
import com.github.ajalt.clikt.core.IncorrectOptionValueCount
import com.github.ajalt.clikt.core.MissingArgument
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.MultiUsageError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.eagerOption
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.rendering.Whitespace
import com.github.ajalt.mordant.rendering.Widget
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.widgets.Text
import com.jonnyzzz.mcpSteroid.aiAgents.AgentCliNotLaunchableException
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."
const val DEVRIG_USAGE_EXIT_CODE: Int = 64

enum class DevrigCliMode(
    val telemetryMode: String?,
    val runsTool: Boolean,
    val isMcp: Boolean = false,
    val selfHealsLauncherOnStart: Boolean,
    val mayPrintHeadliner: Boolean,
) {
    MCP("mcp", runsTool = true, isMcp = true, selfHealsLauncherOnStart = true, mayPrintHeadliner = false),
    GENERATED_TOOL(null, runsTool = true, selfHealsLauncherOnStart = false, mayPrintHeadliner = false),
    BACKEND("backend", runsTool = true, selfHealsLauncherOnStart = true, mayPrintHeadliner = true),
    INSTALL("install", runsTool = true, selfHealsLauncherOnStart = true, mayPrintHeadliner = true),
    INFORMATIONAL(null, runsTool = false, selfHealsLauncherOnStart = true, mayPrintHeadliner = false),
}

class DevrigCliInvocation(
    val commandPath: String,
    val debug: Boolean,
    val json: Boolean,
    val mode: DevrigCliMode,
    val telemetryMode: String? = mode.telemetryMode,
    val jsonEnvelopeCommand: String = "devrig",
    val generatedTool: GeneratedToolInvocation? = null,
    val informationalText: String? = null,
    private val terminal: Terminal?,
    private val action: suspend DevrigServices.() -> Int,
) {
    val selfHealsLauncherOnStart: Boolean
        get() = mode.selfHealsLauncherOnStart

    val printsHeadliner: Boolean
        get() = mode.mayPrintHeadliner && !json

    /** Keep direct library prints away from the stdio protocol and generated JSON document. */
    val keepsSystemOutGuarded: Boolean
        get() = mode.isMcp || (mode == DevrigCliMode.GENERATED_TOOL && json)

    fun renderHeadliner(headliner: String): String {
        if (!printsHeadliner || terminal == null) return headliner
        val lines = headliner.lines()
        val styled = terminal.render((TextStyles.bold + TextColors.brightCyan)(lines.first()))
        return (listOf(styled) + lines.drop(1)).joinToString("\n")
    }

    suspend fun execute(services: DevrigServices): Int = try {
        action(services)
    } catch (e: AgentCliNotLaunchableException) {
        reportAgentCliNotLaunchable(e, System.err)
    }
}

fun parseDevrigCommand(
    rawArgs: Array<String>,
    terminal: Terminal = Terminal(),
): DevrigCliInvocation {
    val selected = SelectedDevrigInvocation()
    selected.rawArgs = rawArgs.toList()
    val jsonRequested = rawArgs.exactJsonRequested()
    val renderingTerminal = if (jsonRequested) Terminal(ansiLevel = AnsiLevel.NONE) else terminal
    val root = DevrigRootCommand(selected, renderingTerminal)
    return try {
        root.parse(rawArgs)
        selected.invocation ?: informationalInvocation(
            root.getFormattedHelp() ?: "devrig help is unavailable",
            debug = rawArgs.debugRequested(),
        )
    } catch (e: CliktError) {
        val exitCode = if (e.statusCode == 0) 0 else DEVRIG_USAGE_EXIT_CODE
        val reported = (e as? UsageError)?.withCuratedMissingHints() ?: e
        val text = root.getFormattedHelp(reported) ?: reported.message ?: "Invalid arguments"
        val failingCommand = reported.failingCommand()
        val jsonError = exitCode != 0 && jsonRequested
        informationalInvocation(
            text,
            exitCode = exitCode,
            error = exitCode != 0 || reported.printError,
            debug = rawArgs.debugRequested(),
            json = jsonError,
            jsonEnvelopeCommand = rawArgs.jsonEnvelopeCommand(failingCommand),
        )
    }
}

private fun CliktError.failingCommand(): CliktCommand? =
    (this as? ContextCliktError)?.context?.command
        ?: (this as? MultiUsageError)?.errors?.firstNotNullOfOrNull { it.failingCommand() }

private fun Array<String>.jsonEnvelopeCommand(failingCommand: CliktCommand?): String {
    if (failingCommand != null) {
        val path = failingCommand.currentContext.commandNameWithParents().drop(1).joinToString(" ")
        return path.ifEmpty { "devrig" }
    }
    val commandToken = takeWhile { it != "--" }.firstOrNull { !it.startsWith("-") } ?: return "devrig"
    return devrigCliTools().firstOrNull { spec ->
        commandToken == spec.cli.name || commandToken in spec.cli.aliases
    }?.cli?.name ?: commandToken
}

private fun UsageError.withCuratedMissingHints(): UsageError {
    val command = context?.command as? SchemaToolCliCommand ?: return this
    return withCuratedMissingHints(command)
}

private fun UsageError.withCuratedMissingHints(command: SchemaToolCliCommand): UsageError {
    if (this is MultiUsageError) {
        return MultiUsageError(errors.map { it.withCuratedMissingHints(command) }).also { it.context = context }
    }
    if (this is IncorrectOptionValueCount) {
        val name = paramName ?: return this
        val negative = command.negativeFlagFor(name) ?: return this
        val positive = command.positiveFlagFor(name) ?: return this
        return UsageError(
            "$name is a switch and takes no value; use $negative to set it false, or $positive to set it true",
            paramName = name,
        ).also { it.context = context }
    }
    if (this !is MissingOption && this !is MissingArgument && this !is MissingCliValue) return this
    val name = paramName ?: return this
    val hint = command.missingHintFor(name) ?: return this
    return UsageError(hint, paramName = name).also { it.context = context }
}

/**
 * Was diagnostics asked for? Read straight off argv (and the env) so logging can be configured before
 * Clikt runs — logback pins its configuration on the first getLogger call, which happens during
 * command-tree construction (#462). [configureLoggingSystemProperties] is the caller with that need.
 */
fun Array<String>.debugRequested(): Boolean = devrigDebugEnvEnabled() || any { it == "--debug" }

/** Exact framework flag only, before the conventional end-of-options marker. Values such as
 * `--reason=--json` are data, not a presentation request. */
private fun Array<String>.exactJsonRequested(): Boolean = takeWhile { it != "--" }.any { it == "--json" }

private fun devrigDebugEnvEnabled(): Boolean = !System.getenv("DEVRIG_DEBUG").isNullOrBlank()

private fun informationalInvocation(
    text: String,
    exitCode: Int = 0,
    error: Boolean = false,
    debug: Boolean = false,
    json: Boolean = false,
    jsonEnvelopeCommand: String = "devrig",
): DevrigCliInvocation = DevrigCliInvocation(
    commandPath = if (exitCode == 0) "help" else "parse-error",
    debug = debug,
    json = json,
    mode = DevrigCliMode.INFORMATIONAL,
    jsonEnvelopeCommand = jsonEnvelopeCommand,
    informationalText = text,
    terminal = null,
) {
    if (json) {
        Presentation.Json().renderError(jsonEnvelopeCommand, text.trimEnd(), exitCode, mcpStdout, System.err)
    } else {
        val output = if (error) System.err else mcpStdout
        output.println(text.trimEnd())
        exitCode
    }
}

class SelectedDevrigInvocation {
    var invocation: DevrigCliInvocation? = null
    var rawArgs: List<String> = emptyList()
}

data class GenericOptions(
    val debug: Boolean,
    val json: Boolean,
)

abstract class DevrigCliktCommand(
    name: String,
    help: String,
    private val selected: SelectedDevrigInvocation,
    private val parent: DevrigCliktCommand?,
    invokeWithoutSubcommand: Boolean = false,
    printHelpOnEmptyArgs: Boolean = false,
    hidden: Boolean = false,
    epilog: String = "",
) : CliktCommand(
    name = name,
    help = help,
    epilog = epilog,
    invokeWithoutSubcommand = invokeWithoutSubcommand,
    printHelpOnEmptyArgs = printHelpOnEmptyArgs,
    hidden = hidden,
) {
    val hiddenFromHelp: Boolean = hidden

    private val debugFlag by option(
        "--debug",
        help = DEVRIG_DEBUG_FLAG_HELP,
    ).flag()

    init {
        context { helpOptionNames = emptySet() }
        eagerOption("--help", "-h", help = "Show this message and exit.") {
            if (!context.errorEncountered) throw PrintHelpMessage(context)
        }
    }

    final override fun run() {
        runCommand()
    }

    protected abstract fun runCommand()

    protected open fun localJson(): Boolean = false

    protected fun commandPath(): String = currentContext.commandNameWithParents().joinToString(" ")

    protected fun options(): GenericOptions {
        val parentOptions = parent?.options()
        return GenericOptions(
            debug = debugFlag || parentOptions?.debug == true || devrigDebugEnvEnabled(),
            json = localJson() || parentOptions?.json == true,
        )
    }

    protected fun select(
        mode: DevrigCliMode,
        supportsJson: Boolean,
        telemetryMode: String? = mode.telemetryMode,
        jsonEnvelopeCommand: String = "devrig",
        generatedTool: GeneratedToolInvocation? = null,
        action: suspend DevrigServices.(json: Boolean) -> Int,
    ) {
        val options = options()
        if (options.json && !supportsJson) {
            throw UsageError("--json is not supported by '${commandPath()}'")
        }
        selected.invocation = DevrigCliInvocation(
            commandPath = commandPath(),
            debug = options.debug,
            json = options.json,
            mode = mode,
            telemetryMode = telemetryMode,
            jsonEnvelopeCommand = jsonEnvelopeCommand,
            generatedTool = generatedTool,
            terminal = currentContext.terminal,
            action = { action(options.json) },
        )
    }

    protected fun rawArgs(): List<String> = selected.rawArgs

    /**
     * Prevent a value-taking option from swallowing one of this command's own flags. The explicit `=`
     * form remains valid data; only a separate next token is treated as a likely omitted value.
     */
    protected fun rejectFlagsConsumedAsValues(valueOptions: Map<String, String>) {
        registerCliParseCheck {
            val knownFlags = registeredOptions().flatMap { it.names + it.secondaryNames }.toSet()
            val args = rawArgs()
            for (index in 0 until args.lastIndex) {
                val option = args[index]
                val paramName = valueOptions[option] ?: continue
                val token = args[index + 1]
                if (token.substringBefore('=') !in knownFlags) continue
                throw UsageError(
                    "'$token' is a devrig flag, not a value; it was read as the value of '$paramName'. " +
                        "Pass a real value for '$paramName' and give '$token' on its own, or bind the literal " +
                        "explicitly as '$option=$token'."
                )
            }
        }
    }
}

abstract class JsonDevrigCliktCommand(
    name: String,
    help: String,
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand?,
    invokeWithoutSubcommand: Boolean = false,
    printHelpOnEmptyArgs: Boolean = false,
    hidden: Boolean = false,
    epilog: String = "",
) : DevrigCliktCommand(
    name = name,
    help = help,
    selected = selected,
    parent = parent,
    invokeWithoutSubcommand = invokeWithoutSubcommand,
    printHelpOnEmptyArgs = printHelpOnEmptyArgs,
    hidden = hidden,
    epilog = epilog,
) {
    private val jsonFlag by option(
        "--json",
        help = DEVRIG_JSON_FLAG_HELP,
    ).flag()

    override fun localJson(): Boolean = jsonFlag
}

/**
 * The base of the GENERATED tool commands: a [DevrigCliktCommand] that accepts `--out` when — and only
 * when — [acceptsOut] says this tool's result can carry an image.
 *
 * `--out` sits here and not on [DevrigCliktCommand] because it redirects the image a tool RESULT carries
 * (the behavior is [renderWithOut]), and no lifecycle verb — `project`, `backend`, `install`, `help`,
 * `version` — ever produces a result at all. But not every tool command produces an image either: only
 * `take_screenshot` (always) and `execute_code` (a script's `logImage` or a dialog-failure screenshot) do,
 * which is what [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec.producesImage] records. So the option is
 * registered per command, gated on that flag, rather than declared once for everything: declared for all it
 * parsed everywhere and was read in one place, so `devrig project --out=/tmp/x.png` (and `devrig
 * list_projects --out=x`) exited having written nothing. Accepting a flag and silently dropping it is the
 * same failure `open_project --wait` had to avoid ([awaitWaitOption] in `GeneratedToolRuntime.kt`): no flag
 * may be accepted and then ignored. The lever differs only because the ownership does — `--wait` is
 * declared by a tool's own metadata that devrig cannot unilaterally withhold, so devrig can only act on it
 * (or fail loudly) at runtime, whereas devrig owns this declaration and can simply not make it. Scoping it
 * wins where it is available: on a command that cannot honour `--out` the refusal is Clikt's own
 * unknown-option error at parse time, and that command's `--help` stops listing it.
 *
 * The cost is that `--out` must now follow its command (`devrig take_screenshot --out=x`, not
 * `devrig --out=x take_screenshot`), which is where it reads correctly anyway.
 */
abstract class DevrigToolCliktCommand(
    name: String,
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand?,
    help: String,
    acceptsOut: Boolean,
    epilog: String = "",
) : JsonDevrigCliktCommand(name = name, help = help, selected = selected, parent = parent, epilog = epilog) {
    private val outFlag =
        if (acceptsOut) option("--out", help = DEVRIG_OUT_FLAG_HELP).path(canBeDir = false).also { registerOption(it) }
        else null

    protected fun outPath(): Path? = outFlag?.value
}

fun toolAliasMap(tools: List<CliToolSpec>): Map<String, List<String>> {
    val pairs = tools
        .filterNot { it.cli.hidden }
        .flatMap { spec -> spec.cli.aliases.map { alias -> alias to spec.cli.name } }
    val duplicates = pairs.map { it.first }.groupBy { it }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "devrig tool alias(es) declared by more than one tool: $duplicates" }
    return pairs.associate { (alias, name) -> alias to listOf(name) }
}

private fun rootEpilog(): String = buildString {
    appendLine("Human output uses terminal colors automatically. JSON output never contains ANSI styling.")
    appendLine()
    appendLine("Environment variables: DEVRIG_DEBUG enables diagnostics and the launcher debugger;")
    appendLine("DEVRIG_JAVA_HOME selects the devrig runtime; DEVRIG_JVM_OPTS adds JVM options,")
    appendLine("for example -Xmx512m.")
    appendLine()
    appendLine("Run `devrig tools` for the MCP-tools-as-CLI reference (written for coding agents),")
    append("or `devrig <command> --help` for one command's full option list.")
}.trimEnd()

class DevrigRootCommand(
    selected: SelectedDevrigInvocation,
    terminal: Terminal = Terminal(),
    private val tools: List<CliToolSpec> = devrigCliTools(),
) : JsonDevrigCliktCommand(
    name = "devrig",
    help = "Give coding agents deterministic access to JetBrains IDE capabilities.",
    selected = selected,
    parent = null,
    invokeWithoutSubcommand = true,
    epilog = rootEpilog(),
) {
    private val versionFlag by option("--version", "-v", help = "Print the devrig version and exit.").flag()

    init {
        context {
            this.terminal = terminal
            helpFormatter = { context ->
                object : MordantHelpFormatter(context) {
                    override fun renderEpilog(epilog: String): Widget =
                        Text(epilog, whitespace = Whitespace.PRE)
                }
            }
            val defaultSuggestor = correctionSuggestor
            correctionSuggestor = { entered, candidates ->
                defaultSuggestor(entered, candidates.filterNot { it == "mpc" })
            }
        }
        val backend = BackendCommand(selected, this)
        val install = InstallCommand(selected, this)
        subcommands(
            McpCommand(selected, this, name = "mcp", hidden = false),
            // Compatibility for registrations created before issue #85. Never advertise this typo.
            McpCommand(selected, this, name = "mpc", hidden = true),
            backend,
            install,
            *schemaToolCliCommands(selected, this, tools).toTypedArray(),
            ToolsCommand(selected, this, tools),
            HelpCommand(selected, this),
            VersionCommand(selected, this),
        )
        val tokens = registeredSubcommandNames() + aliases().keys
        val duplicates = tokens.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "devrig subcommand token(s) declared more than once: $duplicates" }
    }

    override fun aliases(): Map<String, List<String>> = toolAliasMap(tools)

    override fun runCommand() {
        if (versionFlag) {
            if (currentContext.invokedSubcommand != null) {
                throw UsageError("--version cannot be combined with a subcommand; use `devrig version` instead")
            }
            select(DevrigCliMode.INFORMATIONAL, supportsJson = true) { json ->
                printVersion(mcpStdout, json)
            }
        } else if (currentContext.invokedSubcommand == null && options().json) {
            throw UsageError("--json requires a command that advertises structured output")
        }
    }
}

private class McpCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
    name: String,
    hidden: Boolean,
) : DevrigCliktCommand(
    name = name,
    help = "Run the stdio MCP server used by coding agents.",
    selected = selected,
    parent = parent,
    hidden = hidden,
) {
    override fun runCommand() {
        select(DevrigCliMode.MCP, supportsJson = false) {
            error("devrig mcp is served by mainImplMcp, never by execute()")
        }
    }
}

private class InstallCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(
    name = "install",
    help = "Install devrig components or connect devrig to a coding agent.",
    selected = selected,
    parent = parent,
    invokeWithoutSubcommand = true,
    epilog = "Run without a target to inspect agent CLI availability. Use --check on an agent or plugin target for a read-only diagnosis.",
) {
    init {
        context {
            val inheritedTransformer = tokenTransformer
            tokenTransformer = { token ->
                AiAgentCli.entries.firstOrNull { it.binary.equals(token, ignoreCase = true) }?.binary
                    ?: inheritedTransformer(token)
            }
        }
        subcommands(
            InstallAgentCommand(AiAgentCli.CLAUDE, selected, this),
            InstallAgentCommand(AiAgentCli.CODEX, selected, this),
            InstallAgentCommand(AiAgentCli.GEMINI, selected, this),
            InstallConfigCommand(selected, this),
            InstallDevrigCommand(selected, this),
            InstallPluginCommand(selected, this),
        )
    }

    override fun runCommand() {
        select(DevrigCliMode.INFORMATIONAL, supportsJson = true) { json ->
            runInstallOverviewCommand(json)
        }
    }
}

private class InstallAgentCommand(
    private val agent: AiAgentCli,
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand(
    name = agent.binary,
    help = "Register devrig as the mcp-steroid server in ${agent.displayName}.",
    selected = selected,
    parent = parent,
) {
    private val checkFlag by option(
        "--check",
        help = "Diagnose registration and IDE reachability without changing anything; exit 1 on drift.",
    ).flag()

    override fun runCommand() {
        select(DevrigCliMode.INSTALL, supportsJson = false) {
            runInstallCommand(agent = agent, check = checkFlag)
        }
    }
}

private class InstallConfigCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(
    name = "config",
    help = "Print manual mcpServers configuration and agent-specific add commands.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        select(DevrigCliMode.INFORMATIONAL, supportsJson = true) { json ->
            runInstallConfigCommand(json)
        }
    }
}

private class InstallDevrigCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand(
    name = "devrig",
    help = "Re-register devrig's launcher and PATH entry.",
    selected = selected,
    parent = parent,
) {
    // Forward-compatible install-script contract. The running binary remains the source of truth.
    private val installScript: String? by option("--install-script", hidden = true)
    private val jdkHome: String? by option("--jdk-home", hidden = true)

    init {
        rejectFlagsConsumedAsValues(mapOf("--install-script" to "install-script", "--jdk-home" to "jdk-home"))
    }

    override fun runCommand() {
        val compatibilityOptionsSupplied = installScript != null || jdkHome != null
        select(DevrigCliMode.INSTALL, supportsJson = false) {
            if (compatibilityOptionsSupplied) {
                logger<InstallDevrigCommand>().debug(
                    "Accepted the forward-compatible --install-script/--jdk-home contract; " +
                        "the running devrig binary remains the source of truth",
                )
            }
            runInstallDevrigCommand()
        }
    }
}

private class InstallPluginCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand(
    name = "plugin",
    help = "Install MCP Steroid into locally running JetBrains IDEs.",
    selected = selected,
    parent = parent,
) {
    private val checkFlag by option(
        "--check",
        help = "List IDEs that would be asked to install the plugin without showing dialogs.",
    ).flag()

    override fun runCommand() {
        select(DevrigCliMode.INSTALL, supportsJson = false) {
            runInstallPluginCommand(checkFlag)
        }
    }
}

/**
 * `devrig tools` prints the generated "MCP tools as CLI" reference ([renderMcpToolsCliSection]). The
 * reference used to ride as the epilog of root `--help`, where its ~90 lines buried the command index it
 * was appended to; the root banner now stays an index and points here. It is prose for an agent to read,
 * not a result, so `--json` is refused rather than accepted-and-ignored.
 */
private class ToolsCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
    private val tools: List<CliToolSpec>,
) : DevrigCliktCommand(
    name = "tools",
    help = "Print the MCP-tools-as-CLI reference for coding agents.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        select(DevrigCliMode.INFORMATIONAL, supportsJson = false) {
            mcpStdout.println(renderMcpToolsCliSection(tools).trimEnd())
            0
        }
    }
}

private class HelpCommand(
    selected: SelectedDevrigInvocation,
    private val root: DevrigCliktCommand,
) : DevrigCliktCommand(
    name = "help",
    help = "Print root help, or use `devrig help <command>` for focused help.",
    selected = selected,
    parent = root,
) {
    private val requestedPath by argument(
        "command",
        help = "Command path to explain, for example `execute_code` or `backend download`.",
    ).multiple()

    override fun runCommand() {
        if (options().json) throw UsageError("--json is not supported by '${commandPath()}'")
        var target: CliktCommand = root
        for (token in requestedPath) {
            val expanded = target.aliases()[token] ?: listOf(token)
            for (commandName in expanded) {
                val next = target.registeredSubcommands().singleOrNull { it.commandName == commandName }
                if (next == null) {
                    val choices = target.registeredSubcommands()
                        .filterNot { (it as? DevrigCliktCommand)?.hiddenFromHelp == true }
                        .map { it.commandName }
                        .sorted()
                    if (choices.isEmpty()) {
                        throw UsageError(
                            "'${target.commandName}' has no subcommands; unexpected path component '$commandName'",
                        )
                    }
                    throw UsageError(
                        "unknown command path '${requestedPath.joinToString(" ")}'. " +
                            "Choose one of: ${choices.joinToString(", ")}",
                    )
                }
                target = next
            }
        }
        throw PrintHelpMessage(target.currentContext)
    }
}

private class VersionCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(
    name = "version",
    help = "Print the devrig version.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        select(DevrigCliMode.INFORMATIONAL, supportsJson = true) { json ->
            printVersion(mcpStdout, json)
        }
    }
}

private class BackendCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(
    name = "backend",
    help = "Inspect and manage JetBrains IDE backends.",
    selected = selected,
    parent = parent,
    invokeWithoutSubcommand = true,
    epilog = "Run without a subcommand to list reachable and startable backends.",
) {
    init {
        subcommands(
            BackendDownloadCommand(selected, this),
            BackendStartCommand(selected, this),
            BackendStopCommand(selected, this),
            BackendProvisionCommand(selected, this),
        )
    }

    override fun runCommand() {
        select(DevrigCliMode.BACKEND, supportsJson = true) { json ->
            runBackendCommand(json)
        }
    }
}

private abstract class BackendLifecycleCommand(
    name: String,
    help: String,
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(name, help, selected, parent) {
    protected val id: String? by argument(
        "id",
        help = "Backend product/id. Omit it to list valid choices.",
    ).optional()
    protected val version: String? by option(
        "--version",
        help = "Override the IDE version.",
    )

    init {
        rejectFlagsConsumedAsValues(mapOf("--version" to "version"))
    }

    protected fun validateArguments() {
        val selectedId = id ?: return
        if (!isSupportedBackendLifecycleId(selectedId)) {
            throw UsageError(
                "backend id must be a product id, product:version, product-version, or a listed backend id. " +
                    "Run '${commandPath()}' without an id to list valid choices.",
            )
        }
    }
}

private class BackendDownloadCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : BackendLifecycleCommand(
    name = "download",
    help = "List downloadable IDEs, or download and install one backend.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        validateArguments()
        select(DevrigCliMode.BACKEND, supportsJson = true) { json ->
            runBackendDownloadCommand(id = id, version = version, json = json)
        }
    }
}

private class BackendStartCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : BackendLifecycleCommand(
    name = "start",
    help = "List installed IDEs, or start one managed backend.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        validateArguments()
        select(DevrigCliMode.BACKEND, supportsJson = true) { json ->
            runBackendStartCommand(id = id, version = version, json = json)
        }
    }
}

private class BackendStopCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : BackendLifecycleCommand(
    name = "stop",
    help = "List running managed IDEs, or stop one backend.",
    selected = selected,
    parent = parent,
) {
    override fun runCommand() {
        validateArguments()
        select(DevrigCliMode.BACKEND, supportsJson = true) { json ->
            runBackendStopCommand(id = id, version = version, json = json)
        }
    }
}

private class BackendProvisionCommand(
    selected: SelectedDevrigInvocation,
    parent: DevrigCliktCommand,
) : JsonDevrigCliktCommand(
    name = "provision",
    help = "List unprovisioned local IDEs, or print plugin installation guidance for one.",
    selected = selected,
    parent = parent,
) {
    private val id by argument(
        "id",
        help = "Port-discovered backend id such as port-63342. Omit it to list choices.",
    ).optional()

    override fun runCommand() {
        val selectedId = id
        if (selectedId != null && !isSupportedProvisionTargetId(selectedId)) {
            throw UsageError("backend id must be one of the ids listed by 'devrig backend provision'")
        }
        select(DevrigCliMode.BACKEND, supportsJson = true) { json ->
            runBackendProvisionCommand(id = selectedId, json = json)
        }
    }
}

fun printVersion(out: PrintStream, json: Boolean = false): Int {
    val version = DevrigVersionMetadata.getDevrigVersion()
    if (json) {
        out.println(buildJsonObject { put("version", version) })
    } else {
        out.println(version)
    }
    return 0
}
