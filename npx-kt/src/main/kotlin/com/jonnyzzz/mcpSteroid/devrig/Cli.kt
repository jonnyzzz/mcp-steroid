/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

sealed interface DevrigCommand {
    val debug: Boolean
    val json: Boolean

    data class MCP(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackend(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendDownload(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendStart(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendStop(
        val id: String? = null,
        val version: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandBackendProvision(
        val id: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandProject(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandInstall(
        val agent: AiAgentCli,
        /** Read-only dry-run: report the registration diff + IDE reachability, change nothing. */
        val check: Boolean = false,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * `devrig install devrig` — register devrig's OWN `~/.mcp-steroid/bin` launcher + PATH (NOT an agent).
     * The install scripts call this with every non-trivial parameter explicit: [installScript] (the
     * install-tree launcher the wrapper execs) and [jdkHome] (pinned as `DEVRIG_JAVA_HOME`).
     */
    data class DevrigCommandInstallDevrig(
        val installScript: String? = null,
        val jdkHome: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandHelp(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandVersion(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandParseError(
        val text: String,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand
}

fun parseDevrigCommand(rawArgs: Array<String>): DevrigCommand {
    val selected = SelectedDevrigCommand()
    val root = DevrigRootCommand(selected)
    return try {
        root.parse(rawArgs)
        selected.command ?: DevrigCommand.DevrigCommandHelp()
    } catch (e: CliktError) {
        DevrigCommand.DevrigCommandParseError(root.getFormattedHelp(e) ?: e.message ?: "Invalid arguments")
    }
}

private class SelectedDevrigCommand {
    var command: DevrigCommand? = null
}

private data class GenericOptions(
    val debug: Boolean,
    val json: Boolean,
    val help: Boolean,
)

private abstract class DevrigCliktCommand(
    name: String,
    private val selected: SelectedDevrigCommand,
    private val parent: DevrigCliktCommand?,
    invokeWithoutSubcommand: Boolean = false,
    hidden: Boolean = false,
) : CliktCommand(
    name = name,
    invokeWithoutSubcommand = invokeWithoutSubcommand,
    hidden = hidden,
) {
    // DEVRIG_DEBUG (the env var that also makes the launcher attach a JDWP agent) additionally turns on
    // full debug mode for every command — identical to passing --debug — so the verbose DEBUG logs that
    // explain a debugging session are emitted without also having to pass the flag.
    private val devrigDebugEnv = !System.getenv("DEVRIG_DEBUG").isNullOrBlank()
    private val debugFlag by option("--debug", help = "enable verbose stderr logging (also enabled by the DEVRIG_DEBUG env var)").flag()
    private val jsonFlag by option("--json", help = "emit JSON output where supported").flag()
    private val helpFlag by option("--help", "-h", help = "print help and exit").flag()

    init {
        context {
            helpOptionNames = emptySet()
        }
    }

    protected fun options(): GenericOptions {
        val parentOptions = parent?.options()
        return GenericOptions(
            debug = debugFlag || parentOptions?.debug == true || devrigDebugEnv,
            json = jsonFlag || parentOptions?.json == true,
            help = helpFlag || parentOptions?.help == true,
        )
    }

    protected fun select(command: DevrigCommand) {
        val options = options()
        selected.command = if (options.help) {
            DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json)
        } else {
            command
        }
    }
}

private class DevrigRootCommand(
    selected: SelectedDevrigCommand,
) : DevrigCliktCommand(
    name = "devrig",
    selected = selected,
    parent = null,
    invokeWithoutSubcommand = true,
) {
    private val versionFlag by option("--version", "-v", help = "print the devrig version and exit").flag()

    init {
        val backend = BackendCommand(selected, this)
        subcommands(
            // `mcp` is the canonical, advertised spelling. `mpc` is the original
            // (mis-spelled) subcommand kept as a hidden alias so existing agent
            // registrations that launch `devrig mpc` keep working — see issue #85.
            McpCommand(selected, this, name = "mcp", hidden = false),
            McpCommand(selected, this, name = "mpc", hidden = true),
            backend,
            ProjectCommand(selected, this),
            InstallCommand(selected, this),
            HelpCommand(selected, this),
            VersionCommand(selected, this),
        )
    }

    override fun run() {
        val options = options()
        if (versionFlag) {
            select(DevrigCommand.DevrigCommandVersion(debug = options.debug, json = options.json))
        } else {
            select(DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json))
        }
    }
}

private class McpCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
    name: String,
    hidden: Boolean,
) : DevrigCliktCommand(name, selected, parent, hidden = hidden) {
    override fun run() {
        val options = options()
        select(DevrigCommand.MCP(debug = options.debug, json = options.json))
    }
}

private class ProjectCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("project", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandProject(debug = options.debug, json = options.json))
    }
}

private class InstallCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("install", selected, parent) {
    private val agent by argument("agent")
    // Only meaningful for `install devrig` (the install scripts pass them); rejected for agents below.
    private val installScript: String? by option("--install-script")
    private val jdkHome: String? by option("--jdk-home")
    private val checkFlag by option(
        "--check",
        help = "read-only dry-run: report the registration diff + IDE reachability, change nothing " +
            "(exit 1 if install would change anything)",
    ).flag()

    override fun run() {
        val options = options()
        if (agent == "devrig") {
            if (checkFlag) throw UsageError("--check is only valid for an agent install (claude / codex / gemini)")
            select(DevrigCommand.DevrigCommandInstallDevrig(
                installScript = installScript, jdkHome = jdkHome, debug = options.debug, json = options.json,
            ))
            return
        }
        val target = AiAgentCli.parse(agent)
            ?: throw UsageError("agent must be one of: claude, codex, gemini, devrig")
        if (installScript != null || jdkHome != null) {
            throw UsageError("--install-script / --jdk-home are only valid with 'devrig install devrig'")
        }
        select(DevrigCommand.DevrigCommandInstall(target, check = checkFlag, debug = options.debug, json = options.json))
    }
}

private class HelpCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("help", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json))
    }
}

private class VersionCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("version", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandVersion(debug = options.debug, json = options.json))
    }
}

private class BackendCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("backend", selected, parent, invokeWithoutSubcommand = true) {
    init {
        subcommands(
            BackendDownloadCommand(selected, this),
            BackendStartCommand(selected, this),
            BackendStopCommand(selected, this),
            BackendProvisionCommand(selected, this),
        )
    }

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackend(debug = options.debug, json = options.json))
    }
}

private class BackendDownloadCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("download", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to download")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendDownload(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStartCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("start", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to start")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendStart(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendStopCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("stop", selected, parent) {
    private val id by argument("id").optional()
    private val version by option("--version", help = "IDE version to stop")

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendStop(id = id, version = version, debug = options.debug, json = options.json))
    }
}

private class BackendProvisionCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("provision", selected, parent) {
    private val id by argument("id").optional()

    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandBackendProvision(id = id, debug = options.debug, json = options.json))
    }
}

fun DevrigServices.runCli(command: DevrigCommand): Int {
    return try {
        when (command) {
            is DevrigCommand.MCP -> error("runCli called with DevrigCommand.MCP")
            is DevrigCommand.DevrigCommandHelp -> printHelp(mcpStdout)
            is DevrigCommand.DevrigCommandVersion -> printVersion(mcpStdout)
            is DevrigCommand.DevrigCommandParseError -> {
                System.err.println(command.text)
                64
            }
            is DevrigCommand.DevrigCommandBackend -> runBackendCommand(command)
            is DevrigCommand.DevrigCommandBackendDownload -> runBackendDownloadCommand(command)
            is DevrigCommand.DevrigCommandBackendStart -> runBackendStartCommand(command)
            is DevrigCommand.DevrigCommandBackendStop -> runBackendStopCommand(command)
            is DevrigCommand.DevrigCommandBackendProvision -> runBackendProvisionCommand(command)
            is DevrigCommand.DevrigCommandProject -> runProjectCommand(command)
            is DevrigCommand.DevrigCommandInstall -> runInstallCommand(command)
            is DevrigCommand.DevrigCommandInstallDevrig -> runInstallDevrigCommand(command)
        }
    } catch (e: ManagedBackendLockException) {
        System.err.println(e.message)
        64
    } catch (e: ManagedBackendValidationException) {
        System.err.println(e.message)
        64
    }
}

fun unknownArguments(tokens: List<String>, hint: String? = null): Int {
    System.err.println("Unknown argument(s): ${tokens.joinToString(" ")}")
    hint?.let { System.err.println(it) }
    printHelp(System.err)
    return 64
}
