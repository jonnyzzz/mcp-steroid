/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * #460 at the MCP boundary: a blank `code` used to reach the backend, burn the full pre-flight and
 * compilation plus an execution_id, and end in the misleading no-output hint. The devrig CLI refuses
 * blank `--code`/`--code-file`/stdin at parse/read time; this pins the guard that covers direct MCP
 * callers, so no transport can ship an empty script.
 */
class ExecuteCodeToolBlankCodeTest {

    private class RecordingHandler : ExecuteCodeToolHandler {
        var received: ExecCodeParams? = null

        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            received = execCodeParams
            return ToolCallResult.successTextResult("Success")
        }
    }

    private fun call(code: String): Pair<ToolCallResult, RecordingHandler> {
        val handler = RecordingHandler()
        val result = callToolSpecForTest(
            ExecuteCodeToolSpec { handler },
            buildJsonObject {
                put("project_name", "proj")
                put("code", code)
                put("task_id", "t-1")
                put("reason", "unit test")
            },
        )
        return result to handler
    }

    @Test
    fun `blank code is rejected with a focused error before the handler runs`() {
        // "\uFEFF": a pasted "empty" Windows file is a BOM-only payload; isEffectivelyBlank knows it.
        for (blank in listOf("", "   ", "\n\t", "\uFEFF", "\uFEFF \uFEFF")) {
            val (result, handler) = call(blank)
            assertTrue(result.isError, "blank code '${blank.replace("\n", "\\n")}' must be a tool error")
            val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
                .ifEmpty { fail("expected an error message") }
            assertTrue("code" in text && "blank" in text, "names the parameter and the cause: $text")
            assertFalse("Stacktrace" in text, "no internal stack trace in the tool error: $text")
            assertNull(handler.received, "the handler (pre-flight + compile) must never run for blank code")
        }
    }

    @Test
    fun `non-blank code reaches the handler unchanged`() {
        val (result, handler) = call("println(1)")
        assertFalse(result.isError)
        assertTrue(handler.received?.code == "println(1)")
    }

    @Test
    fun `a leading BOM is stripped before the script ships`() {
        // A pasted Windows file starts with U+FEFF; wrapped mid-file it would fail compilation with an
        // invisible-character error. Same normalization the CLI's file/stdin decoder applies.
        val (result, handler) = call("\uFEFF\uFEFFprintln(1)")
        assertFalse(result.isError)
        assertTrue(
            handler.received?.code == "println(1)",
            "the encoding artifact must not reach the compiler: '${handler.received?.code}'",
        )
    }
}
