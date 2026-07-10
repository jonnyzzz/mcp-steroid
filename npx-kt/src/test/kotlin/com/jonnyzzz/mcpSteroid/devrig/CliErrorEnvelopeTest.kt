/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedWindow
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Base64

/**
 * Pins the Round-4 hardening: under `--json`, EVERY failure (client-side usage, routing, bridge) emits
 * the unified `{tool, command, isError:true, data}` envelope; errors never leak server stack traces; a
 * relative `open_project` path resolves against cwd; and `take_screenshot --out` surfaces the written
 * absolute path in the envelope.
 */
class CliErrorEnvelopeTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    private fun CliRun.envelope() = Json.parseToJsonElement(stdout).jsonObject
    private fun envIsError(run: CliRun) = run.envelope()["isError"]!!.jsonPrimitive.booleanOrNull == true
    private fun envCommand(run: CliRun) = run.envelope()["command"]!!.jsonPrimitive.content
    private fun envContentText(run: CliRun): String =
        run.envelope()["data"]!!.jsonObject["content"]!!.jsonArray
            .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }

    // ------------------------------ finding (a): usage/parse errors under --json ------------------------------

    @Test
    fun `parse error under --json emits an isError envelope with exit 64`() {
        val run = runCliCommand(homePaths()) { runCli(parseDevrigCommand(arrayOf("execute_code", "--json"))) }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertEquals("execute_code", envCommand(run))
    }

    @Test
    fun `unknown flag under --json emits an isError envelope`() {
        val run = runCliCommand(homePaths()) { runCli(parseDevrigCommand(arrayOf("list_projects", "--json", "--nope"))) }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        // The specific clikt error is lifted into the envelope, not a useless "Invalid arguments".
        assertTrue(envContentText(run).contains("no such option"), run.stdout)
    }

    // ------------------------------ finding (b): runtime bridge/routing errors under --json ------------------------------

    private fun throwingExecuteCode(ex: Throwable) = object : ExecuteCodeToolHandler {
        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult = throw ex
    }

    @Test
    fun `stale project_name under --json is an enveloped usage error`() {
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "stale", code = "x", taskId = "t", reason = "r", json = true,
        )
        val run = runCliCommand(homePaths()) {
            runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to throwingExecuteCode(ProjectRouteNotFoundException("stale"))))
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("list_projects"), run.stdout)
    }

    @Test
    fun `bridge failure under --json is an enveloped unavailable error`() {
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", code = "x", taskId = "t", reason = "r", json = true,
        )
        val run = runCliCommand(homePaths()) {
            runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to throwingExecuteCode(RuntimeException("connection refused"))))
        }
        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertTrue(envIsError(run), run.stdout)
    }

    // ------------------------------ take_screenshot --out (findings b + --out reporting) ------------------------------

    private fun imageResult(): ToolCallResult {
        val b64 = Base64.getEncoder().encodeToString(ByteArray(8) { it.toByte() })
        return ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png")))
    }

    @Test
    fun `--out success surfaces the written absolute path in the --json envelope`() {
        // Desired contract (#6): the saved destination is a STRUCTURED `data.savedOut` field (an absolute
        // path), not a human string buried in the content array. Supersedes the Round-4 "Saved --out:"
        // content-text approach.
        val outFile = home.resolve("shots/ok.png")
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = outFile.toString(), json = true,
        )
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(imageResult())))
        }
        assertEquals(CliExit.OK, run.exit)
        assertFalse(envIsError(run), run.stdout)
        val savedOut = run.envelope()["data"]!!.jsonObject["savedOut"]!!.jsonPrimitive.content
        assertEquals(outFile.toAbsolutePath().normalize().toString(), savedOut)
    }

    @Test
    fun `--out write failure under --json is an enveloped IO error`() {
        // Point --out at the home DIRECTORY: Files.write on a directory fails. A genuine write failure is
        // an I/O error (74), not a usage error (64) — the path string was fine, the write was not (#4/#6).
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = home.toString(), json = true,
        )
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(imageResult())))
        }
        assertEquals(CliExit.IO_ERROR, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("failed to write --out"), run.stdout)
    }

    @Test
    fun `--out requested but no image is a data error, never a silent success`() {
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = home.resolve("x.png").toString(), json = true,
        )
        val noImage = ToolCallResult(content = listOf(ContentItem.Text("screenshot taken, tree saved")))
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(noImage)))
        }
        assertEquals(CliExit.DATA_ERROR, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("no image to save"), run.stdout)
    }

    @Test
    fun `--out with an undecodable image payload is a data error`() {
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = home.resolve("x.png").toString(), json = true,
        )
        val bad = ToolCallResult(content = listOf(ContentItem.Image(data = "!!!not base64!!!", mimeType = "image/png")))
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(bad)))
        }
        assertEquals(CliExit.DATA_ERROR, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("not valid base64"), run.stdout)
    }

    // ------------------------------ #5: input raw forwarding + no stack-trace leak ------------------------------

    @Test
    fun `input forwards an unrecognized step verbatim (version skew, no client-side rejection)`() {
        // A step this devrig's parser would not recognize must still be forwarded raw — a newer plugin
        // may support it. devrig is NOT a second source of truth for input syntax.
        val rec = RecordingInput()
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = "k", windowId = "w", taskId = "t", reason = "r", sequence = "warp:9000", json = true,
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(cmd, fakeTools(VisionInputToolHandler::class.java to rec))
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("warp:9000", rec.params!!.rawSequence)
    }

    @Test
    fun `input normalizes a server stack-trace error (message kept, frames stripped)`() {
        val serverError = ToolCallResult(
            content = listOf(ContentItem.Text(
                "ERROR: Unknown input step 'warp:9000'\n" +
                    "\tat com.jonnyzzz.mcpSteroid.vision.InputSequenceParser.parse(InputSequenceParser.kt:42)\n" +
                    "\tat com.jonnyzzz.mcpSteroid.server.VisionInputTool.call(VisionInputTool.kt:88)\n" +
                    "\t... 12 more",
            )),
            isError = true,
        )
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = "k", windowId = "w", taskId = "t", reason = "r", sequence = "warp:9000", json = true,
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(cmd, fakeTools(VisionInputToolHandler::class.java to RecordingInput(serverError)))
        }
        assertEquals(CliExit.TOOL_ERROR, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("Unknown input step 'warp:9000'"), run.stdout)
        assertFalse(run.stdout.contains("\\tat "), "no stack frames in stdout: ${run.stdout}")
        assertFalse(run.stdout.contains("InputSequenceParser"), "no server internals leaked: ${run.stdout}")
        assertFalse(run.stdout.contains("... 12 more"), "no frame continuations: ${run.stdout}")
    }

    // ------------------------------ #8: execution_id is accepted but never forwarded ------------------------------

    @Test
    fun `execute_feedback does not forward --execution_id into FeedbackParams`() {
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = "k", taskId = "t", executionId = "e-42", successRating = 0.5, explanation = "x",
        )
        val run = runCliCommand(homePaths()) {
            runFeedbackCommand(cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec))
        }
        assertEquals(CliExit.OK, run.exit)
        // FeedbackParams has no execution_id field; the value is contextual only (matches the MCP tool).
        assertEquals("t", rec.params!!.taskId)
        assertEquals(0.5, rec.params!!.successRating)
    }

    // ------------------------------ #3: open_project --wait single final envelope ------------------------------

    private fun readyWindow(path: String) = ListWindowsResponse(
        windows = listOf(ListedWindow(
            projectName = "k", projectPath = path, title = "t", isActive = true, isVisible = true,
            bounds = null, windowId = "w",
            modalDialogShowing = false, indexingInProgress = false, projectInitialized = true,
        )),
        backgroundTasks = emptyList(),
    )

    private fun notReadyWindow(path: String) = ListWindowsResponse(
        windows = listOf(ListedWindow(
            projectName = "k", projectPath = path, title = "t", isActive = true, isVisible = true,
            bounds = null, windowId = "w",
            modalDialogShowing = false, indexingInProgress = true, projectInitialized = false,
        )),
        backgroundTasks = emptyList(),
    )

    @Test
    fun `open_project --wait ready emits a single success envelope`() {
        val path = home.resolve("proj").toAbsolutePath().normalize().toString()
        val cmd = DevrigCommand.DevrigCommandOpenProject(projectPath = path, taskId = "t", reason = "r", wait = true, json = true)
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(
                cmd,
                fakeTools(
                    OpenProjectToolHandler::class.java to RecordingOpenProject(),
                    ListWindowsToolHandler::class.java to SequencedListWindows(listOf(notReadyWindow(path), readyWindow(path))),
                ),
                waitAttempts = 5, waitIntervalMs = 1,
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertFalse(envIsError(run), run.stdout)
        assertOneJsonDocument(run.stdout)
        assertTrue(envContentText(run).contains("initialized and ready"), run.stdout)
    }

    @Test
    fun `open_project --wait timeout emits a single isError envelope, never a stale success`() {
        val path = home.resolve("proj").toAbsolutePath().normalize().toString()
        val cmd = DevrigCommand.DevrigCommandOpenProject(projectPath = path, taskId = "t", reason = "r", wait = true, json = true)
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(
                cmd,
                fakeTools(
                    OpenProjectToolHandler::class.java to RecordingOpenProject(),
                    ListWindowsToolHandler::class.java to SequencedListWindows(listOf(notReadyWindow(path))),
                ),
                waitAttempts = 3, waitIntervalMs = 1,
            )
        }
        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertOneJsonDocument(run.stdout)
        assertTrue(envContentText(run).contains("timed out"), run.stdout)
    }

    @Test
    fun `open_project --wait tolerates a transient poll failure then succeeds`() {
        val path = home.resolve("proj").toAbsolutePath().normalize().toString()
        val cmd = DevrigCommand.DevrigCommandOpenProject(projectPath = path, taskId = "t", reason = "r", wait = true, json = true)
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(
                cmd,
                fakeTools(
                    OpenProjectToolHandler::class.java to RecordingOpenProject(),
                    ListWindowsToolHandler::class.java to FlakyListWindows(failFirst = 1, then = readyWindow(path)),
                ),
                waitAttempts = 5, waitIntervalMs = 1,
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertFalse(envIsError(run), run.stdout)
        assertOneJsonDocument(run.stdout)
    }

    /**
     * Asserts [stdout] is exactly ONE JSON envelope. [Json.parseToJsonElement] reads a single element
     * and requires EOF, so a second document (or any trailing token) makes it throw — that is the
     * "exactly one JSON document in stdout" contract.
     */
    private fun assertOneJsonDocument(stdout: String) {
        assertFalse(stdout.contains("}\n{"), "more than one JSON document in stdout: $stdout")
        val obj = Json.parseToJsonElement(stdout.trim()).jsonObject
        assertTrue(obj.containsKey("tool") && obj.containsKey("command"), "one envelope object: $stdout")
    }

    // ------------------------------ finding (dangerous): open_project relative path ------------------------------

    @Test
    fun `open_project resolves a relative --project_path against cwd`() {
        val rec = RecordingOpenProject()
        val cmd = DevrigCommand.DevrigCommandOpenProject(projectPath = ".", taskId = "t", reason = "r")
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(cmd, fakeTools(OpenProjectToolHandler::class.java to rec))
        }
        assertEquals(CliExit.OK, run.exit)
        val expected = Path.of(".").toAbsolutePath().normalize().toString()
        assertEquals(expected, rec.params!!.projectPath)
        assertFalse(rec.params!!.projectPath == "." || rec.params!!.projectPath == "/", "must not forward a relative/root path")
    }
}
