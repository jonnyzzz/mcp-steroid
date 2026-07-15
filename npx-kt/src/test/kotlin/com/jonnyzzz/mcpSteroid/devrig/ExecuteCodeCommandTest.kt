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

/** Glue test for `devrig execute_code`: CLI args → ExecCodeParams → render/exit, via a fake handler. */
class ExecuteCodeCommandTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    @Test
    fun `inline code maps to ExecCodeParams with defaults`() {
        val rec = RecordingExecuteCode()
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "proj-key", code = "println(1)", taskId = "t1", reason = "why",
        )
        val run = runCliCommand(homePaths()) {
            runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
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
        // Characterization (Task 9): the CLI now maps its flags to an `arguments` JsonObject and calls
        // ExecuteCodeToolSpec.call(), which re-parses that JSON into ExecCodeParams. This locks that every
        // flag — code, task_id, reason, timeout, modal — round-trips through the arguments JSON and arrives
        // at the handler unchanged, so the spec-dispatch path preserves the args-mapping contract.
        val rec = RecordingExecuteCode()
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "proj-key", code = "println(42)", taskId = "task-9", reason = "characterize",
            modal = "non_modal", timeout = 321,
        )
        val run = runCliCommand(homePaths()) {
            runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
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
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", code = "x", taskId = "t", reason = "r",
            modal = "unleashed", timeout = 120,
        )
        runCliCommand(homePaths()) { runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(ModalMode.UNLEASHED, rec.params!!.modal)
        assertEquals(120, rec.params!!.timeout)
    }

    @Test
    fun `invalid modal is rejected at parse, handler never reached`() {
        // #2: --modal is validated at PARSE now (not inside runExecuteCodeCommand), so a bad value becomes
        // a DevrigCommandParseError that rides the unified --json envelope path — the execute_code handler
        // is never reached. This supersedes the old stderr-only, --json-ignoring behavior.
        val parsed = parseDevrigCommand(arrayOf(
            "execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=bogus",
        ))
        assertTrue(parsed is DevrigCommand.DevrigCommandParseError, "expected parse error, got $parsed")
        assertTrue((parsed as DevrigCommand.DevrigCommandParseError).message.contains("invalid --modal"), parsed.message)
    }

    @Test
    fun `code-file dash reads the script from stdin`() {
        val rec = RecordingExecuteCode()
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", codeFile = "-", taskId = "t", reason = "r",
        )
        runCliCommand(homePaths(), stdin = "val x = 42".toByteArray()) {
            runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec))
        }
        assertEquals("val x = 42", rec.params!!.code)
    }

    @Test
    fun `code-file path is read from disk`() {
        val script = home.resolve("snippet.kts")
        Files.writeString(script, "println(\"hi\")")
        val rec = RecordingExecuteCode()
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", codeFile = script.toString(), taskId = "t", reason = "r",
        )
        runCliCommand(homePaths()) { runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals("println(\"hi\")", rec.params!!.code)
    }

    @Test
    fun `missing code-file is a usage error`() {
        val rec = RecordingExecuteCode()
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", codeFile = home.resolve("nope.kts").toString(), taskId = "t", reason = "r",
        )
        val run = runCliCommand(homePaths()) { runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("--code-file not found"), run.stderr)
    }

    @Test
    fun `tool error routes to stderr and TOOL_ERROR exit, clean stdout`() {
        val rec = RecordingExecuteCode(result = ToolCallResult.errorResult("boom"))
        val cmd = DevrigCommand.DevrigCommandExecuteCode(
            projectName = "k", code = "x", taskId = "t", reason = "r",
        )
        val run = runCliCommand(homePaths()) { runExecuteCodeCommand(cmd, fakeTools(ExecuteCodeToolHandler::class.java to rec)) }
        assertEquals(CliExit.TOOL_ERROR, run.exit)
        assertEquals("", run.stdout)
        assertTrue(run.stderr.contains("boom"), run.stderr)
    }
}
