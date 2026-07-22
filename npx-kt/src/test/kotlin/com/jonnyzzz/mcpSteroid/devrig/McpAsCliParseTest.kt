/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        // The positional-`<uri>` alias selects the SAME RunTool the canonical fetch_resource command does,
        // reporting command:"prompt" (issue #284).
        val command = parse("prompt", "mcp-steroid://x/y")
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_fetch_resource", command.toolName)
        assertEquals("prompt", command.commandName)
        assertEquals("mcp-steroid://x/y", command.arguments["uri"]?.jsonPrimitive?.content)
        assertFalse(command.arguments.containsKey("project_name"))
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
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_fetch_resource", command.toolName)
        assertEquals("fetch_resource", command.commandName)
        assertEquals("mcp-steroid://a", command.arguments["uri"]?.jsonPrimitive?.content)
        assertEquals("proj-1", command.arguments["project_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `fetch_resource without --uri is a parse error`() {
        assertTrue(parseError("fetch_resource").text.contains("--uri"))
    }

    // ------------------------------ execute_code ------------------------------

    @Test
    fun `execute_code maps all flags`() {
        // Schema-generated command (issue #284): typed schema flags land in RunTool.arguments, and the
        // CLI-only --code-file rides in extras. The `code` body is synthesized from --code-file at runtime.
        val command = parse(
            "execute_code",
            "--project_name=key-1",
            "--code-file=repro.kts",
            "--task_id=t1",
            "--reason=repro",
            "--modal=unleashed",
            "--timeout=120",
        )
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_execute_code", command.toolName)
        assertEquals("execute_code", command.commandName)
        assertEquals("key-1", command.arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("repro.kts", command.extras.codeFile)
        assertEquals("t1", command.arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("repro", command.arguments["reason"]?.jsonPrimitive?.content)
        assertEquals("unleashed", command.arguments["modal"]?.jsonPrimitive?.content)
        assertEquals(120, command.arguments["timeout"]?.jsonPrimitive?.int)
    }

    @Test
    fun `execute_code without project_name parses successfully, resolved later from cwd (#266 p2)`() {
        // --project_name is optional at parse time (issue #266): a missing value is no longer a parse
        // error here — it is resolved against the current directory at runtime by requireProjectName in
        // runGeneratedToolCommand, which fails with a candidate-listing USAGE error (see
        // ExecuteCodeCommandTest's cwd-inference tests) only when the cwd doesn't uniquely match one open
        // project. Parsing must succeed and omit project_name so that resolution point runs.
        val command = parse("execute_code", "--code-file=x.kts", "--task_id=t", "--reason=r")
        assertTrue(command is DevrigCommand.RunTool, "expected a parsed command, got $command")
        assertFalse((command as DevrigCommand.RunTool).arguments.containsKey("project_name"))
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
        // Both listers are now schema-generated: they parse into the inert RunTool carrying the canonical
        // tool name, the typed command name, and the --json flag (issue #284).
        val projects = parse("list_projects", "--json") as DevrigCommand.RunTool
        assertEquals("steroid_list_projects", projects.toolName)
        assertEquals("list_projects", projects.commandName)
        assertTrue(projects.json)
        val windows = parse("list_windows") as DevrigCommand.RunTool
        assertEquals("steroid_list_windows", windows.toolName)
        assertEquals("list_windows", windows.commandName)
        assertFalse(windows.json)
    }

    // ------------------------------ parse/runtime boundary ------------------------------

    @Test
    fun `parsing is inert - it only routes and types tokens into a value object`() {
        // The parse phase must never create a service, backend, or handler: DevrigServices is built after
        // parsing (see Main.runCli). A parsed command is therefore a plain data object carrying only the
        // typed tokens — pinned here by data-class equality with a freshly constructed instance.
        assertEquals(
            DevrigCommand.RunTool(
                toolName = "steroid_list_windows", commandName = "list_windows",
                arguments = JsonObject(emptyMap()), json = true,
            ),
            parse("list_windows", "--json"),
        )
        assertEquals(
            DevrigCommand.RunTool(
                toolName = "steroid_fetch_resource", commandName = "fetch_resource",
                arguments = buildJsonObject { put("uri", "mcp-steroid://a") },
            ),
            parse("fetch_resource", "--uri=mcp-steroid://a"),
        )
    }

    // ------------------------------ open_project ------------------------------

    @Test
    fun `open_project maps path and --wait`() {
        val command = parse("open_project", "--project_path=/abs/p", "--task_id=t", "--reason=r", "--wait")
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_open_project", command.toolName)
        assertEquals("/abs/p", command.arguments["project_path"]?.jsonPrimitive?.content)
        assertTrue(command.extras.wait)
        // trust_project is a nullableFlag: an omitted flag stays absent so the tool default (true) owned by
        // OpenProjectToolSpec.call() is preserved — a CLI-synthesized false must never be serialized (#284).
        assertFalse(command.arguments.containsKey("trust_project"), "absent trust_project must not be serialized")
    }

    @Test
    fun `open_project without project_path is a parse error`() {
        assertTrue(parseError("open_project", "--task_id=t", "--reason=r").text.contains("--project_path"))
    }

    // ------------------------------ take_screenshot / input ------------------------------

    @Test
    fun `take_screenshot maps --out and --window_id`() {
        val command = parse("take_screenshot", "--project_name=k", "--task_id=t", "--reason=r", "--window_id=w1", "--out=shot.png")
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_take_screenshot", command.toolName)
        assertEquals("w1", command.arguments["window_id"]?.jsonPrimitive?.content)
        // --out has no MCP-schema parameter, so it rides in the CLI-only extras, not the arguments JSON.
        assertEquals("shot.png", command.extras.out)
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
        assertTrue(command is DevrigCommand.RunTool)
        command as DevrigCommand.RunTool
        assertEquals("steroid_execute_feedback", command.toolName)
        assertEquals(0.9, command.arguments["success_rating"]?.jsonPrimitive?.double)
        assertEquals("good", command.arguments["explanation"]?.jsonPrimitive?.content)
        assertEquals("e1", command.arguments["execution_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute_feedback rejects out-of-range rating`() {
        // success_rating carries maximum=1.0; the schema-generated Clikt `restrictTo` rejects 2.0 at parse
        // (exit 64), never as a backend tool error. The message references the valid range.
        val err = parseError("execute_feedback", "--project_name=k", "--task_id=t", "--success_rating=2.0", "--explanation=x")
        assertTrue(err.text.contains("--success_rating"), err.text)
        assertTrue(err.text.contains("range"), err.text)
        // The concise `--json` envelope message must also name the flag (clikt keeps it in `paramName`, not
        // in the raw message), so an agent reading the envelope knows which flag was rejected.
        assertTrue(err.message.contains("--success_rating"), err.message)
    }

    @Test
    fun `execute_feedback without rating shows an example`() {
        val err = parseError("execute_feedback", "--project_name=k", "--task_id=t", "--explanation=x")
        assertTrue(err.text.contains("--success_rating"), err.text)
    }

    @Test
    fun `execute_feedback still accepts --execution_id (MCP parity, contextual only)`() {
        // #8: execution_id is a generic schema parameter kept for parity with steroid_execute_feedback; it
        // maps into the RunTool arguments but ExecuteFeedbackToolSpec.call() drops it when building
        // FeedbackParams, so it is never forwarded (asserted in the glue tests).
        val command = parse("execute_feedback", "--project_name=k", "--task_id=t", "--success_rating=0.5",
            "--explanation=x", "--execution_id=e-42") as DevrigCommand.RunTool
        assertEquals("e-42", command.arguments["execution_id"]?.jsonPrimitive?.content)
    }

    // ------------------------------ #2: --modal validation at parse ------------------------------

    @Test
    fun `execute_code rejects an unknown --modal value at parse`() {
        // --modal is a schema-generated Clikt `choice`, so a bad value is a parse-time BadParameterValue
        // (exit 64) that lists the valid choices — not a backend error.
        val err = parseError("execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=bogus")
        assertTrue(err.text.contains("--modal"), err.text)
        assertTrue(err.text.contains("smart_non_modal"), "lists valid values: ${err.text}")
        // The concise `--json` envelope message must also name the flag, not just the human `.text`.
        assertTrue(err.message.contains("--modal"), err.message)
    }

    @Test
    fun `execute_code accepts each valid --modal value`() {
        for (wire in listOf("smart_non_modal", "non_modal", "unleashed")) {
            val command = parse("execute_code", "--project_name=k", "--code=x", "--task_id=t", "--reason=r", "--modal=$wire")
            assertTrue(command is DevrigCommand.RunTool, "modal=$wire should parse: $command")
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
