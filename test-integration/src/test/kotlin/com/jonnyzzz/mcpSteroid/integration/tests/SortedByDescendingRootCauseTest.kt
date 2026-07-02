/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [scoreSortedByDescendingRootCause] — the DebuggerDemoTest gate for the "unit test via
 * debugger" scenario. The two REGRESSION cases are the verbatim ROOT_CAUSE texts Claude (Opus 4.8)
 * produced on CI builds 988635686 (2026-06-30) and 991971410 (2026-07-02): both semantically perfect
 * (the second even carries debugger identity evidence and the exact fix) yet rejected by the old raw
 * substring patterns — markdown backticks broke `new sorted list` ("NEW sorted `List`") and inserted
 * words broke `original list` ("the original `players` list"). Scoring must normalize markdown before
 * matching.
 */
class SortedByDescendingRootCauseTest {

    // Verbatim from CI build 991971410 (2026-07-02).
    private val ci20260702 =
        "sortedByDescending` is a non-mutating extension that returns a NEW sorted `List` and leaves " +
            "the original `players` list untouched. On line 9 the return value of " +
            "`players.sortedByDescending { it.score }` is ignored — it is never assigned back to " +
            "`players` (nor is `sortByDescending`, the in-place variant, used). So line 10 " +
            "`return players` returns the original, still-unsorted list."

    // Verbatim from CI build 988635686 (2026-06-30).
    private val ci20260630 =
        "sortedByDescending` is a non-mutating Kotlin extension that returns a NEW sorted `List` and " +
            "leaves the receiver untouched (debugger confirmed `sortedByDescending(...) === players` is " +
            "`false`). On line 9 the return value is ignored / never assigned back to `players`, so " +
            "line 10 `return players` returns the original unsorted list."

    @Test
    fun `accepts the real CI answers that the raw substring patterns rejected`() {
        for (rootCause in listOf(ci20260702, ci20260630)) {
            val score = scoreSortedByDescendingRootCause(rootCause)
            assertTrue(score.mentionsIgnoredReturn, "ignored-return should match:\n$rootCause")
            assertTrue(score.mentionsNewList, "new-list should match:\n$rootCause")
            assertTrue(score.pass, "must pass:\n$rootCause")
        }
    }

    @Test
    fun `accepts a plain unformatted correct explanation`() {
        val score = scoreSortedByDescendingRootCause(
            "sortedByDescending returns a new list; the return value is ignored and never assigned back."
        )
        assertTrue(score.pass)
    }

    @Test
    fun `rejects an explanation that misses the new-list half`() {
        val score = scoreSortedByDescendingRootCause(
            "The return value on line 9 is ignored, so the list stays unsorted."
        )
        assertTrue(score.mentionsIgnoredReturn)
        assertFalse(score.mentionsNewList)
        assertFalse(score.pass)
    }

    @Test
    fun `rejects an explanation that misses the ignored-return half`() {
        val score = scoreSortedByDescendingRootCause(
            "sortedByDescending creates a new sorted list instead of sorting in place."
        )
        assertTrue(score.mentionsNewList)
        assertFalse(score.mentionsIgnoredReturn)
        assertFalse(score.pass)
    }

    @Test
    fun `rejects a wrong root cause entirely`() {
        val score = scoreSortedByDescendingRootCause(
            "The comparator is inverted: it.score should be negated for descending order."
        )
        assertFalse(score.pass)
    }
}
