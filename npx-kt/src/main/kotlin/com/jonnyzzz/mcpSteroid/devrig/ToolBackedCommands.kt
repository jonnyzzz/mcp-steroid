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
import java.nio.file.Files
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
        System.err.println("${e.message} — run `devrig list_projects` to see valid project_name keys")
        return CliExit.USAGE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("devrig $commandName failed to reach a backend: ${e.message}")
        return CliExit.UNAVAILABLE
    }
    return result.renderTo(command = commandName, json = json, out = mcpStdout)
}

/** Reads inline `--code` or the `--code-file` path; returns null (after printing) on a bad file. */
private fun resolveCodeArg(inline: String?, file: String?): String? {
    if (!inline.isNullOrBlank()) return inline
    val path = Path.of(file!!)
    if (!Files.isRegularFile(path)) {
        System.err.println("--code-file not found or not a regular file: $path")
        return null
    }
    return Files.readString(path)
}

// ----------------------------------- execute_code -----------------------------------

fun DevrigServices.runExecuteCodeCommand(
    command: DevrigCommand.DevrigCommandExecuteCode,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    // `--code-file=-` reads the script from stdin so agents can pipe a snippet without a temp file.
    val code = when {
        command.codeFile == "-" -> mcpStdin.readBytes().decodeToString()
        else -> resolveCodeArg(command.code, command.codeFile) ?: return CliExit.USAGE
    }
    val modal = command.modal?.let { wire ->
        ModalMode.entries.firstOrNull { it.wire == wire }
            ?: run {
                System.err.println(
                    "invalid --modal '$wire'. Valid: ${ModalMode.entries.joinToString(" | ") { it.wire }}"
                )
                return CliExit.USAGE
            }
    } ?: ModalMode.DEFAULT
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
    val code: String? = when {
        !command.code.isNullOrBlank() -> command.code
        !command.codeFile.isNullOrBlank() -> resolveCodeArg(null, command.codeFile) ?: return CliExit.USAGE
        else -> null
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
    // The devrig bridge forwards the raw sequence string verbatim (the IDE re-parses it), so we do
    // not need a client-side parse here; pass an empty parsed list and the raw string.
    val params = InputParams(
        taskId = command.taskId!!,
        reason = command.reason!!,
        windowId = command.windowId!!,
        sequence = emptyList(),
        rawSequence = command.sequence,
    )
    return runToolCall("input", command.json, tools) { t ->
        t.handler<VisionInputToolHandler>().handleInputSequence(command.projectName!!, params)
    }
}

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
        System.err.println("${e.message} — run `devrig list_projects` to see valid project_name keys")
        return CliExit.USAGE
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("devrig take_screenshot failed to reach a backend: ${e.message}")
        return CliExit.UNAVAILABLE
    }

    // --out: pull the first image out of the result and write the raw PNG bytes to disk.
    if (!command.out.isNullOrBlank() && !result.isError) {
        val written = writeScreenshotOut(result, command.out)
        if (!written) return CliExit.USAGE
    }
    return result.renderTo(command = "take_screenshot", json = command.json, out = mcpStdout)
}

/** Decodes the first image in [result] and writes it to [out]; returns false (after printing) on failure. */
private fun writeScreenshotOut(result: ToolCallResult, out: String): Boolean {
    val image = result.content.filterIsInstance<ContentItem.Image>().firstOrNull()
    if (image == null) {
        System.err.println("--out given but the screenshot result carried no image payload")
        return true // not a usage error — the call succeeded, there was just nothing to save
    }
    return try {
        val bytes = Base64.getDecoder().decode(image.data)
        val outPath = Path.of(out).toAbsolutePath()
        outPath.parent?.let { Files.createDirectories(it) }
        Files.write(outPath, bytes)
        System.err.println("Saved screenshot (${bytes.size} bytes, ${image.mimeType}) to $outPath")
        true
    } catch (e: Exception) {
        System.err.println("failed to write --out=$out: ${e.message}")
        false
    }
}

// ----------------------------------- open_project -----------------------------------

fun DevrigServices.runOpenProjectCommand(
    command: DevrigCommand.DevrigCommandOpenProject,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
    // Exposed for tests so the --wait poll loop can run with a fast cadence.
    waitAttempts: Int = 60,
    waitIntervalMs: Long = 2000,
): Int {
    val params = OpenProjectParams(
        projectPath = command.projectPath!!,
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
        System.err.println("devrig open_project failed to reach a backend: ${e.message}")
        return CliExit.UNAVAILABLE
    }

    val exit = result.renderTo(command = "open_project", json = command.json, out = mcpStdout)
    if (exit != CliExit.OK || !command.wait) return exit

    // --wait: poll list_windows until the freshly-opened project is ready. Best-effort; all progress
    // goes to stderr so stdout keeps just the open_project result (clean for --json / pipes).
    val ready = waitForProjectReady(command.projectPath, tools, attempts = waitAttempts, intervalMs = waitIntervalMs)
    if (!ready) {
        System.err.println("open_project: --wait timed out before the project became ready")
        return CliExit.UNAVAILABLE
    }
    System.err.println("open_project: project is initialized and ready")
    return CliExit.OK
}

/**
 * Polls `list_windows` until a window for [projectPath] is initialized, not indexing, and has no
 * modal dialog — or the timeout elapses. Returns true when ready. Fixed cadence, bounded attempts,
 * all diagnostics on stderr.
 */
private fun DevrigServices.waitForProjectReady(
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
