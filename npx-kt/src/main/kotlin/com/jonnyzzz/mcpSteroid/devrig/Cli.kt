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
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
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

    // ---- MCP-as-CLI (epic #188): thin frontends over the existing bridge tool handlers ----

    /** `devrig prompt <uri>` / `devrig fetch_resource --uri=...` — steroid_fetch_resource. */
    data class DevrigCommandFetchResource(
        val uri: String? = null,
        val projectName: String? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig execute_code` — steroid_execute_code. Code comes from --code-file (preferred) or --code. */
    data class DevrigCommandExecuteCode(
        val projectName: String? = null,
        val code: String? = null,
        val codeFile: String? = null,
        val taskId: String? = null,
        val reason: String? = null,
        val modal: String? = null,
        val timeout: Int? = null,
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig list_projects` — steroid_list_projects (reconciled with `devrig project`). */
    data class DevrigCommandListProjects(
        override val debug: Boolean = false,
        override val json: Boolean = false,
    ) : DevrigCommand

    /** `devrig list_windows` — steroid_list_windows. */
    data class DevrigCommandListWindows(
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

    /** `devrig execute_feedback` — steroid_execute_feedback. Optional code via --code-file / --code. */
    data class DevrigCommandFeedback(
        val projectName: String? = null,
        val taskId: String? = null,
        val executionId: String? = null,
        val successRating: Double? = null,
        val explanation: String? = null,
        val code: String? = null,
        val codeFile: String? = null,
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
            // MCP-as-CLI (epic #188) — thin frontends over the existing bridge tool handlers.
            PromptCliCommand(selected, this),
            FetchResourceCliCommand(selected, this),
            ExecuteCodeCliCommand(selected, this),
            ListProjectsCliCommand(selected, this),
            ListWindowsCliCommand(selected, this),
            OpenProjectCliCommand(selected, this),
            ScreenshotCliCommand(selected, this),
            InputCliCommand(selected, this),
            FeedbackCliCommand(selected, this),
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
            uri = uri, projectName = projectName, debug = options.debug, json = options.json,
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
            uri = uri, projectName = projectName, debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig execute_code` — steroid_execute_code. */
private class ExecuteCodeCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("execute_code", selected, parent) {
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`")
    private val codeFile: String? by option("--code-file", help = "path to a Kotlin script file (preferred)")
    private val code: String? by option("--code", help = "inline Kotlin suspend body (alternative to --code-file)")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val modal: String? by option("--modal", help = "smart_non_modal (default) | non_modal | unleashed")
    private val timeout: Int? by option("--timeout", help = "script timeout in seconds (default 600)").int()

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(projectName, "--project_name", "devrig list_projects")
        if (code.isNullOrBlank() && codeFile.isNullOrBlank()) {
            throw UsageError(
                "missing code. Pass --code-file=<path> (preferred) or --code=\"...\". Example:\n" +
                    "  devrig execute_code --project_name=\"<key>\" --code-file=repro.kts --task_id=t1 --reason=\"reproduce issue\""
            )
        }
        if (!code.isNullOrBlank() && !codeFile.isNullOrBlank()) {
            throw UsageError("pass only one of --code / --code-file, not both")
        }
        requireArg(taskId, "--task_id", null)
        requireArg(reason, "--reason", null)
        select(DevrigCommand.DevrigCommandExecuteCode(
            projectName = projectName, code = code, codeFile = codeFile, taskId = taskId,
            reason = reason, modal = modal, timeout = timeout, debug = options.debug, json = options.json,
        ))
    }
}

/** `devrig list_projects [--json]` — steroid_list_projects (shares the `devrig project` render path). */
private class ListProjectsCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("list_projects", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandListProjects(debug = options.debug, json = options.json))
    }
}

/** `devrig list_windows [--json]` — steroid_list_windows. */
private class ListWindowsCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("list_windows", selected, parent) {
    override fun run() {
        val options = options()
        select(DevrigCommand.DevrigCommandListWindows(debug = options.debug, json = options.json))
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
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val windowId: String? by option("--window_id", help = "target window (from `devrig list_windows`)")
    private val out: String? by option("--out", help = "write the PNG to this file path")

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(projectName, "--project_name", "devrig list_projects")
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
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`")
    private val windowId: String? by option("--window_id", help = "target window (from `devrig list_windows`)")
    private val taskId: String? by option("--task_id", help = "groups related calls in audit logs")
    private val reason: String? by option("--reason", help = "full task description")
    private val sequence: String? by option("--sequence", help = "input steps, e.g. \"press:CTRL+P, type:Main, delay:200, press:ENTER\"")

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(projectName, "--project_name", "devrig list_projects")
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

/** `devrig execute_feedback --project_name=... --task_id=... --success_rating=... --explanation=...` */
private class FeedbackCliCommand(
    selected: SelectedDevrigCommand,
    parent: DevrigCliktCommand,
) : DevrigCliktCommand("execute_feedback", selected, parent) {
    private val projectName: String? by option("--project_name", help = "routing key from `devrig list_projects`")
    private val taskId: String? by option("--task_id", help = "the same task_id used for the rated execution")
    private val executionId: String? by option("--execution_id", help = "execution_id from the rated execute_code result")
    private val successRating: Double? by option("--success_rating", help = "0.00 (failure) .. 1.00 (success)").double()
    private val explanation: String? by option("--explanation", help = "what worked, what didn't, what you'll try next")
    private val codeFile: String? by option("--code-file", help = "optional illustrative snippet file")
    private val code: String? by option("--code", help = "optional illustrative snippet (inline)")

    override fun run() {
        val options = options()
        if (options.help) { select(helpFor(options)); return }
        requireArg(projectName, "--project_name", "devrig list_projects")
        requireArg(taskId, "--task_id", null)
        requireArg(explanation, "--explanation", null)
        val rating = successRating
            ?: throw UsageError("missing --success_rating (number 0.00..1.00). Example:\n" +
                "  devrig execute_feedback --project_name=\"<key>\" --task_id=t1 --success_rating=0.9 --explanation=\"...\"")
        if (rating !in 0.0..1.0) {
            throw UsageError("--success_rating=$rating is out of range (must be 0.00..1.00)")
        }
        if (!code.isNullOrBlank() && !codeFile.isNullOrBlank()) {
            throw UsageError("pass only one of --code / --code-file, not both")
        }
        select(DevrigCommand.DevrigCommandFeedback(
            projectName = projectName, taskId = taskId, executionId = executionId,
            successRating = rating, explanation = explanation, code = code, codeFile = codeFile,
            debug = options.debug, json = options.json,
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
            // MCP-as-CLI (epic #188)
            is DevrigCommand.DevrigCommandFetchResource -> runFetchResourceCommand(command)
            is DevrigCommand.DevrigCommandExecuteCode -> runExecuteCodeCommand(command)
            is DevrigCommand.DevrigCommandListProjects -> runListProjectsCommand(command)
            is DevrigCommand.DevrigCommandListWindows -> runListWindowsCommand(command)
            is DevrigCommand.DevrigCommandOpenProject -> runOpenProjectCommand(command)
            is DevrigCommand.DevrigCommandScreenshot -> runScreenshotCommand(command)
            is DevrigCommand.DevrigCommandInput -> runInputCommand(command)
            is DevrigCommand.DevrigCommandFeedback -> runFeedbackCommand(command)
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
