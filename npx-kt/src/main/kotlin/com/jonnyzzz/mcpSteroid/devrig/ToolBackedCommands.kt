/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.CwdProjectMatch
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.devrig.server.callToolViaSpec
import com.jonnyzzz.mcpSteroid.devrig.server.resolveProjectFromCwd
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpec
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolSpec
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolSpec
import com.jonnyzzz.mcpSteroid.server.devrigToolSpecs
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The MCP tool names whose CLI face renders its structured response directly (as the `--json` envelope
 * `data`, and a dedicated human table) rather than the generic `{content:[...]}` tool-result envelope.
 * They mirror `ListProjectsToolSpec.name` / `ListWindowsToolSpec.name` — the two tools with no per-call
 * argument or content shape (issue #284).
 */
private const val LIST_PROJECTS_TOOL_NAME = "steroid_list_projects"
private const val LIST_WINDOWS_TOOL_NAME = "steroid_list_windows"

/**
 * `devrig` subcommands that map 1:1 onto a bridge tool handler and return a [ToolCallResult].
 *
 * The handlers are resolved from an [McpSteroidTools] — in production [StubMcpSteroidTools], the SAME
 * wiring the `devrig mcp` stdio proxy uses, so the CLI never reimplements tool logic. Each command takes
 * `tools` as a defaulted parameter purely so tests can inject a fake and assert the args→`*Params`→render
 * glue without a live IDE (payload→wire mapping is covered by DevrigToolBridgeClientTest).
 */

/**
 * The single error-mapping pipeline for every tool-backed command: runs [call] against [tools], turning
 * the frozen failure modes into the frozen exit codes + agent-usable enveloped messages
 * ([ProjectRouteNotFoundException] → USAGE 64 with a `list_projects` hint, [CancellationException]
 * rethrown, [IllegalArgumentException] → USAGE 64, any other failure → UNAVAILABLE 69). On success the
 * [ToolCallResult] is handed to [renderSuccess] to produce the exit code, so a command can either render
 * the generic result envelope or a byte-compatible per-command projection.
 */
private inline fun DevrigServices.dispatchToolCall(
    commandName: String,
    presentation: Presentation,
    tools: McpSteroidTools,
    crossinline call: suspend (McpSteroidTools) -> ToolCallResult,
    renderSuccess: (ToolCallResult) -> Int,
): Int {
    val result = try {
        runBlocking(Dispatchers.IO) { call(tools) }
    } catch (e: ProjectRouteNotFoundException) {
        return presentation.renderError(
            commandName,
            "${e.message} — run `devrig list_projects` to see valid project_name keys",
            CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        return presentation.renderError(
            commandName, "devrig $commandName: ${e.message}", CliExit.USAGE, mcpStdout,
        )
    } catch (e: Exception) {
        return presentation.renderError(
            commandName, "devrig $commandName failed to reach a backend: ${e.message}",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    return renderSuccess(result)
}

/**
 * Runs [block] against [tools] through [dispatchToolCall], then renders the [ToolCallResult] through the
 * generic [presentation] result envelope.
 */
private inline fun DevrigServices.runToolCall(
    commandName: String,
    presentation: Presentation,
    tools: McpSteroidTools,
    crossinline block: suspend (McpSteroidTools) -> ToolCallResult,
): Int = dispatchToolCall(commandName, presentation, tools, block) { result ->
    presentation.render(result, command = commandName, out = mcpStdout)
}

/** Builds this command's [Presentation] once from its `--json` flag; console images go to [HomePaths.screenshotTmpDir]. */
private fun DevrigServices.presentationFor(json: Boolean): Presentation =
    presentationFor(json, homePaths::screenshotTmpDir)

/**
 * Signals a bad `--code-file`. [exit] distinguishes a fixable-input mistake ([CliExit.USAGE] — missing
 * file, non-regular file, malformed path string) from a genuine read failure ([CliExit.IO_ERROR] — the
 * path resolves and is a regular file but reading it throws, e.g. a permission denial).
 */
private class CodeArgException(message: String, val exit: Int) : RuntimeException(message)

/** Reads inline `--code` or the `--code-file` path; throws [CodeArgException] on a bad/unreadable file. */
private fun resolveCodeArg(inline: String?, file: String?): String {
    if (!inline.isNullOrBlank()) return inline
    val resolvedFile = file
        ?: throw CodeArgException("provide --code or --code-file", CliExit.USAGE)
    val path = try {
        Path.of(resolvedFile)
    } catch (e: InvalidPathException) {
        throw CodeArgException("--code-file is not a valid path: $resolvedFile (${e.reason})", CliExit.USAGE)
    }
    if (!Files.isRegularFile(path)) {
        throw CodeArgException("--code-file not found or not a regular file: $path", CliExit.USAGE)
    }
    return try {
        Files.readString(path)
    } catch (e: IOException) {
        throw CodeArgException("--code-file could not be read: $path (${e.message})", CliExit.IO_ERROR)
    }
}

/** Renders a [CodeArgException] (bad --code-file, missing/ambiguous --project_name, …) via [Presentation]. */
private fun Presentation.renderCodeArgFailure(command: String, e: CodeArgException, out: PrintStream): Int =
    renderError(command, e.message!!, e.exit, out)

/**
 * Effective `--project_name` for the four project-scoped commands (issue #266): [explicit] wins outright
 * when non-blank; otherwise infers from [cwd] via [resolveProjectFromCwd] against [routes]. Throws
 * [CodeArgException] ([CliExit.USAGE]) when inference finds no single containing project — [routes]'
 * exposed names are listed so the agent knows what to pass explicitly instead of guessing.
 *
 * [cwd] and [routes] are parameters (not read from [DevrigServices] directly) purely so tests can drive
 * inference deterministically without a live IDE; production call sites default them to the real launch
 * directory (`user.dir`) and [DevrigServices.projectRouting].
 */
private fun requireProjectName(explicit: String?, cwd: Path, routes: List<ProjectRoute>): String {
    if (!explicit.isNullOrBlank()) return explicit
    return when (val match = resolveProjectFromCwd(cwd, routes)) {
        is CwdProjectMatch.One -> {
            System.err.println(
                "devrig: --project_name omitted; inferred '${match.route.exposedProjectName}' from cwd ($cwd)",
            )
            match.route.exposedProjectName
        }
        is CwdProjectMatch.None, is CwdProjectMatch.Ambiguous -> {
            val candidates = routes.map { it.exposedProjectName }
            val listing = if (candidates.isEmpty()) {
                "no projects are currently open"
            } else {
                "open projects: ${candidates.joinToString(", ")}"
            }
            throw CodeArgException(
                "missing --project_name: the current directory ($cwd) does not uniquely match one open " +
                    "project ($listing). Pass --project_name explicitly (get it from `devrig list_projects`).",
                CliExit.USAGE,
            )
        }
    }
}

// ----------------------------------- input -----------------------------------

/**
 * `steroid_input` runtime behavior (issue #284): generic schema mapping plus cwd `project_name`
 * inference. The sequence is forwarded verbatim — [VisionInputToolSpec] is constructed with
 * `parseSequence = false` so devrig never rejects a step this (possibly older) parser doesn't recognise;
 * the target plugin parses `rawSequence` with its own version. Stack-frame noise is stripped ONLY from an
 * error result after the call ([sanitizeErrorContent]).
 */
private fun DevrigServices.runInputBehavior(
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools,
    cwd: Path,
    routes: List<ProjectRoute>,
): Int {
    val presentation = presentationFor(command.json)
    val projectName = try {
        requireProjectName(command.arguments.stringOrNull("project_name"), cwd, routes)
    } catch (e: CodeArgException) {
        return presentation.renderCodeArgFailure(command.commandName, e, mcpStdout)
    }
    val arguments = buildJsonObject {
        command.arguments.forEach { (key, value) -> put(key, value) }
        put("project_name", projectName)
    }
    return runToolCall(command.commandName, presentation, tools) { t ->
        val result = callToolViaSpec(
            VisionInputToolSpec(parseSequence = false) { t.handler<VisionInputToolHandler>() },
            arguments,
            stderrProgressReporter(),
        )
        if (result.isError) result.sanitizeErrorContent() else result
    }
}

/** Returns a copy with every Text content item's stack-frame noise stripped ([sanitizeServerError]). */
private fun ToolCallResult.sanitizeErrorContent(): ToolCallResult = copy(
    content = content.map { item ->
        if (item is ContentItem.Text) ContentItem.Text(sanitizeServerError(item.text)) else item
    },
)

// ----------------------------------- take_screenshot -----------------------------------

/**
 * `steroid_take_screenshot` runtime behavior (issue #284): generic schema mapping plus cwd `project_name`
 * inference. `--out` is the only CLI-only extra ([ToolCliExtras.out]); on success its image is decoded and
 * written ONCE ([writeScreenshotOut]) and the saved path is preserved as the envelope `savedOut` field.
 * The image-carrying result NEVER goes through the verbatim JSON lift; without `--out` (or on an error) it
 * renders through the shared `{content:[...]}` envelope ([Presentation.render]).
 */
private fun DevrigServices.runScreenshotBehavior(
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools,
    cwd: Path,
    routes: List<ProjectRoute>,
): Int {
    val presentation = presentationFor(command.json)
    val projectName = try {
        requireProjectName(command.arguments.stringOrNull("project_name"), cwd, routes)
    } catch (e: CodeArgException) {
        return presentation.renderCodeArgFailure(command.commandName, e, mcpStdout)
    }
    val out = command.extras.out
    // --out remains a CLI-only post-processing step around the shared ToolSpec call.
    val arguments = buildJsonObject {
        command.arguments.forEach { (key, value) -> put(key, value) }
        put("project_name", projectName)
    }
    val result = try {
        runBlocking(Dispatchers.IO) {
            callToolViaSpec(
                VisionScreenshotToolSpec { tools.handler<VisionScreenshotToolHandler>() },
                arguments,
                stderrProgressReporter(),
            )
        }
    } catch (e: ProjectRouteNotFoundException) {
        return presentation.renderError(
            command.commandName,
            "${e.message} — run `devrig list_projects` to see valid project_name keys",
            CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return presentation.renderError(
            command.commandName, "devrig ${command.commandName} failed to reach a backend: ${e.message}",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }

    // A tool-level error (bad window_id, etc.) or a request with no --out: render the single result.
    if (result.isError || out.isNullOrBlank()) {
        return presentation.render(result, command = command.commandName, out = mcpStdout)
    }

    // With --out, success means an image was decoded and written.
    val image = result.content.filterIsInstance<ContentItem.Image>().firstOrNull()
        ?: return presentation.renderError(
            command.commandName,
            "--out=$out requested but the screenshot result carried no image to save",
            CliExit.DATA_ERROR, mcpStdout,
        )
    val bytes = try {
        Base64.getDecoder().decode(image.data)
    } catch (e: IllegalArgumentException) {
        return presentation.renderError(
            command.commandName,
            "--out=$out: the screenshot image payload was not valid base64 (${e.message})",
            CliExit.DATA_ERROR, mcpStdout,
        )
    }
    val savedPath = try {
        writeScreenshotOut(out, bytes, image.mimeType)
    } catch (e: InvalidPathException) {
        return presentation.renderError(
            command.commandName, "invalid --out path: $out (${e.reason})",
            CliExit.USAGE, mcpStdout,
        )
    } catch (e: IOException) {
        return presentation.renderError(
            command.commandName, "failed to write --out=$out: ${e.message}",
            CliExit.IO_ERROR, mcpStdout,
        )
    }
    return presentation.renderScreenshotSaved(result, savedPath.toString(), mcpStdout)
}

/**
 * Writes the decoded PNG [bytes] to [out] (relative paths resolve against cwd), creating parent
 * directories. Returns the absolute path written. Throws [InvalidPathException] for a malformed path
 * string and [IOException] for a genuine write failure (a directory target, a permission denial) so the
 * caller can envelope each with the right exit code.
 */
private fun writeScreenshotOut(out: String, bytes: ByteArray, mimeType: String): Path {
    val outPath = Path.of(out).toAbsolutePath().normalize()
    outPath.parent?.let { Files.createDirectories(it) }
    Files.write(outPath, bytes)
    System.err.println("Saved screenshot (${bytes.size} bytes, $mimeType) to $outPath")
    return outPath
}

// ----------------------------------- open_project -----------------------------------

/**
 * `steroid_open_project` runtime behavior (issue #284): `backend_name` is already schema-generated
 * (devrig's `OpenProjectToolSpec(includeBackendName = true)`), so only `--wait` is a CLI-only extra
 * ([ToolCliExtras.wait]). The relative `--project_path` is resolved against the caller cwd here, and an
 * ABSENT `trust_project` is left out of the arguments so the tool default (`true`) stays owned by
 * `OpenProjectToolSpec.call()` — a CLI-synthesized `false` never leaks. With `--wait`, stdout is delayed
 * until [waitForProjectReady] yields ONE final success/error envelope.
 */
private fun DevrigServices.runOpenProjectBehavior(
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools,
    waitAttempts: Int,
    waitIntervalMs: Long,
): Int {
    val presentation = presentationFor(command.json)
    val rawProjectPath = command.arguments.stringOrNull("project_path")!!
    // Resolve relative paths against the caller's cwd before dispatch.
    val absoluteProjectPath = try {
        Path.of(rawProjectPath).toAbsolutePath().normalize().toString()
    } catch (e: InvalidPathException) {
        return presentation.renderError(
            command.commandName, "invalid --project_path: $rawProjectPath (${e.reason})",
            CliExit.USAGE, mcpStdout,
        )
    }
    val arguments = buildJsonObject {
        command.arguments.forEach { (key, value) -> put(key, value) }
        put("project_path", absoluteProjectPath)
    }
    val result = try {
        runBlocking(Dispatchers.IO) {
            callToolViaSpec(
                OpenProjectToolSpec(
                    includeBackendName = true,
                    validateProjectPath = false,
                ) { tools.handler<OpenProjectToolHandler>() },
                arguments,
                stderrProgressReporter(),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return presentation.renderError(
            command.commandName, "devrig ${command.commandName} failed to reach a backend: ${e.message}",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }

    // Without --wait, or when the open itself already errored, render that single result now.
    if (!command.extras.wait || result.isError) {
        return presentation.render(result, command = command.commandName, out = mcpStdout)
    }

    // Delay stdout until --wait knows the final outcome, preserving one JSON envelope per invocation.
    val ready = waitForProjectReady(absoluteProjectPath, tools, attempts = waitAttempts, intervalMs = waitIntervalMs)
    if (!ready) {
        return presentation.renderError(
            command.commandName,
            "open_project --wait timed out before the project became ready: $absoluteProjectPath",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    val readyResult = result.copy(
        content = result.content + ContentItem.Text("open_project: project is initialized and ready"),
    )
    return presentation.render(readyResult, command = command.commandName, out = mcpStdout)
}

/**
 * Polls `list_windows` until a window for [projectPath] is initialized, not indexing, and has no
 * modal dialog — or the timeout elapses. Returns true when ready. Fixed cadence, bounded attempts,
 * all diagnostics on stderr.
 */
private fun waitForProjectReady(
    projectPath: String,
    tools: McpSteroidTools,
    attempts: Int = 60,
    intervalMs: Long = 2000,
): Boolean {
    val target = try {
        Path.of(projectPath).toRealPath().toString()
    } catch (e: Exception) {
        System.err.println("Could not canonicalize project path '$projectPath' while waiting for readiness: ${e.message}")
        projectPath
    }
    repeat(attempts) { attempt ->
        val response = try {
            runBlocking(Dispatchers.IO) {
                tools.handler<ListWindowsToolHandler>().collectListWindowsResponse()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("open_project --wait: poll ${attempt + 1} failed: ${e.message}")
            null
        }
        val window = response?.windows?.firstOrNull { w ->
            val wp = w.projectPath ?: return@firstOrNull false
            wp == projectPath || wp == target
        }
        if (window != null &&
            window.projectInitialized == true &&
            window.indexingInProgress != true &&
            !window.modalDialogShowing
        ) {
            return true
        }
        System.err.println("open_project --wait: not ready yet (poll ${attempt + 1}/$attempts)")
        Thread.sleep(intervalMs)
    }
    return false
}

// ----------------------------------- schema-driven runtime dispatch -----------------------------------

/**
 * The single runtime entry point for a schema-driven [DevrigCommand.RunTool] (issue #284). Clikt has
 * already routed, tokenized, and typed every parameter into [DevrigCommand.RunTool.arguments]; here the
 * command is routed to its runtime behavior by [DevrigCommand.RunTool.toolName].
 *
 * Most tools flow through the generic pipeline ([runSchemaDispatchToolCommand]): the live [CliToolSpec]
 * is resolved from `devrigToolSpecs` and executed through the same `ToolSpec.call()` path the `devrig mcp`
 * stdio proxy uses. The four tools that need CLI-only orchestration a schema parameter cannot express —
 * `input` (verbatim sequence, error sanitize), `take_screenshot` (`--out` write), `open_project`
 * (`--project_path` resolve, `--wait` poll, absent `trust_project`), and `fetch_resource`
 * (local Generic context, entry-point hints) — route to a dedicated per-tool runtime behavior, each of
 * which still maps the generic schema arguments and shares the frozen exit-code/envelope contract.
 *
 * `tools` is defaulted so tests can inject a fake snapshot without a live IDE; `waitAttempts` /
 * `waitIntervalMs` let the `open_project --wait` poll loop run with a fast cadence under test.
 */
fun DevrigServices.runGeneratedToolCommand(
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
    cwd: Path = Path.of(System.getProperty("user.dir")),
    routes: List<ProjectRoute> = projectRouting.routes(),
    waitAttempts: Int = 60,
    waitIntervalMs: Long = 2000,
): Int = when (command.toolName) {
    VISION_INPUT_TOOL_NAME -> runInputBehavior(command, tools, cwd, routes)
    VISION_SCREENSHOT_TOOL_NAME -> runScreenshotBehavior(command, tools, cwd, routes)
    OPEN_PROJECT_TOOL_NAME -> runOpenProjectBehavior(command, tools, waitAttempts, waitIntervalMs)
    FETCH_RESOURCE_TOOL_NAME -> runFetchResourceBehavior(command)
    else -> runSchemaDispatchToolCommand(command, tools, cwd, routes)
}

/**
 * The generic runtime pipeline for tools with no CLI-only orchestration (the two listers, `execute_code`,
 * `execute_feedback`): resolve the live [CliToolSpec] by [DevrigCommand.RunTool.toolName], apply the
 * runtime preprocessing ([toolRuntimeArguments]), and execute through the one shared error-mapping
 * pipeline ([dispatchToolCall]).
 */
private fun DevrigServices.runSchemaDispatchToolCommand(
    command: DevrigCommand.RunTool,
    tools: McpSteroidTools,
    cwd: Path,
    routes: List<ProjectRoute>,
): Int {
    // Resolve the LIVE spec (with its real handler wiring), never a metadata-only parser spec.
    val spec = liveToolSpec(command.toolName, tools)

    // Human `list_projects` reuses the richer `devrig project` routing table (issue #191): that table
    // draws on port-scan + backend metadata absent from the tool's ListProjectsResponse, so it renders
    // directly and issues no bridge call.
    if (!command.json && command.toolName == LIST_PROJECTS_TOOL_NAME) {
        return runProjectCommand(DevrigCommand.DevrigCommandProject(debug = command.debug, json = false))
    }

    val presentation = presentationFor(command.json)
    // CLI-only runtime preprocessing (cwd-inferred project_name, --code-file/stdin) into a NEW JsonObject.
    val arguments = try {
        toolRuntimeArguments(command, cwd, routes)
    } catch (e: CodeArgException) {
        return presentation.renderCodeArgFailure(command.commandName, e, mcpStdout)
    }
    return dispatchToolCall(
        command.commandName, presentation, tools,
        call = { callToolViaSpec(spec, arguments, stderrProgressReporter()) },
    ) { result -> renderGeneratedToolResult(command, presentation, result) }
}

/**
 * Runtime preprocessing for a generated [command]: resolves the CLI-only inputs that a schema parameter
 * cannot carry — the cwd-inferred `project_name` (issue #266) and the `--code-file`/stdin script source —
 * and returns a NEW [JsonObject]. It never mutates [DevrigCommand.RunTool.arguments] and never re-parses
 * an already-typed value (task_id/reason/timeout/modal/success_rating/execution_id are copied verbatim);
 * a tool with no such inputs (the listers) gets its typed arguments back unchanged. The generic mapping
 * keeps `execution_id` — `ExecuteFeedbackToolSpec.call()` owns that it is contextual and absent from
 * `FeedbackParams`, so CLI glue must not drop it (issue #284).
 */
private fun DevrigServices.toolRuntimeArguments(
    command: DevrigCommand.RunTool,
    cwd: Path,
    routes: List<ProjectRoute>,
): JsonObject = when (command.toolName) {
    EXECUTE_CODE_TOOL_NAME -> command.arguments.withResolvedProjectAndCode(
        projectName = requireProjectName(command.arguments.stringOrNull("project_name"), cwd, routes),
        code = executeCodeSource(command),
    )
    EXECUTE_FEEDBACK_TOOL_NAME -> command.arguments.withResolvedProjectAndCode(
        projectName = requireProjectName(command.arguments.stringOrNull("project_name"), cwd, routes),
        code = feedbackCodeSource(command),
    )
    else -> command.arguments
}

/** execute_code's script body: `--code-file=-` reads stdin, else inline `--code` or the `--code-file` path. */
private fun DevrigServices.executeCodeSource(command: DevrigCommand.RunTool): String = when {
    command.extras.codeFile == "-" -> mcpStdin.readBytes().decodeToString()
    else -> resolveCodeArg(command.arguments.stringOrNull("code"), command.extras.codeFile)
}

/** execute_feedback's optional illustrative snippet: inline `--code`, else the `--code-file` path, else none. */
private fun DevrigServices.feedbackCodeSource(command: DevrigCommand.RunTool): String? {
    val inline = command.arguments.stringOrNull("code")
    return when {
        !inline.isNullOrBlank() -> inline
        command.extras.codeFile == "-" -> mcpStdin.readBytes().decodeToString()
        !command.extras.codeFile.isNullOrBlank() -> resolveCodeArg(null, command.extras.codeFile)
        else -> null
    }
}

/** A copy of these arguments with `project_name` set to [projectName] and `code` replaced by [code] (omitted when null). */
private fun JsonObject.withResolvedProjectAndCode(projectName: String, code: String?): JsonObject =
    buildJsonObject {
        forEach { (key, value) -> if (key != "project_name" && key != "code") put(key, value) }
        put("project_name", projectName)
        code?.let { put("code", it) }
    }

/**
 * The one live [CliToolSpec] whose MCP [toolName] matches, resolved from the canonical [devrigToolSpecs]
 * list — the same list that feeds stdio registration and `--help`. A missing or duplicated match is an
 * invariant violation (a generated command routed to a tool the runtime list does not uniquely provide),
 * never a silent fallback to a handler-free metadata spec.
 */
fun liveToolSpec(toolName: String, tools: McpSteroidTools): CliToolSpec {
    val matches = devrigToolSpecs(tools).filter { it.name == toolName }
    return matches.singleOrNull()
        ?: error(
            "runGeneratedToolCommand: expected exactly one live tool spec named '$toolName' in " +
                "devrigToolSpecs, found ${matches.size}",
        )
}

/**
 * Renders a generated tool's [result]. The two listers carry a single structured-JSON text content that
 * IS the payload, so `--json` lifts it into the envelope `data` verbatim (byte-identical to the
 * pre-schema list envelopes: [McpJson] and [CLI_ENVELOPE_JSON] both encode defaults and omit nulls) and
 * the human path prints each command's dedicated table.
 *
 * Every other (content) tool returns free-form content items, so both modes render through the shared
 * [presentation]: `--json` emits `data:{content:[...]}` and human mode prints each item — byte-identical
 * to the pre-schema per-tool render path (issue #284).
 */
private fun DevrigServices.renderGeneratedToolResult(
    command: DevrigCommand.RunTool,
    presentation: Presentation,
    result: ToolCallResult,
): Int {
    if (command.toolName == LIST_PROJECTS_TOOL_NAME || command.toolName == LIST_WINDOWS_TOOL_NAME) {
        if (command.json) {
            mcpStdout.println(cliEnvelopeJson(command.commandName, isError = result.isError, data = result.jsonResponseData()))
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }
        // Human `list_projects` is handled before dispatch (runProjectCommand); only list_windows reaches here.
        renderListWindowsText(McpJson.decodeFromString(ListWindowsResponse.serializer(), result.singleTextContent()), mcpStdout)
        return CliExit.OK
    }
    return presentation.render(result, command = command.commandName, out = mcpStdout)
}

/** The single text content item a structured-response tool returns; an invariant for the list tools. */
private fun ToolCallResult.singleTextContent(): String =
    content.filterIsInstance<ContentItem.Text>().singleOrNull()?.text
        ?: error("expected exactly one text content item from a generated tool, got $content")

/** The tool's single JSON-object text content, parsed for use verbatim as the envelope `data`. */
private fun ToolCallResult.jsonResponseData(): JsonObject =
    CLI_ENVELOPE_JSON.parseToJsonElement(singleTextContent()).jsonObject
