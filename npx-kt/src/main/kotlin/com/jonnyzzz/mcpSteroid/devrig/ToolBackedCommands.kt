/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * `devrig` subcommands that map 1:1 onto a bridge tool handler and return a [ToolCallResult].
 *
 * The handlers are resolved from an [McpSteroidTools] — in production [StubMcpSteroidTools], the SAME
 * wiring the `devrig mcp` stdio proxy uses, so the CLI never reimplements tool logic. Each command takes
 * `tools` as a defaulted parameter purely so tests can inject a fake and assert the args→`*Params`→render
 * glue without a live IDE (payload→wire mapping is covered by DevrigToolBridgeClientTest).
 */

/**
 * Runs [block] against [tools], turning routing/bridge failures into meaningful exit codes + agent-usable
 * stderr messages, then renders the [ToolCallResult].
 */
private inline fun DevrigServices.runToolCall(
    commandName: String,
    json: Boolean,
    tools: McpSteroidTools,
    crossinline block: suspend (McpSteroidTools) -> ToolCallResult,
): Int {
    val result = try {
        runBlocking(Dispatchers.IO) { block(tools) }
    } catch (e: ProjectRouteNotFoundException) {
        return renderCliError(
            commandName,
            "${e.message} — run `devrig list_projects` to see valid project_name keys",
            json, CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return renderCliError(
            commandName, "devrig $commandName failed to reach a backend: ${e.message}",
            json, CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    return result.renderTo(command = commandName, json = json, out = mcpStdout)
}

/**
 * Signals a bad `--code-file`. [exit] distinguishes a fixable-input mistake ([CliExit.USAGE] — missing
 * file, non-regular file, malformed path string) from a genuine read failure ([CliExit.IO_ERROR] — the
 * path resolves and is a regular file but reading it throws, e.g. a permission denial).
 */
private class CodeArgException(message: String, val exit: Int) : RuntimeException(message)

/** Reads inline `--code` or the `--code-file` path; throws [CodeArgException] on a bad/unreadable file. */
private fun resolveCodeArg(inline: String?, file: String?): String {
    if (!inline.isNullOrBlank()) return inline
    val path = try {
        Path.of(file!!)
    } catch (e: InvalidPathException) {
        throw CodeArgException("--code-file is not a valid path: $file (${e.reason})", CliExit.USAGE)
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

// ----------------------------------- execute_code -----------------------------------

fun DevrigServices.runExecuteCodeCommand(
    command: DevrigCommand.DevrigCommandExecuteCode,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    // `--code-file=-` reads the script from stdin so agents can pipe a snippet without a temp file.
    val code = try {
        when {
            command.codeFile == "-" -> mcpStdin.readBytes().decodeToString()
            else -> resolveCodeArg(command.code, command.codeFile)
        }
    } catch (e: CodeArgException) {
        return renderCliError("execute_code", e.message!!, command.json, e.exit, mcpStdout)
    }
    // --modal is validated at parse time (ExecuteCodeCliCommand), so a bad value never reaches here; the
    // firstOrNull mapping is a defensive lookup that falls back to the default rather than failing.
    val modal = command.modal?.let { wire -> ModalMode.entries.firstOrNull { it.wire == wire } }
        ?: ModalMode.DEFAULT
    val params = ExecCodeParams(
        taskId = command.taskId!!,
        code = code,
        reason = command.reason!!,
        timeout = command.timeout ?: 600,
        modal = modal,
    )
    return runToolCall("execute_code", command.json, tools) { t ->
        t.handler<ExecuteCodeToolHandler>()
            .executeCode(command.projectName!!, params, stderrProgressReporter())
    }
}

// ----------------------------------- execute_feedback -----------------------------------

fun DevrigServices.runFeedbackCommand(
    command: DevrigCommand.DevrigCommandFeedback,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    val code: String? = try {
        when {
            !command.code.isNullOrBlank() -> command.code
            !command.codeFile.isNullOrBlank() -> resolveCodeArg(null, command.codeFile)
            else -> null
        }
    } catch (e: CodeArgException) {
        return renderCliError("execute_feedback", e.message!!, command.json, e.exit, mcpStdout)
    }
    val params = FeedbackParams(
        taskId = command.taskId!!,
        successRating = command.successRating!!,
        explanation = command.explanation,
        code = code,
    )
    return runToolCall("execute_feedback", command.json, tools) { t ->
        t.handler<ExecuteFeedbackToolHandler>().handleFeedback(command.projectName!!, params)
    }
}

// ----------------------------------- input -----------------------------------

fun DevrigServices.runInputCommand(
    command: DevrigCommand.DevrigCommandInput,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    // Forward the RAW sequence verbatim — the IDE is the SINGLE source of truth for input syntax. devrig
    // deliberately does NOT re-parse/validate the sequence: a newer plugin may accept steps this devrig's
    // parser wouldn't recognize, so client-side rejection would strand a valid call on version skew.
    val params = InputParams(
        taskId = command.taskId!!,
        reason = command.reason!!,
        windowId = command.windowId!!,
        sequence = emptyList(),
        rawSequence = command.sequence,
    )
    // The IDE's parse failure is returned as an isError result whose text is a full server stack trace;
    // sanitize THAT (strip JVM frames) so the agent sees the message, not a leaked trace. Scoped to
    // input — never execute_code, whose trace is the agent's own script (see sanitizeServerError).
    val result = try {
        runBlocking(Dispatchers.IO) {
            tools.handler<VisionInputToolHandler>().handleInputSequence(command.projectName!!, params)
        }
    } catch (e: ProjectRouteNotFoundException) {
        return renderCliError(
            "input", "${e.message} — run `devrig list_projects` to see valid project_name keys",
            command.json, CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return renderCliError(
            "input", "devrig input failed to reach a backend: ${e.message}",
            command.json, CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    val rendered = if (result.isError) result.sanitizeErrorContent() else result
    return rendered.renderTo(command = "input", json = command.json, out = mcpStdout)
}

/** Returns a copy with every Text content item's stack-frame noise stripped ([sanitizeServerError]). */
private fun ToolCallResult.sanitizeErrorContent(): ToolCallResult = copy(
    content = content.map { item ->
        if (item is ContentItem.Text) ContentItem.Text(sanitizeServerError(item.text)) else item
    },
)

// ----------------------------------- take_screenshot -----------------------------------

fun DevrigServices.runScreenshotCommand(
    command: DevrigCommand.DevrigCommandScreenshot,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    val params = ScreenshotParams(
        taskId = command.taskId!!,
        reason = command.reason!!,
        windowId = command.windowId,
    )
    val result = try {
        runBlocking(Dispatchers.IO) {
            tools.handler<VisionScreenshotToolHandler>()
                .screenshotWindow(command.projectName!!, params, stderrProgressReporter())
        }
    } catch (e: ProjectRouteNotFoundException) {
        return renderCliError(
            "take_screenshot",
            "${e.message} — run `devrig list_projects` to see valid project_name keys",
            command.json, CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return renderCliError(
            "take_screenshot", "devrig take_screenshot failed to reach a backend: ${e.message}",
            command.json, CliExit.UNAVAILABLE, mcpStdout,
        )
    }

    // A tool-level error (bad window_id, etc.) or a request with no --out: render the single result.
    if (result.isError || command.out.isNullOrBlank()) {
        return result.renderTo(command = "take_screenshot", json = command.json, out = mcpStdout)
    }

    // --out requested: strict contract (finding #6) — exit 0 ONLY when the file is actually written.
    // A result with no image, or an undecodable image, is a data error (65), not a silent success; a
    // malformed --out path string is a usage error (64); a real write failure is an I/O error (74).
    val image = result.content.filterIsInstance<ContentItem.Image>().firstOrNull()
        ?: return renderCliError(
            "take_screenshot",
            "--out=${command.out} requested but the screenshot result carried no image to save",
            command.json, CliExit.DATA_ERROR, mcpStdout,
        )
    val bytes = try {
        Base64.getDecoder().decode(image.data)
    } catch (e: IllegalArgumentException) {
        return renderCliError(
            "take_screenshot",
            "--out=${command.out}: the screenshot image payload was not valid base64 (${e.message})",
            command.json, CliExit.DATA_ERROR, mcpStdout,
        )
    }
    val savedPath = try {
        writeScreenshotOut(command.out, bytes, image.mimeType)
    } catch (e: InvalidPathException) {
        return renderCliError(
            "take_screenshot", "invalid --out path: ${command.out} (${e.reason})",
            command.json, CliExit.USAGE, mcpStdout,
        )
    } catch (e: IOException) {
        return renderCliError(
            "take_screenshot", "failed to write --out=${command.out}: ${e.message}",
            command.json, CliExit.IO_ERROR, mcpStdout,
        )
    }
    return renderScreenshotSaved(result, savedPath.toString(), command.json, mcpStdout)
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

fun DevrigServices.runOpenProjectCommand(
    command: DevrigCommand.DevrigCommandOpenProject,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
    // Exposed for tests so the --wait poll loop can run with a fast cadence.
    waitAttempts: Int = 60,
    waitIntervalMs: Long = 2000,
): Int {
    // Resolve a relative --project_path against the caller's cwd into an absolute path. Previously a
    // relative path (e.g. `.`) was resolved by the backend against `/`, silently opening the wrong
    // project — the one genuinely dangerous Round-4 finding. A malformed path STRING is a fixable
    // usage error (enveloped, not a stack trace propagated to Main).
    val absoluteProjectPath = try {
        Path.of(command.projectPath!!).toAbsolutePath().normalize().toString()
    } catch (e: InvalidPathException) {
        return renderCliError(
            "open_project", "invalid --project_path: ${command.projectPath} (${e.reason})",
            command.json, CliExit.USAGE, mcpStdout,
        )
    }
    val params = OpenProjectParams(
        projectPath = absoluteProjectPath,
        trustProject = command.trustProject,
        backendName = command.backendName,
    )
    val result = try {
        runBlocking(Dispatchers.IO) {
            tools.handler<OpenProjectToolHandler>().handleOpenProject(params, stderrProgressReporter())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return renderCliError(
            "open_project", "devrig open_project failed to reach a backend: ${e.message}",
            command.json, CliExit.UNAVAILABLE, mcpStdout,
        )
    }

    // Without --wait, or when the open itself already errored, render that single result now.
    if (!command.wait || result.isError) {
        return result.renderTo(command = "open_project", json = command.json, out = mcpStdout)
    }

    // --wait: poll list_windows until the freshly-opened project is ready BEFORE emitting any stdout, so
    // exactly ONE final envelope reflects the command's true outcome. Poll progress + transient poll
    // failures go to stderr; the readiness contract is unchanged (initialized, not indexing, no modal).
    // A timeout is a genuine failure — stdout gets a single isError envelope, never a stale success one.
    val ready = waitForProjectReady(absoluteProjectPath, tools, attempts = waitAttempts, intervalMs = waitIntervalMs)
    if (!ready) {
        return renderCliError(
            "open_project",
            "open_project --wait timed out before the project became ready: $absoluteProjectPath",
            command.json, CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    val readyResult = result.copy(
        content = result.content + ContentItem.Text("open_project: project is initialized and ready"),
    )
    return readyResult.renderTo(command = "open_project", json = command.json, out = mcpStdout)
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
