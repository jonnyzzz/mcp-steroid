/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Unit tests for [ExecuteCodeToolSpec.call] argument validation.
 *
 * Issue #469: a non-positive `timeout` used to reach the backend, burn the
 * full pre-flight and compilation, and fail with a nonsensical
 * "Execution timed out after -N seconds". The tool boundary must reject it
 * with a focused error before the handler is ever invoked.
 *
 * Note the schema-layer parsing that runs before this validation:
 * `intOrNull` parses numeric string primitives (so `"timeout": "-5"` reaches
 * the range check as -5), while a non-integer value (`-5.0`, `"abc"`) parses
 * to null and falls back to the default — a generic int-param behavior,
 * outside this guard's scope.
 */
class ExecuteCodeToolHandlerTest {

    private class RecordingHandler : ExecuteCodeToolHandler {
        var receivedParams: ExecCodeParams? = null

        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            receivedParams = execCodeParams
            return ToolCallResult.successTextResult("Success")
        }
    }

    /** [error] is null on success; [received] is null when the handler was never invoked. */
    private data class ToolCall(val error: String?, val received: ExecCodeParams?)

    private fun callTool(args: JsonObject): ToolCall {
        val handler = RecordingHandler()
        val spec = ExecuteCodeToolSpec { handler }
        val context = ToolCallContext(
            params = ToolCallParams(name = spec.name, arguments = args),
            session = McpSession(),
            mcpProgressReporter = object : McpProgressReporter { override fun report(message: String) = Unit },
        )
        val result = runBlocking {
            try {
                spec.call(context)
            } catch (e: ToolCallErrorException) {
                e.toolCallResult
            }
        }
        val error = if (result.isError) {
            result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        } else {
            null
        }
        return ToolCall(error, handler.receivedParams)
    }

    private fun validArgs(configure: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("project_name", "proj")
        put("code", "println(\"hi\")")
        put("task_id", "t-1")
        put("reason", "unit test")
        configure()
    }

    @Test
    fun `valid args without timeout use the default and reach the handler`() {
        val call = callTool(validArgs())
        assertNull(call.error)
        assertEquals(600, call.received?.timeout, "omitted timeout falls back to the documented default")
    }

    @Test
    fun `explicit positive timeout reaches the handler unchanged`() {
        val call = callTool(validArgs { put("timeout", 1) })
        assertNull(call.error)
        assertEquals(1, call.received?.timeout)
    }

    @Test
    fun `timeout zero is rejected before the handler runs with a focused error`() {
        val call = callTool(validArgs { put("timeout", 0) })
        val err = call.error ?: fail("expected a focused tool error")
        assertTrue(err.contains("timeout"), "names the offending parameter: $err")
        assertTrue(err.contains("positive"), "states the allowed range: $err")
        assertTrue(err.contains("600"), "points at the default as the recovery: $err")
        assertFalse(err.contains("Stacktrace"), "no internal stack trace in the tool error: $err")
        assertFalse(err.contains("\tat "), "no internal stack trace in the tool error: $err")
        assertNull(call.received, "the handler (pre-flight + compile) must never run for an invalid timeout")
    }

    @Test
    fun `negative timeout is rejected before the handler runs`() {
        val call = callTool(validArgs { put("timeout", -5) })
        val err = call.error ?: fail("expected a focused tool error")
        assertTrue(err.contains("-5"), "echoes the rejected value: $err")
        assertNull(call.received, "the handler (pre-flight + compile) must never run for an invalid timeout")
    }

    @Test
    fun `string-encoded negative timeout is parsed and rejected, not defaulted`() {
        val call = callTool(validArgs { put("timeout", "-5") })
        val err = call.error ?: fail("expected a focused tool error")
        assertTrue(err.contains("-5"), "echoes the rejected value: $err")
        assertNull(call.received, "the handler (pre-flight + compile) must never run for an invalid timeout")
    }
}
