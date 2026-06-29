/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreSortedByDescendingBug] — the mode-independent correctness verdict used
 * by the debugger A/B (with-MCP vs without-MCP). This is the "did the agent actually identify the bug"
 * score that feeds the dashboard's `agent_claimed_fix`, scored identically for both modes so the
 * comparison is fair. No IDE / Docker / agent key required.
 */
class DebuggerBugScoringTest {

    @Test
    fun `a correct answer scores bugFound = true`() {
        val output = """
            Some analysis...
            BUG_FOUND: yes
            BUG_LINE: val sorted = players.sortedByDescending { it.score }
            ROOT_CAUSE: sortedByDescending returns a new sorted list but the return value is ignored,
                        so the original unsorted list is used.
        """.trimIndent()
        val score = scoreSortedByDescendingBug(output)
        assertTrue(score.bugFound, "expected bugFound; reasons=${score.reasons}")
        assertEquals(emptyList<String>(), score.reasons)
    }

    @Test
    fun `a without-MCP agent that reasons it out (no debugger) still scores when correct`() {
        // The whole point of the baseline: a no-debugger agent can still be scored on correctness.
        val output = """
            I read the code and reasoned about it without running the debugger.
            BUG_LINE: players.sortedByDescending { it.score }
            ROOT_CAUSE: the call returns a new sorted copy and that result is not stored, so the
                        list is never actually reordered (sortedByDescending does not modify in place).
        """.trimIndent()
        assertTrue(scoreSortedByDescendingBug(output).bugFound)
    }

    @Test
    fun `an incomplete root cause does not score`() {
        val output = """
            BUG_LINE: players.sortedByDescending { it.score }
            ROOT_CAUSE: the list is sorted in the wrong order.
        """.trimIndent()
        val score = scoreSortedByDescendingBug(output)
        assertFalse(score.bugFound)
        assertTrue(score.reasons.any { it.contains("ignored", ignoreCase = true) })
    }

    @Test
    fun `the wrong-selector misdiagnosis does not score`() {
        val output = """
            BUG_LINE: players.sortedByDescending { it.score }
            ROOT_CAUSE: it uses it.first instead of it.score, and the new list return value is ignored.
        """.trimIndent()
        val score = scoreSortedByDescendingBug(output)
        assertFalse(score.bugFound, "wrong-selector claim must not pass")
        assertTrue(score.reasons.any { it.contains("it.first") })
    }

    @Test
    fun `missing markers do not score`() {
        assertFalse(scoreSortedByDescendingBug("I could not find the bug.").bugFound)
    }
}
