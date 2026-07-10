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
import com.jonnyzzz.mcpSteroid.vision.InputSequenceParser
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

/** Signals a bad `--code-file` argument (missing / not a regular file) with an agent-usable message. */
private class CodeArgException(message: String) : RuntimeException(message)

/** Reads inline `--code` or the `--code-file` path; throws [CodeArgException] on a bad file. */
private fun resolveCodeArg(inline: String?, file: String?): String {
    if (!inline.isNullOrBlank()) return inline
    val path = Path.of(file!!)
    if (!Files.isRegularFile(path)) {
        throw CodeArgException("--code-file not found or not a regular file: $path")
    }
    return Files.readString(path)
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
        return renderCliError("execute_code", e.message!!, command.json, CliExit.USAGE, mcpStdout)
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
    val code: String? = try {
        when {
            !command.code.isNullOrBlank() -> command.code
            !command.codeFile.isNullOrBlank() -> resolveCodeArg(null, command.codeFile)
            else -> null
        }
    } catch (e: CodeArgException) {
        return renderCliError("execute_feedback", e.message!!, command.json, CliExit.USAGE, mcpStdout)
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
    // Validate the sequence client-side so a malformed step fails fast with a concise message + syntax
    // hint. Previously the raw string round-tripped to the IDE, whose parse failure leaked a full server
    // stack trace into the (--json) envelope (Codex Round-4 finding). We still forward the RAW string and
    // an empty parsed list on success — the IDE remains the single source of truth for parsing.
    command.sequence?.let { seq ->
        try {
            InputSequenceParser().parse(seq)
        } catch (e: IllegalArgumentException) {
            return renderCliError(
                "input",
                "invalid --sequence: ${e.message}\n" +
                    "Accepted steps: press:KEY[+MODS], type:TEXT, delay:MS, stick:KEY, click:BTN@x,y",
                command.json, CliExit.USAGE, mcpStdout,
            )
        }
    }
    // The devrig bridge forwards the raw sequence string verbatim (the IDE re-parses it), so we do
    // not need to send the parsed list; pass an empty parsed list and the raw string.
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

    // --out: pull the first image out of the result and write the raw PNG bytes to disk.
    var rendered = result
    if (!command.out.isNullOrBlank() && !result.isError) {
        val savedPath = try {
            writeScreenshotOut(result, command.out)
        } catch (e: Exception) {
            return renderCliError(
                "take_screenshot", "failed to write --out=${command.out}: ${e.message}",
                command.json, CliExit.USAGE, mcpStdout,
            )
        }
        // Surface the ACTUAL written destination (absolute, cwd-resolved for a relative --out) in the
        // rendered result so it appears in both the human output and the --json envelope `data`, not
        // just on stderr (Codex Round-4 finding).
        if (savedPath != null) {
            rendered = result.copy(content = result.content + ContentItem.Text("Saved --out: $savedPath"))
        }
    }
    return rendered.renderTo(command = "take_screenshot", json = command.json, out = mcpStdout)
}

/**
 * Decodes the first image in [result] and writes it to [out]. Returns the absolute path written, or
 * null when the result carried no image (a non-error no-op). Throws on a genuine write failure so the
 * caller can envelope it.
 */
private fun writeScreenshotOut(result: ToolCallResult, out: String): Path? {
    val image = result.content.filterIsInstance<ContentItem.Image>().firstOrNull()
    if (image == null) {
        System.err.println("--out given but the screenshot result carried no image payload")
        return null // not an error — the call succeeded, there was just nothing to save
    }
    val bytes = Base64.getDecoder().decode(image.data)
    val outPath = Path.of(out).toAbsolutePath().normalize()
    outPath.parent?.let { Files.createDirectories(it) }
    Files.write(outPath, bytes)
    System.err.println("Saved screenshot (${bytes.size} bytes, ${image.mimeType}) to $outPath")
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
    // project — the one genuinely dangerous Round-4 finding.
    val absoluteProjectPath = Path.of(command.projectPath!!).toAbsolutePath().normalize().toString()
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

    val exit = result.renderTo(command = "open_project", json = command.json, out = mcpStdout)
    if (exit != CliExit.OK || !command.wait) return exit

    // --wait: poll list_windows until the freshly-opened project is ready. Best-effort; all progress
    // goes to stderr so stdout keeps just the open_project result (clean for --json / pipes).
    val ready = waitForProjectReady(absoluteProjectPath, tools, attempts = waitAttempts, intervalMs = waitIntervalMs)
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
