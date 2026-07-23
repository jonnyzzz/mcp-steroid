/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Cross-command contract sweep for the MCP-as-CLI tool commands (epic #188). Runs each command against
 * fake tool handlers (no IDE) and pins the invariants the whole surface must share:
 *  - success and tool error;
 *  - routing error (stale project_name) and bridge/runtime error;
 *  - human vs `--json` mode, exact exit code, stdout/stderr separation;
 *  - exactly ONE JSON document on stdout under `--json`;
 *  - CancellationException propagation, null exception messages, extreme stdin, path normalization.
 *
 * Per-command specifics (screenshot `--out`, open_project `--wait`, input normalization) live in
 * [CliErrorEnvelopeTest]; this class covers the shared contract and the long-tail edge cases.
 */
class McpAsCliContractTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    // Project-scoped commands (routing can fail with ProjectRouteNotFoundException). execute_code /
    // execute_feedback are schema-generated RunTool commands dispatched via runGeneratedToolCommand.
    private fun execCmd(json: Boolean) =
        executeCodeRunTool(projectName = "k", code = "x", taskId = "t", reason = "r", json = json)
    private fun feedbackCmd(json: Boolean) =
        executeFeedbackRunTool(projectName = "k", taskId = "t", successRating = 0.5, explanation = "x", json = json)
    private fun inputCmd(json: Boolean) =
        inputRunTool(projectName = "k", windowId = "w", taskId = "t", reason = "r", sequence = "press:ESCAPE", json = json)
    private fun screenshotCmd(json: Boolean) =
        takeScreenshotRunTool(projectName = "k", taskId = "t", reason = "r", json = json)

    // ---- shared assertions ----

    /** Under `--json`: exactly one envelope on stdout, with the expected `isError` + exit. */
    private fun assertEnvelope(run: CliRun, expectedExit: Int, expectError: Boolean) {
        assertEquals(expectedExit, run.exit, "stderr=${run.stderr}\nstdout=${run.stdout}")
        // Exactly ONE JSON document: `}\n{` only appears between two top-level pretty-printed objects.
        assertFalse(run.stdout.contains("}\n{"), "more than one JSON document in stdout: ${run.stdout}")
        val obj = Json.parseToJsonElement(run.stdout.trim()).jsonObject
        assertTrue(obj.containsKey("tool") && obj.containsKey("command"), "unified envelope: ${run.stdout}")
        assertEquals(expectError, obj["isError"]!!.jsonPrimitive.boolean, "isError mismatch: ${run.stdout}")
    }

    /** Human mode error: stdout stays clean, the message goes to stderr, exit is the expected code. */
    private fun assertHumanErrorOnStderr(run: CliRun, expectedExit: Int) {
        assertEquals(expectedExit, run.exit)
        assertTrue(run.stdout.isBlank(), "stdout must stay clean on a human-mode error: '${run.stdout}'")
        assertTrue(run.stderr.isNotBlank(), "human-mode error must reach stderr")
    }

    // ------------------------------ routing error → enveloped USAGE ------------------------------

    @Test
    fun `stale project_name is an enveloped usage error for every project-scoped tool`() {
        val ex = { ProjectRouteNotFoundException("k") }
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to ThrowingExecuteCode(ex())))
        }, CliExit.USAGE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(feedbackCmd(true), fakeTools(ExecuteFeedbackToolHandler::class.java to ThrowingFeedback(ex())))
        }, CliExit.USAGE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(inputCmd(true), fakeTools(VisionInputToolHandler::class.java to ThrowingInput(ex())))
        }, CliExit.USAGE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(screenshotCmd(true), fakeTools(VisionScreenshotToolHandler::class.java to ThrowingScreenshot(ex())))
        }, CliExit.USAGE, expectError = true)
    }

    // ------------------------------ bridge/runtime error → enveloped UNAVAILABLE ------------------------------

    @Test
    fun `bridge failure is an enveloped unavailable error for every tool`() {
        val ex = { RuntimeException("connection refused") }
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to ThrowingExecuteCode(ex())))
        }, CliExit.UNAVAILABLE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(feedbackCmd(true), fakeTools(ExecuteFeedbackToolHandler::class.java to ThrowingFeedback(ex())))
        }, CliExit.UNAVAILABLE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(inputCmd(true), fakeTools(VisionInputToolHandler::class.java to ThrowingInput(ex())))
        }, CliExit.UNAVAILABLE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(screenshotCmd(true), fakeTools(VisionScreenshotToolHandler::class.java to ThrowingScreenshot(ex())))
        }, CliExit.UNAVAILABLE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                openProjectRunTool(projectPath = "/p", taskId = "t", reason = "r", json = true),
                fakeTools(OpenProjectToolHandler::class.java to ThrowingOpenProject(ex())),
            )
        }, CliExit.UNAVAILABLE, expectError = true)
        assertEnvelope(runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                DevrigCommand.RunTool(toolName = "steroid_list_windows", commandName = "list_windows", arguments = JsonObject(emptyMap()), json = true),
                fakeTools(ListWindowsToolHandler::class.java to ThrowingListWindows(ex())),
            )
        }, CliExit.UNAVAILABLE, expectError = true)
    }

    // ------------------------------ tool error (isError result) → TOOL_ERROR ------------------------------

    @Test
    fun `a tool-reported error is enveloped in json and goes to stderr in human mode`() {
        val jsonRun = runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to RecordingExecuteCode(toolErrorResult("script threw"))))
        }
        assertEnvelope(jsonRun, CliExit.TOOL_ERROR, expectError = true)

        val humanRun = runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(false), fakeTools(ExecuteCodeToolHandler::class.java to RecordingExecuteCode(toolErrorResult("script threw"))))
        }
        assertHumanErrorOnStderr(humanRun, CliExit.TOOL_ERROR)
        assertTrue(humanRun.stderr.contains("script threw"))
    }

    // ------------------------------ success: human vs json separation ------------------------------

    @Test
    fun `success emits one envelope in json and clean content on stdout in human mode`() {
        val jsonRun = runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to RecordingExecuteCode(okResult("done"))))
        }
        assertEnvelope(jsonRun, CliExit.OK, expectError = false)

        val humanRun = runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(false), fakeTools(ExecuteCodeToolHandler::class.java to RecordingExecuteCode(okResult("done"))))
        }
        assertEquals(CliExit.OK, humanRun.exit)
        assertTrue(humanRun.stdout.contains("done"))
        assertFalse(humanRun.stdout.contains("\"isError\""), "human mode must not print a JSON envelope")
    }

    // ------------------------------ CancellationException propagates ------------------------------

    @Test
    fun `cancellation propagates and is never swallowed as a bridge error`() {
        assertThrows(CancellationException::class.java) {
            runCliCommand(homePaths()) {
                runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to ThrowingExecuteCode(CancellationException("cancelled"))))
            }
        }
    }

    // ------------------------------ null exception message ------------------------------

    @Test
    fun `a bridge exception with a null message still produces a valid envelope`() {
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(execCmd(true), fakeTools(ExecuteCodeToolHandler::class.java to ThrowingExecuteCode(RuntimeException())))
        }
        assertEnvelope(run, CliExit.UNAVAILABLE, expectError = true)
    }

    // ------------------------------ NaN / Infinity success_rating ------------------------------

    @Test
    fun `NaN and Infinity success_rating are rejected at parse`() {
        for (bad in listOf("NaN", "Infinity", "-Infinity")) {
            val cmd = parseDevrigCommand(arrayOf("execute_feedback", "--project_name=k", "--task_id=t", "--success_rating=$bad", "--explanation=x"))
            assertTrue(cmd is DevrigCommand.DevrigCommandParseError, "success_rating=$bad must be a parse error, got $cmd")
        }
    }

    // ------------------------------ extreme stdin for --code-file=- ------------------------------

    @Test
    fun `execute_code reads empty stdin without error`() {
        val rec = RecordingExecuteCode()
        val run = runCliCommand(homePaths(), stdin = ByteArray(0)) {
            runGeneratedToolCommand(
                executeCodeRunTool(projectName = "k", codeFile = "-", taskId = "t", reason = "r"),
                fakeTools(ExecuteCodeToolHandler::class.java to rec),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("", rec.params!!.code)
    }

    @Test
    fun `execute_code reads over 1 MiB from stdin`() {
        val big = "x".repeat(1_100_000)
        val rec = RecordingExecuteCode()
        val run = runCliCommand(homePaths(), stdin = big.toByteArray()) {
            runGeneratedToolCommand(
                executeCodeRunTool(projectName = "k", codeFile = "-", taskId = "t", reason = "r"),
                fakeTools(ExecuteCodeToolHandler::class.java to rec),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals(big.length, rec.params!!.code.length)
    }

    // ------------------------------ path normalization: relative + symlink ------------------------------

    @Test
    fun `open_project normalizes a relative path with dot-dot against cwd`() {
        val rec = RecordingOpenProject()
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                openProjectRunTool(projectPath = "a/../b", taskId = "t", reason = "r"),
                fakeTools(OpenProjectToolHandler::class.java to rec),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        val expected = Path.of("a/../b").toAbsolutePath().normalize().toString()
        assertEquals(expected, rec.params!!.projectPath)
        assertFalse(rec.params!!.projectPath.contains(".."), "dot-dot must be normalized away")
    }

    @Test
    fun `open_project preserves a symlinked project path (does not resolve the link)`() {
        val target = Files.createDirectories(home.resolve("real-project"))
        val link = home.resolve("linked-project")
        Files.createSymbolicLink(link, target)
        val rec = RecordingOpenProject()
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                openProjectRunTool(projectPath = link.toString(), taskId = "t", reason = "r"),
                fakeTools(OpenProjectToolHandler::class.java to rec),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        // The user's chosen (symlink) path is honored verbatim after absolute+normalize — not resolved to
        // the link target, which would silently open a different-looking project than the user asked for.
        assertEquals(link.toAbsolutePath().normalize().toString(), rec.params!!.projectPath)
    }
}
