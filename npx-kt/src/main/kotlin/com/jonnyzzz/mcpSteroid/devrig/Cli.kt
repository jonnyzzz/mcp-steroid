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
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import kotlinx.serialization.json.JsonObject

const val NO_BACKENDS_DETECTED_MESSAGE: String = "No backends detected."

/**
 * The devrig subcommands that are NOT schema-driven `steroid_*`-as-CLI tools: the lifecycle/config verbs,
 * the hidden `mpc` alias of `mcp`, and the nested `backend` verbs. The tool tokens are derived separately
 * from [devrigCliTools] metadata and unioned in [DEVRIG_SUBCOMMAND_NAMES], so adding a tool never needs an
 * edit here (issue #284).
 */
val FIXED_DEVRIG_SUBCOMMAND_NAMES: Set<String> = setOf(
    // `mpc` is the original (mis-spelled) hidden alias of `mcp` — see issue #85.
    "mcp", "mpc", "backend", "project", "install",
    "help", "version",
    // nested `backend` verbs
    "download", "start", "stop", "provision",
)

/**
 * The closed set of every devrig subcommand token. Used to recover the command name from raw tokens when a
 * [CliktError] aborts parsing before a variant's flags are captured — reliable precisely because the
 * grammar is subcommand-first over this fixed set and every global flag is boolean (so an option VALUE
 * never precedes the subcommand). Derived from the canonical tool names + aliases in [devrigCliTools]
 * (the single source of truth for the tool-as-CLI surface) unioned with [FIXED_DEVRIG_SUBCOMMAND_NAMES],
 * so a newly added tool's command name is recovered without editing this list (issue #284).
 */
val DEVRIG_SUBCOMMAND_NAMES: Set<String> =
    FIXED_DEVRIG_SUBCOMMAND_NAMES + devrigCliTools().flatMap { listOf(it.cli.name) + it.cli.aliases }

/**
 * Builds one generic [SchemaToolCliCommand] per visible spec in [tools] (defaulting to the canonical
 * [devrigCliTools] list), in factory order, so adding a tool to `devrigToolSpecs(...)` adds its canonical
 * CLI command with no new command class. A `cli.hidden` spec contributes no command. The commands are
 * parse-only: each selects an inert [DevrigCommand.RunTool] whose runtime resolution happens later in the
 * service layer (issue #284).
 */
fun schemaToolCliCommands(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand?,
    tools: List<CliToolSpec> = devrigCliTools(),
): List<SchemaToolCliCommand> =
    tools.filterNot { it.cli.hidden }.map { SchemaToolCliCommand(it, selected, parent) }

/**
 * Recovers the subcommand name from raw CLI tokens for a parse-error envelope. Prefers the first token
 * that matches a known subcommand ([DEVRIG_SUBCOMMAND_NAMES]); falls back to the first non-flag token
 * (covers an unknown command like `devrig frobnicate`), then to `"devrig"`. Deterministic and free of
 * clikt internals, so it survives clikt version changes.
 */
fun recoverCommandName(rawArgs: Array<String>): String =
    rawArgs.firstOrNull { it in DEVRIG_SUBCOMMAND_NAMES }
        ?: rawArgs.firstOrNull { !it.startsWith("-") }
        ?: "devrig"

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

    // ---- MCP-as-CLI (epic #188): thin frontends over the existing bridge tool handlers ----

    /** `devrig prompt <uri>` / `devrig fetch_resource --uri=...` — steroid_fetch_resource. */
    data class DevrigCommandFetchResource(
        val uri: String? = null,
        val projectName: String? = null,
        /** The alias the user typed ("prompt" or "fetch_resource"); echoed into the `--json` envelope. */
        val commandName: String = "fetch_resource",
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig open_project` — steroid_open_project. `--wait` polls until the project is ready. */
    data class DevrigCommandOpenProject(
        val projectPath: String? = null,
        val taskId: String? = null,
        val reason: String? = null,
        val trustProject: Boolean = true,
        val backendName: String? = null,
        val wait: Boolean = false,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig take_screenshot` — steroid_take_screenshot. `--out` writes the PNG to a file. */
    data class DevrigCommandScreenshot(
        val projectName: String? = null,
        val taskId: String? = null,
        val reason: String? = null,
        val windowId: String? = null,
        val out: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig input` — steroid_input. */
    data class DevrigCommandInput(
        val projectName: String? = null,
        val windowId: String? = null,
        val taskId: String? = null,
        val reason: String? = null,
        val sequence: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /**
     * A parsed-but-inert schema-driven tool command. Clikt has routed, tokenized, and typed every
     * parameter into [arguments], but nothing has executed: parsing touches no service, backend, or
     * handler. Runtime resolves the live `CliToolSpec` by [toolName] and runs it; [commandName] is the
     * token the user typed (canonical name or alias) and is echoed into the `--json` envelope. [extras]
     * carry CLI-only state that has no MCP-schema parameter (see [ToolCliExtras]).
     */
    data class RunTool(
        val toolName: String,
        val commandName: String,
        val arguments: JsonObject,
        val extras: ToolCliExtras = ToolCliExtras(),
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandHelp(
        /** Optional per-command topic (e.g. "execute_code") for layered help; null = the global banner. */
        val topic: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandVersion(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    data class DevrigCommandParseError(
        /** Full formatted help/usage text (printed to stderr in the human, non-`--json` path). */
        val text: String,
        /** Concise one-line message (used as the `--json` error-envelope payload). */
        val message: String = text,
        /** Best-effort subcommand name (first non-flag token) echoed into the `--json` envelope. */
        val commandName: String = "devrig",
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand
}

private val ANSI_ESCAPE_CODES = Regex("\\u001B\\[[0-9;]*m")

/** Removes ANSI SGR colour codes so a formatted-help string can be matched by plain text. */
private fun stripAnsiCodes(text: String): String = ANSI_ESCAPE_CODES.replace(text, "")

fun parseDevrigCommand(rawArgs: Array<String>): DevrigCommand {
    val selected = SelectedDevrigCommand()
    return parseDevrigCommandWithRoot(DevrigRootCommand(selected), selected, rawArgs)
}

/**
 * Parses [rawArgs] against [root], returning the [DevrigCommand] the root's commands selected into
 * [selected], or a [DevrigCommand.DevrigCommandParseError] recovered from the raw tokens when parsing
 * aborts. Split out from [parseDevrigCommand] so a root wired with generated tool commands can reuse the
 * identical parse-error/`--json`-envelope recovery (issue #284).
 */
fun parseDevrigCommandWithRoot(
    root: DevrigCliktCommand,
    selected: SelectedDevrigCommand,
    rawArgs: Array<String>,
): DevrigCommand {
    return try {
        root.parse(rawArgs)
        selected.command ?: DevrigCommand.DevrigCommandHelp()
    } catch (e: CliktError) {
        // The exception aborts parsing before the `--json`/command flags are captured on a variant, so
        // recover both directly from the raw tokens to keep the `--json` error envelope contract intact.
        val formatted = root.getFormattedHelp(e)
        // The formatted help's "Error:" line names the offending flag (clikt colourises it on a TTY, so
        // strip ANSI first — otherwise the line starts with an escape sequence, not "Error:").
        val errorLine = stripAnsiCodes(formatted.orEmpty()).lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Error:") }?.removePrefix("Error:")?.trim()
        // A clikt parameter error (invalid choice, out-of-range, no such option) stores the offending flag
        // in `paramName`, NOT in `message` — its raw `message` omits the flag ("invalid choice: bogus…").
        // Prefer the flag-naming "Error:" line for those so the `--json` envelope identifies the flag. Our
        // own UsageErrors carry no `paramName` and may be multi-line (curated examples), so keep their full
        // `message`.
        val message = if (e is UsageError && !e.paramName.isNullOrBlank()) {
            errorLine ?: e.message?.takeIf { it.isNotBlank() } ?: "Invalid arguments"
        } else {
            e.message?.takeIf { it.isNotBlank() } ?: errorLine ?: "Invalid arguments"
        }
        DevrigCommand.DevrigCommandParseError(
            text = formatted ?: message,
            message = message,
            commandName = recoverCommandName(rawArgs),
            json = rawArgs.any { it == "--json" },
        )
    }
}

class SelectedDevrigCommand {
    var command: DevrigCommand? = null
}

data class GenericOptions(
    val debug: Boolean,
    val json: Boolean,
    val help: Boolean,
)

abstract class DevrigCliktCommand(
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

    /** Select layered help for a specific [topic] (bypasses the generic --help→global-banner rule). */
    protected fun selectHelpTopic(topic: String?) {
        val options = options()
        selected.command = DevrigCommand.DevrigCommandHelp(topic = topic, debug = options.debug, json = options.json)
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
        // These tools are generated from their `CliToolSpec` metadata and dispatched at runtime through
        // the single `RunTool` arm (issue #284); the remaining tools still carry their own command classes
        // until their runtime behaviors are migrated.
        val generatedToolNames = setOf("list_projects", "list_windows", "execute_code", "execute_feedback")
        val generatedTools = schemaToolCliCommands(
            selected, this,
            tools = devrigCliTools().filter { it.cli.name in generatedToolNames },
        )
        subcommands(
            // `mcp` is the canonical, advertised spelling. `mpc` is the original
            // (mis-spelled) subcommand kept as a hidden alias so existing agent
            // registrations that launch `devrig mpc` keep working — see issue #85.
            McpCommand(selected, this, name = "mcp", hidden = false),
            McpCommand(selected, this, name = "mpc", hidden = true),
            backend,
            ProjectCommand(selected, this),
            InstallCommand(selected, this),
            // MCP-as-CLI (epic #188) — thin frontends over the existing bridge tool handlers.
            PromptCliCommand(selected, this),
            FetchResourceCliCommand(selected, this),
            *generatedTools.toTypedArray(),
            OpenProjectCliCommand(selected, this),
            ScreenshotCliCommand(selected, this),
            InputCliCommand(selected, this),
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

// ============================ MCP-as-CLI subcommands (epic #188) ============================
//
// Each command below only PARSES + VALIDATES into a DevrigCommand variant. The actual behavior is
// dispatched by runCli(...) and reuses the existing bridge tool handlers — the CLI never
// reimplements tool logic. Required args are validated here (not via clikt `.required()`) so the
// error messages can carry agent-usable runnable examples.

/** `devrig prompt <uri> [--project_name]` — ergonomic alias for fetch_resource. */
private class PromptCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("prompt", selected, parent) {
    private val uri by argument("uri").optional()
    private val projectName: String? by option("--project_name", help = "resolve IDE-specific content for this project (from `devrig list_projects`); omit for generic docs")

    override fun run() {
        val options = options()
        if (!options.help && uri.isNullOrBlank()) {
            throw UsageError("missing <uri>. Example:\n  ${fetchResourceUsageExample()}")
        }
        select(DevrigCommand.DevrigCommandFetchResource(
            uri = uri, projectName = projectName, commandName = "prompt",
            debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig fetch_resource --uri=<uri> [--project_name]` — canonical steroid_fetch_resource. */
private class FetchResourceCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("fetch_resource", selected, parent) {
    private val uri: String? by option("--uri", help = "the mcp-steroid:// resource URI to fetch")
    private val projectName: String? by option("--project_name", help = "resolve IDE-specific content for this project (from `devrig list_projects`); omit for generic docs")

    override fun run() {
        val options = options()
        if (!options.help && uri.isNullOrBlank()) {
            throw UsageError("missing --uri. Example:\n  devrig fetch_resource --uri=${canonicalResourceEntryPointOrPlaceholder()}")
        }
        select(DevrigCommand.DevrigCommandFetchResource(
            uri = uri, projectName = projectName, commandName = "fetch_resource",
            debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig open_project --project_path=... [--wait]` — steroid_open_project. */
private class OpenProjectCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("open_project", selected, parent) {
    private val projectPath: String? by option("--project_path", help = "absolute path to the project directory")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val trustProject by option("--trust_project", help = "trust the project (skip trust dialog); default true").flag(default = true)
    private val backendName: String? by option("--backend_name", help = "target backend when several IDEs are running (from `devrig backend --json`)")
    private val wait by option("--wait", help = "poll until the project is initialized (no modal, indexing done)").flag()

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(projectPath, "--project_path", null)
        requireArg(taskId, "--task_id", null)
        requireArg(reason, "--reason", null)
        select(DevrigCommand.DevrigCommandOpenProject(
            projectPath = projectPath, taskId = taskId, reason = reason, trustProject = trustProject,
            backendName = backendName, wait = wait, debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig take_screenshot --project_name=... [--out=file.png]` — steroid_take_screenshot. */
private class ScreenshotCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("take_screenshot", selected, parent) {
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`; omit to infer from the current directory")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val windowId: String? by option("--window_id", help = "target window (from `devrig list_windows`)")
    private val out: String? by option("--out", help = "write the PNG to this file path")

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(taskId, "--task_id", null)
        requireArg(reason, "--reason", null)
        select(DevrigCommand.DevrigCommandScreenshot(
            projectName = projectName, taskId = taskId, reason = reason, windowId = windowId,
            out = out, debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig input --project_name=... --window_id=... --sequence=...` — steroid_input. */
private class InputCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("input", selected, parent) {
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`; omit to infer from the current directory")
    private val windowId: String? by option("--window_id", help = "target window (from `devrig list_windows`)")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val sequence: String? by option("--sequence", help = "input steps, e.g. \"press:CTRL+P, type:Main, delay:200, press:ENTER\"")

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(windowId, "--window_id", "devrig list_windows")
        requireArg(taskId, "--task_id", null)
        requireArg(reason, "--reason", null)
        if (sequence.isNullOrBlank()) {
            throw UsageError(
                "missing --sequence. Example:\n" +
                    "  devrig input --project_name=\"<key>\" --window_id=\"<win>\" --task_id=t1 --reason=\"...\" \\\n" +
                    "    --sequence=\"press:CTRL+P, type:Main, delay:200, press:ENTER\""
            )
        }
        select(DevrigCommand.DevrigCommandInput(
            projectName = projectName, windowId = windowId, taskId = taskId, reason = reason,
            sequence = sequence, debug = options.debug, json = options.json,
        ))
    }
}

/**
 * Throws an agent-usable [UsageError] when [value] is null/blank. [nextStep] names the command to
 * run to obtain the value (e.g. `devrig list_projects`) so the error points the agent forward.
 */
private fun requireArg(value: String?, flag: String, nextStep: String?) {
    if (!value.isNullOrBlank()) return
    val hint = nextStep?.let { " (get it from `$it`)" } ?: ""
    throw UsageError("missing required $flag$hint")
}

private fun helpFor(options: GenericOptions): DevrigCommand =
    DevrigCommand.DevrigCommandHelp(debug = options.debug, json = options.json)

private class HelpCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("help", selected, parent) {
    // `devrig help` → global banner; `devrig help <topic>` (e.g. execute_code) → layered per-command help.
    private val topic by argument("topic").optional()

    override fun run() {
        selectHelpTopic(topic)
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
            is DevrigCommand.RunTool -> runGeneratedToolCommand(command)
            is DevrigCommand.DevrigCommandHelp -> printTopicHelp(command.topic, mcpStdout)
            is DevrigCommand.DevrigCommandVersion -> printVersion(mcpStdout)
            is DevrigCommand.DevrigCommandParseError -> {
                // `--json` consumers must be able to parse usage/parse failures too — emit the unified
                // isError envelope; otherwise print the full formatted help to stderr. Exit stays 64.
                if (command.json) {
                    Presentation.Json().renderError(command.commandName, command.message, exit = CliExit.USAGE, out = mcpStdout)
                } else {
                    System.err.println(command.text)
                    CliExit.USAGE
                }
            }
            is DevrigCommand.DevrigCommandBackend -> runBackendCommand(command)
            is DevrigCommand.DevrigCommandBackendDownload -> runBackendDownloadCommand(command)
            is DevrigCommand.DevrigCommandBackendStart -> runBackendStartCommand(command)
            is DevrigCommand.DevrigCommandBackendStop -> runBackendStopCommand(command)
            is DevrigCommand.DevrigCommandBackendProvision -> runBackendProvisionCommand(command)
            is DevrigCommand.DevrigCommandProject -> runProjectCommand(command)
            is DevrigCommand.DevrigCommandInstall -> runInstallCommand(command)
            is DevrigCommand.DevrigCommandInstallDevrig -> runInstallDevrigCommand(command)
            // MCP-as-CLI (epic #188)
            is DevrigCommand.DevrigCommandFetchResource -> runFetchResourceCommand(command)
            is DevrigCommand.DevrigCommandOpenProject -> runOpenProjectCommand(command)
            is DevrigCommand.DevrigCommandScreenshot -> runScreenshotCommand(command)
            is DevrigCommand.DevrigCommandInput -> runInputCommand(command)
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
