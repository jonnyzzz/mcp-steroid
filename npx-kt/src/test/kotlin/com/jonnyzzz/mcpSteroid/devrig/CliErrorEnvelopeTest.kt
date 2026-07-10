/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
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
        val outFile = home.resolve("shots/ok.png")
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = outFile.toString(), json = true,
        )
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(imageResult())))
        }
        assertEquals(CliExit.OK, run.exit)
        assertFalse(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains(outFile.toAbsolutePath().normalize().toString()), run.stdout)
    }

    @Test
    fun `--out write failure under --json is an enveloped error`() {
        // Point --out at the home DIRECTORY: Files.write on a directory fails, exercising the write path.
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = home.toString(), json = true,
        )
        val run = runCliCommand(homePaths()) {
            runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to RecordingScreenshot(imageResult())))
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("failed to write --out"), run.stdout)
    }

    // ------------------------------ finding: input stack-trace leak ------------------------------

    @Test
    fun `invalid --sequence fails client-side with a concise enveloped message and no stack trace`() {
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = "k", windowId = "w", taskId = "t", reason = "r", sequence = "not-json", json = true,
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(cmd, fakeTools(VisionInputToolHandler::class.java to RecordingInput()))
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(envIsError(run), run.stdout)
        assertTrue(envContentText(run).contains("invalid --sequence"), run.stdout)
        assertFalse(run.stdout.contains("\tat "), "no stack frames in stdout: ${run.stdout}")
        assertFalse(run.stdout.contains("com.jonnyzzz.mcpSteroid.vision"), "no server internals leaked: ${run.stdout}")
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
