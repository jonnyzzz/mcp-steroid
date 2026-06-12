/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigToolBridgeClient
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolSpec
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.ModalMode
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Exit code of `devrig exec-code` when the routed tool call completed but reported `isError`. */
const val EXEC_CODE_TOOL_ERROR_EXIT_CODE = 1

/** Exit code (sysexits `EX_NOINPUT`) when the `--file` script cannot be read. */
const val EXEC_CODE_FILE_ERROR_EXIT_CODE = 66

/**
 * Exit code (sysexits `EX_UNAVAILABLE`) when no IDE/project is reachable to route the script to:
 * no discovered IDE backends, an unknown `--project` name, an ambiguous raw project name, or the
 * routed IDE dying between the routing snapshot and the tool call (connect/IO failure).
 * Distinct from [EXEC_CODE_TOOL_ERROR_EXIT_CODE] so scripts can tell "the script failed" apart
 * from "there was nothing to run it on".
 */
const val EXEC_CODE_NO_PROJECT_EXIT_CODE = 69

/** Default `reason` recorded when `--reason` is not passed. */
const val EXEC_CODE_DEFAULT_REASON = "devrig exec-code CLI"

/**
 * `devrig exec-code --project <name> --file <script.kt>` — run `steroid_execute_code` from the CLI
 * (issue #100). A CLI front-end over the EXISTING tool surface: no new MCP tool, no new context
 * method, nothing new on the devrig↔plugin wire — the call goes through the very same
 * [DevrigExecuteCodeToolHandler] + [DevrigToolBridgeClient] the MCP path uses, so the forwarded
 * params are byte-identical by construction (pinned in `ExecCodeCommandTest`).
 *
 * Output routing: NDJSON progress lines stream to **stderr** as they arrive; the tool result's
 * text content goes to **stdout** (so `devrig exec-code … > result.txt` works); exit codes are
 * documented on the constants above.
 */
fun DevrigServices.runExecCodeCommand(command: DevrigCommand.DevrigCommandExecCode): Int =
    runExecCodeCommand(
        command = command,
        // Lazy on purpose: the --file read (cheap, fails fast) must happen BEFORE the routing
        // snapshot (marker scan + up to 8 s per IDE), so a typo'd path costs no discovery latency.
        routing = { oneShotProjectRouting() },
        handler = { routing -> DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(routing, mcpHttpClient), beacon) },
        out = mcpStdout,
        err = System.err,
    )

/**
 * One-shot, read-only routing snapshot for the CLI path: the same marker scan + per-IDE
 * `/projects/stream` snapshot fetch `devrig backend` performs (see `cliBackendInventory`), turned
 * into the same [DevrigProjectRoutingService] the MCP handlers route through — so the exposed
 * `project_name`s and the routing rules are identical to `steroid_list_projects` /
 * `steroid_execute_code` in MCP mode. Nothing is cached or persisted (Tenet 3).
 *
 * Unreachable marker IDEs (no snapshot within the timeout) are reported on stderr and excluded:
 * a project cannot be routed to an IDE that does not answer.
 */
internal fun DevrigServices.oneShotProjectRouting(): DevrigProjectRoutingService {
    val states: Map<Long, IdeMonitorState> = runBlocking(Dispatchers.IO) {
        ideDiscovery.scanOnce()
        val ides = ideDiscovery.ides.value.sortedWith(compareBy({ it.marker.ide.name }, { it.pid }))
        collectMarkerSnapshots(
            httpClient = commandHttpClient,
            ides = ides,
            perIdeTimeout = 8.seconds,
            clientInfo = clientInfo,
        ).mapNotNull { row ->
            val projects = row.projects
            if (projects == null) {
                System.err.println(
                    "devrig exec-code: ${row.displayName} (${row.locatorLabel}) is unreachable: " +
                        (row.errorMessage ?: "no snapshot"),
                )
                null
            } else {
                row.ide.pid to IdeMonitorState(ide = row.ide, status = IdeMonitorStatus.CONNECTED, lastSnapshot = projects)
            }
        }.toMap()
    }
    return DevrigProjectRoutingService { states }
}

/**
 * Core of `devrig exec-code`, separated from [DevrigServices] for direct unit testing
 * (same pattern as `runPromptCommand` / `runInstallCheckCommand`).
 *
 * [routing] and [handler] are factories, not instances: the routing snapshot is expensive
 * (marker scan + per-IDE fetch), so it is only taken AFTER the `--file` script has been read —
 * an unreadable file exits with [EXEC_CODE_FILE_ERROR_EXIT_CODE] without touching discovery.
 */
fun runExecCodeCommand(
    command: DevrigCommand.DevrigCommandExecCode,
    routing: () -> DevrigProjectRoutingService,
    handler: (DevrigProjectRoutingService) -> ExecuteCodeToolHandler,
    out: PrintStream,
    err: PrintStream,
): Int {
    val scriptPath = Path.of(command.file)
    val code = try {
        Files.readString(scriptPath)
    } catch (e: IOException) {
        err.println("devrig exec-code: cannot read --file '${command.file}': ${e.message ?: e::class.simpleName}")
        return EXEC_CODE_FILE_ERROR_EXIT_CODE
    }

    val routingService = routing()
    val route = when (val resolution = resolveExecCodeProject(command.project, routingService)) {
        is ExecCodeProjectResolution.Found -> resolution.route
        is ExecCodeProjectResolution.NoBackends -> {
            err.println(
                "devrig exec-code: no IDE backends with the MCP Steroid plugin are reachable; " +
                    "start an IDE (or run 'devrig backend' for the full picture).",
            )
            return EXEC_CODE_NO_PROJECT_EXIT_CODE
        }
        is ExecCodeProjectResolution.Ambiguous -> {
            err.println("devrig exec-code: project '${command.project}' is open in ${resolution.matches.size} IDEs — it matches:")
            resolution.matches.forEach { err.println("  ${it.exposedProjectName}") }
            err.println("Re-run with one of the project_names above (same names 'devrig project' lists).")
            return EXEC_CODE_NO_PROJECT_EXIT_CODE
        }
        is ExecCodeProjectResolution.NotFound -> {
            err.println("devrig exec-code: unknown project '${command.project}'. Available projects:")
            resolution.available.forEach { err.println("  $it") }
            err.println("Run 'devrig project' to refresh the list.")
            return EXEC_CODE_NO_PROJECT_EXIT_CODE
        }
    }

    // Same parameters, same defaults as the steroid_execute_code MCP tool: an unset option sends
    // the exact value the tool schema would have defaulted to, so the IDE sees an identical call.
    val params = ExecCodeParams(
        taskId = command.taskId ?: defaultExecCodeTaskId(scriptPath),
        code = code,
        reason = command.reason ?: EXEC_CODE_DEFAULT_REASON,
        timeout = command.timeout ?: ExecuteCodeToolSpec.DEFAULT_TIMEOUT_SECONDS,
        modal = command.modal ?: ModalMode.DEFAULT,
    )

    val result = try {
        runBlocking(Dispatchers.IO) {
            handler(routingService).executeCode(
                projectName = route.exposedProjectName,
                execCodeParams = params,
                callProgress = object : McpProgressReporter {
                    // NDJSON progress lines stream to stderr as they arrive; stdout stays the result.
                    override fun report(message: String) = err.println(message)
                },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        // The routed IDE died between the routing snapshot and the tool call (connection refused,
        // reset, mid-stream abort). Same "nothing to run it on" contract as an unknown project —
        // a clean message, not a stack trace.
        err.println("devrig exec-code: IDE became unreachable: ${e.message ?: e::class.simpleName}")
        return EXEC_CODE_NO_PROJECT_EXIT_CODE
    }

    for (item in result.content) {
        when (item) {
            is ContentItem.Text -> out.println(item.text)
            is ContentItem.Image ->
                err.println("(image content omitted: ${item.mimeType}, ${item.data.length} base64 chars)")
            is ContentItem.Resource ->
                item.resource.text?.let(out::println)
                    ?: err.println("(resource content omitted: ${item.resource.uri})")
        }
    }
    return if (result.isError) EXEC_CODE_TOOL_ERROR_EXIT_CODE else 0
}

/** Default `task_id` when `--task-id` is not passed: `cli-<file-stem>`. */
internal fun defaultExecCodeTaskId(scriptPath: Path): String {
    val stem = scriptPath.fileName?.toString()?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
    return "cli-${stem ?: "script"}"
}

/** How `--project` resolves against the routing table. */
sealed interface ExecCodeProjectResolution {
    data class Found(val route: ProjectRoute) : ExecCodeProjectResolution

    /** A raw (un-suffixed) project name matched routes in several IDEs — the caller must qualify. */
    data class Ambiguous(val matches: List<ProjectRoute>) : ExecCodeProjectResolution

    /** Nothing matched; [available] are the exposed `project_name`s the user can pick from. */
    data class NotFound(val available: List<String>) : ExecCodeProjectResolution

    /** No IDE with the MCP Steroid plugin is reachable at all. */
    data object NoBackends : ExecCodeProjectResolution
}

/**
 * Resolves `--project` exactly like the MCP path: the routing table is keyed by the devrig-exposed
 * `project_name` (`<name>-<hash8>`, unique by construction — the same names `steroid_list_projects`
 * and `devrig project` surface), so an exact key hit wins outright. As a CLI convenience the raw
 * (un-suffixed) folder name is also accepted when it identifies exactly ONE route; when the same
 * raw name is open in several IDEs there is no preference rule that could pick one (the MCP tool
 * never faces this — agents only ever see exposed names), so it errors listing the candidates.
 */
fun resolveExecCodeProject(
    requested: String,
    routing: DevrigProjectRoutingService,
): ExecCodeProjectResolution {
    val routes = routing.routes()
    if (routes.isEmpty()) return ExecCodeProjectResolution.NoBackends
    routes[requested]?.let { return ExecCodeProjectResolution.Found(it) }
    val byRawName = routes.values.filter { it.originalProjectName == requested }
    return when {
        byRawName.size == 1 -> ExecCodeProjectResolution.Found(byRawName.single())
        byRawName.size > 1 -> ExecCodeProjectResolution.Ambiguous(byRawName)
        else -> ExecCodeProjectResolution.NotFound(routes.keys.toList())
    }
}
