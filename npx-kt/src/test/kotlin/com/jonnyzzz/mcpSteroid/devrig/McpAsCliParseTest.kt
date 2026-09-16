/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parse-time shapes not already covered elsewhere: `SchemaCliBindingTest` proves the generic bounds and
 * requiredness mechanisms directly against `BindingCommand`, and `SchemaToolCliCommandTest` proves the
 * curated-missing-hint MECHANISM once (via `execute_code`'s `task_id`). This file is where a few
 * tool-specific parse failures are checked through the real [parseDevrigCommand] entry point: an enum
 * rejection whose valid-values listing must stay in sync with [com.jonnyzzz.mcpSteroid.server.ModalMode],
 * a floating-point bound against values ordinary comparisons mishandle (`NaN`/`Infinity`), and `input`'s
 * two hand-written, multi-line missing hints (unlike `execute_code`'s single-line one, these embed an
 * example invocation and are worth checking survive Clikt's `MultiUsageError` aggregation intact).
 */
class McpAsCliParseTest {

    private fun parseError(vararg args: String): String {
        val invocation = parseDevrigCommand(args.toList().toTypedArray())
        assertEquals("parse-error", invocation.commandPath)
        return requireNotNull(invocation.informationalText)
    }

    @Test
    fun `execute_code rejects an unknown --modal value at parse`() {
        val error = parseError(
            "execute_code", "--project_name=demo", "--code=x", "--task_id=t", "--reason=r",
            "--modal=bogus",
        )

        assertTrue("--modal" in error, "got:\n$error")
        assertTrue("bogus" in error, "got:\n$error")
        for (valid in listOf("smart_non_modal", "non_modal", "unleashed")) {
            assertTrue(valid in error, "expected the valid-values listing to include $valid; got:\n$error")
        }
    }

    @Test
    fun `execute_code rejects an explicit empty --code= at parse with the missing-code hint`() {
        // #460: an empty (or blank) --code= used to count as provided and ship the empty script to the
        // backend — a full compiler round-trip, a burned execution_id, and the misleading no-output
        // hint — while every sibling required parameter treats an empty string as missing. Blank inline
        // code must fail at parse time with the same curated missing-code hint as omitting it entirely.
        for (blank in listOf("--code=", "--code=   ")) {
            val error = parseError(
                "execute_code", "--project_name=demo", blank, "--task_id=t", "--reason=r",
            )
            assertTrue("missing code" in error, "expected the curated missing-code hint for '$blank'; got:\n$error")
            assertTrue("--code-file" in error, "the hint must offer the --code-file alternative; got:\n$error")
        }
    }

    @Test
    fun `execute_feedback rejects NaN and Infinity success_rating at parse`() {
        for (badValue in listOf("NaN", "Infinity", "-Infinity")) {
            val error = parseError(
                "execute_feedback", "--project_name=demo", "--task_id=t", "--explanation=e",
                "--success_rating=$badValue",
            )
            assertTrue("--success_rating" in error, "got:\n$error")
        }
    }

    @Test
    fun `input without --window_id and --sequence reports both curated hints in one error`() {
        val error = parseError("input", "--project_name=demo", "--task_id=t", "--reason=r")

        assertTrue(
            "missing required --window_id (get it from" in error,
            "expected input's curated window_id hint; got:\n$error",
        )
        assertTrue(
            "missing --sequence" in error && "press:CTRL+P" in error,
            "expected input's curated multi-line sequence hint with its example; got:\n$error",
        )
        assertFalse(
            "Any string works" in error,
            "task_id/reason are supplied here, so their unrelated hint must not appear; got:\n$error",
        )
    }
}
