/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins how the MCP-as-CLI subcommands (epic #188) parse into [DevrigCommand] variants, and that
 * missing required arguments produce agent-usable parse errors with runnable examples.
 */
class McpAsCliParseTest {

    private fun parse(vararg args: String): DevrigCommand = parseDevrigCommand(arrayOf(*args))

    private fun parseError(vararg args: String): DevrigCommand.DevrigCommandParseError {
        val command = parse(*args)
        assertTrue(command is DevrigCommand.DevrigCommandParseError, "expected parse error, got $command")
        return command as DevrigCommand.DevrigCommandParseError
    }

    // ------------------------------ prompt / fetch_resource ------------------------------

    @Test
    fun `prompt takes a positional uri`() {
        val command = parse("prompt", "mcp-steroid://x/y")
        assertTrue(command is DevrigCommand.DevrigCommandFetchResource)
        command as DevrigCommand.DevrigCommandFetchResource
        assertEquals("mcp-steroid://x/y", command.uri)
        assertNull(command.projectName)
    }

    @Test
    fun `prompt without uri is a parse error with a runnable example`() {
        val err = parseError("prompt")
        assertTrue(err.text.contains("missing <uri>"), err.text)
        assertTrue(err.text.contains("devrig prompt "), "must show a runnable example: ${err.text}")
    }

    @Test
    fun `fetch_resource maps --uri and --project_name`() {
        val command = parse("fetch_resource", "--uri=mcp-steroid://a", "--project_name=proj-1")
        assertTrue(command is DevrigCommand.DevrigCommandFetchResource)
        command as DevrigCommand.DevrigCommandFetchResource
        assertEquals("mcp-steroid://a", command.uri)
        assertEquals("proj-1", command.projectName)
    }

    @Test
    fun `fetch_resource without --uri is a parse error`() {
        assertTrue(parseError("fetch_resource").text.contains("--uri"))
    }

    // ------------------------------ execute_code ------------------------------

    @Test
    fun `execute_code maps all flags`() {
        val command = parse(
            "execute_code",
            "--project_name=key-1",
            "--code-file=repro.kts",
            "--task_id=t1",
            "--reason=repro",
            "--modal=unleashed",
            "--timeout=120",
        )
        assertTrue(command is DevrigCommand.DevrigCommandExecuteCode)
        command as DevrigCommand.DevrigCommandExecuteCode
        assertEquals("key-1", command.projectName)
        assertEquals("repro.kts", command.codeFile)
        assertEquals("t1", command.taskId)
        assertEquals("repro", command.reason)
        assertEquals("unleashed", command.modal)
        assertEquals(120, command.timeout)
    }

    @Test
    fun `execute_code without project_name points at list_projects`() {
        val err = parseError("execute_code", "--code-file=x.kts", "--task_id=t", "--reason=r")
        assertTrue(err.text.contains("--project_name"), err.text)
        assertTrue(err.text.contains("list_projects"), err.text)
    }

    @Test
    fun `execute_code without code shows a code-file example`() {
        val err = parseError("execute_code", "--project_name=k", "--task_id=t", "--reason=r")
        assertTrue(err.text.contains("--code-file"), err.text)
    }

    @Test
    fun `execute_code rejects both code and code-file`() {
        val err = parseError("execute_code", "--project_name=k", "--task_id=t", "--reason=r", "--code=x", "--code-file=y")
        assertTrue(err.text.contains("only one of"), err.text)
    }

    @Test
    fun `execute_code rejects a non-positive timeout`() {
        for (bad in listOf("0", "-1")) {
            val err = parseError("execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--timeout=$bad")
            assertTrue(err.text.contains("timeout"), err.text)
            assertTrue(err.text.contains("positive"), err.text)
        }
    }

    // ------------------------------ listings ------------------------------

    @Test
    fun `list_projects and list_windows parse with --json`() {
        assertTrue(parse("list_projects", "--json") is DevrigCommand.DevrigCommandListProjects)
        assertTrue((parse("list_projects", "--json") as DevrigCommand.DevrigCommandListProjects).json)
        assertTrue(parse("list_windows") is DevrigCommand.DevrigCommandListWindows)
    }

    // ------------------------------ open_project ------------------------------

    @Test
    fun `open_project maps path and --wait`() {
        val command = parse("open_project", "--project_path=/abs/p", "--task_id=t", "--reason=r", "--wait")
        assertTrue(command is DevrigCommand.DevrigCommandOpenProject)
        command as DevrigCommand.DevrigCommandOpenProject
        assertEquals("/abs/p", command.projectPath)
        assertTrue(command.wait)
        assertTrue(command.trustProject, "trust_project defaults to true")
    }

    @Test
    fun `open_project without project_path is a parse error`() {
        assertTrue(parseError("open_project", "--task_id=t", "--reason=r").text.contains("--project_path"))
    }

    // ------------------------------ take_screenshot / input ------------------------------

    @Test
    fun `take_screenshot maps --out and --window_id`() {
        val command = parse("take_screenshot", "--project_name=k", "--task_id=t", "--reason=r", "--window_id=w1", "--out=shot.png")
        assertTrue(command is DevrigCommand.DevrigCommandScreenshot)
        command as DevrigCommand.DevrigCommandScreenshot
        assertEquals("w1", command.windowId)
        assertEquals("shot.png", command.out)
    }

    @Test
    fun `input requires a sequence and window_id`() {
        assertTrue(parseError("input", "--project_name=k", "--task_id=t", "--reason=r").text.contains("--window_id"))
        val missingSeq = parseError("input", "--project_name=k", "--window_id=w", "--task_id=t", "--reason=r")
        assertTrue(missingSeq.text.contains("--sequence"), missingSeq.text)
    }

    // ------------------------------ execute_feedback ------------------------------

    @Test
    fun `execute_feedback maps rating and explanation`() {
        val command = parse(
            "execute_feedback",
            "--project_name=k",
            "--task_id=t",
            "--success_rating=0.9",
            "--explanation=good",
            "--execution_id=e1",
        )
        assertTrue(command is DevrigCommand.DevrigCommandFeedback)
        command as DevrigCommand.DevrigCommandFeedback
        assertEquals(0.9, command.successRating)
        assertEquals("good", command.explanation)
        assertEquals("e1", command.executionId)
    }

    @Test
    fun `execute_feedback rejects out-of-range rating`() {
        val err = parseError("execute_feedback", "--project_name=k", "--task_id=t", "--success_rating=2.0", "--explanation=x")
        assertTrue(err.text.contains("out of range"), err.text)
    }

    @Test
    fun `execute_feedback without rating shows an example`() {
        val err = parseError("execute_feedback", "--project_name=k", "--task_id=t", "--explanation=x")
        assertTrue(err.text.contains("--success_rating"), err.text)
    }

    @Test
    fun `execute_feedback still accepts --execution_id (MCP parity, contextual only)`() {
        // #8: the flag is kept for parity with steroid_execute_feedback; it parses onto the command but
        // FeedbackParams has no such field, so it is never forwarded (asserted in the glue tests).
        val command = parse("execute_feedback", "--project_name=k", "--task_id=t", "--success_rating=0.5",
            "--explanation=x", "--execution_id=e-42") as DevrigCommand.DevrigCommandFeedback
        assertEquals("e-42", command.executionId)
    }

    // ------------------------------ #2: --modal validation at parse ------------------------------

    @Test
    fun `execute_code rejects an unknown --modal value at parse`() {
        val err = parseError("execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=bogus")
        assertTrue(err.text.contains("invalid --modal"), err.text)
        assertTrue(err.text.contains("smart_non_modal"), "lists valid values: ${err.text}")
    }

    @Test
    fun `execute_code accepts each valid --modal value`() {
        for (wire in listOf("smart_non_modal", "non_modal", "unleashed")) {
            val command = parse("execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=$wire")
            assertTrue(command is DevrigCommand.DevrigCommandExecuteCode, "modal=$wire should parse: $command")
        }
    }

    // ------------------------------ #7: reliable parse-error command name ------------------------------

    @Test
    fun `parse error recovers the command name with a global flag before the subcommand`() {
        assertEquals("execute_code", parseError("--json", "execute_code").commandName)
    }

    @Test
    fun `parse error recovers the command name with a global flag after the subcommand`() {
        assertEquals("execute_code", parseError("execute_code", "--json").commandName)
    }

    @Test
    fun `parse error recovers the command name when an option value is a separate token`() {
        // `--project_name mykey` (space-separated); the value token must not be mistaken for the command.
        assertEquals("execute_code", parseError("execute_code", "--project_name", "mykey").commandName)
    }

    @Test
    fun `parse error echoes an unknown command name`() {
        assertEquals("frobnicate", parseError("frobnicate").commandName)
    }

    @Test
    fun `parse error on an unknown option keeps the subcommand name`() {
        val err = parseError("list_projects", "--nope")
        assertEquals("list_projects", err.commandName)
        assertTrue(err.message.contains("no such option"), err.message)
    }

    @Test
    fun `recoverCommandName is deterministic over flags, values, unknowns`() {
        assertEquals("execute_code", recoverCommandName(arrayOf("--debug", "execute_code", "--project_name", "input")))
        assertEquals("backend", recoverCommandName(arrayOf("backend", "download", "id1")))
        assertEquals("devrig", recoverCommandName(arrayOf("--json")))
    }
}
