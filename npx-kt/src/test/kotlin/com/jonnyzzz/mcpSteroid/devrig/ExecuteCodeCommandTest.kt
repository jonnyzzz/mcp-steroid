/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ModalMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Glue test for `devrig execute_code`: the generated [DevrigCommand.RunTool] → runtime preprocessing
 * (cwd project inference, --code-file/stdin) → ExecCodeParams → render/exit, via a fake handler (#284).
 */
class ExecuteCodeCommandTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    @Test
    fun `inline code maps to ExecCodeParams with defaults`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "proj-key", code = "println(1)", taskId = "t1", reason = "why")
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-key", rec.projectName)
        assertEquals("println(1)", rec.params!!.code)
        assertEquals("t1", rec.params!!.taskId)
        assertEquals("why", rec.params!!.reason)
        assertEquals(600, rec.params!!.timeout, "default timeout")
        assertEquals(ModalMode.DEFAULT, rec.params!!.modal)
        assertEquals("ok", run.stdout.trim())
    }

    @Test
    fun `all CLI flags reach the tool as the exact ExecCodeParams the spec builds`() {
        // Characterization: the CLI maps its flags to an `arguments` JsonObject and calls the live
        // ExecuteCodeToolSpec.call(), which re-parses that JSON into ExecCodeParams. This locks that every
        // flag — code, task_id, reason, timeout, modal — round-trips through the arguments JSON and arrives
        // at the handler unchanged, so the spec-dispatch path preserves the args-mapping contract.
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(
            projectName = "proj-key", code = "println(42)", taskId = "task-9", reason = "characterize",
            modal = "non_modal", timeout = 321,
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-key", rec.projectName)
        assertEquals("println(42)", rec.params!!.code)
        assertEquals("task-9", rec.params!!.taskId)
        assertEquals("characterize", rec.params!!.reason)
        assertEquals(321, rec.params!!.timeout)
        assertEquals(ModalMode.NON_MODAL, rec.params!!.modal)
    }

    @Test
    fun `modal and timeout flags are reflected`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "k", code = "x", taskId = "t", reason = "r", modal = "unleashed", timeout = 120)
        runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(ModalMode.UNLEASHED, rec.params!!.modal)
        assertEquals(120, rec.params!!.timeout)
    }

    @Test
    fun `invalid modal is rejected at parse, handler never reached`() {
        // --modal is a schema-generated Clikt `choice`, so a bad value is a BadParameterValue at parse
        // (exit 64, rides the unified --json envelope) — the execute_code handler is never reached.
        val parsed = parseDevrigCommand(arrayOf(
            "execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=bogus",
        ))
        assertTrue(parsed is DevrigCommand.DevrigCommandParseError, "expected parse error, got $parsed")
        parsed as DevrigCommand.DevrigCommandParseError
        assertTrue(parsed.message.contains("invalid choice"), parsed.message)
        assertTrue(parsed.message.contains("smart_non_modal"), "lists valid values: ${parsed.message}")
        assertTrue(parsed.text.contains("--modal"), "the formatted usage names the option: ${parsed.text}")
    }

    @Test
    fun `code-file dash reads the script from stdin`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "k", codeFile = "-", taskId = "t", reason = "r")
        runCliCommand(homePaths(), stdin = "val x = 42".toByteArray()) {
            runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
        }
        assertEquals("val x = 42", rec.params!!.code)
    }

    @Test
    fun `code-file path is read from disk`() {
        val script = home.resolve("snippet.kts")
        Files.writeString(script, "println(\"hi\")")
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "k", codeFile = script.toString(), taskId = "t", reason = "r")
        runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals("println(\"hi\")", rec.params!!.code)
    }

    @Test
    fun `missing code-file is a usage error`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "k", codeFile = home.resolve("nope.kts").toString(), taskId = "t", reason = "r")
        val run = runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("--code-file not found"), run.stderr)
    }

    @Test
    fun `tool error routes to stderr and TOOL_ERROR exit, clean stdout`() {
        val rec = RecordingExecuteCode(result = ToolCallResult.errorResult("boom"))
        val cmd = executeCodeRunTool(projectName = "k", code = "x", taskId = "t", reason = "r")
        val run = runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(CliExit.TOOL_ERROR, run.exit)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.contains("boom"), run.stderr)
    }

    // ------------------------- --project_name cwd inference (issue #266 part 2) -------------------------

    @Test
    fun `explicit project_name overrides cwd inference`() {
        // A route DOES contain the cwd, but --project_name was passed explicitly — the explicit value must
        // win outright, never silently redirected to whatever the cwd happens to resolve to.
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "explicit-key", code = "x", taskId = "t", reason = "r")
        val cwd = Path.of("/home/u/proj")
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec),
                cwd = cwd, routes = listOf(fakeRoute("/home/u/proj", "cwd-inferred-key")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("explicit-key", rec.projectName)
    }

    @Test
    fun `blank project_name infers the single containing project`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = null, code = "x", taskId = "t", reason = "r")
        val cwd = Path.of("/home/u/proj/src")
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec),
                cwd = cwd, routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-abc", rec.projectName)
    }

    @Test
    fun `no containing project fails with a candidate-listing usage error`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = null, code = "x", taskId = "t", reason = "r")
        val cwd = Path.of("/tmp/elsewhere")
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec),
                cwd = cwd, routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.contains("proj-abc"), run.stderr)
        assertTrue(run.stderr.contains("--project_name"), run.stderr)
        assertTrue(rec.projectName == null, "handler must never be invoked on a usage error")
    }

    @Test
    fun `ambiguous cwd match fails with a candidate-listing usage error`() {
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = "  ", code = "x", taskId = "t", reason = "r")
        val cwd = Path.of("/home/u/proj/src")
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec),
                cwd = cwd,
                routes = listOf(fakeRoute("/home/u/proj", "proj-abc"), fakeRoute("/home/u/proj", "proj-xyz")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("proj-abc"), run.stderr)
        assertTrue(run.stderr.contains("proj-xyz"), run.stderr)
    }

    @Test
    fun `no containing project with --json emits the unified error envelope`() {
        // The usage error must ride the SAME --json envelope as any other CodeArgException, not a
        // stderr-only path — assert the JSON stdout carries isError + the USAGE-shaped message.
        val rec = RecordingExecuteCode()
        val cmd = executeCodeRunTool(projectName = null, code = "x", taskId = "t", reason = "r", json = true)
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec),
                cwd = Path.of("/tmp/elsewhere"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stdout.contains("\"isError\":true") || run.stdout.contains("\"isError\": true"), run.stdout)
        assertTrue(run.stdout.contains("proj-abc"), run.stdout)
    }
}
