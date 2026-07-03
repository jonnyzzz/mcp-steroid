/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreSsrOptionalGet] — the found/missed/false-positive verdict for the
 * youtrackdb structural-search A/B. The whole point: SSR with an `exprtype(java.util.Optional…)`
 * constraint is AST-exact, while `grep '.get()'` over-matches every no-arg get() in the codebase
 * (AtomicLong, ThreadLocal, Supplier, Future, WeakReference, ByteBuffer, Netty Attribute, gremlin
 * Traverser, …) and misses chained receivers it cannot type. No IDE/Docker — scored on text only.
 */
class SsrOptionalGetScoringTest {

    // A toy ground truth that mirrors the youtrackdb case: three files known to contain
    // Optional.get() callsites at the pinned revision.
    private val truth = setOf(
        "core/src/main/java/com/example/tx/TransactionId.java",
        "server/src/main/java/com/example/gremlin/OpProcessor.java",
        "core/src/test/java/com/example/gremlin/CountStrategyTest.java",
    )

    @Test
    fun `exact answer scores exact`() {
        val output = """
            OPTIONAL_GET_MATCHES: 4
            MATCH: core/src/main/java/com/example/tx/TransactionId.java:29
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java:484
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java:494
            MATCH: core/src/test/java/com/example/gremlin/CountStrategyTest.java:37
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertTrue(s.exact, "missed=${s.missedFiles} falsePos=${s.falsePositiveFiles}")
        assertEquals(truth, s.foundFiles)
        assertEquals(emptySet<String>(), s.missedFiles)
        assertEquals(emptySet<String>(), s.falsePositiveFiles)
        assertEquals(4, s.reportedCount)
    }

    @Test
    fun `grep-style over-match scores false positives`() {
        // grep '.get()' also flags a ByteBuffer.get() file — a false positive SSR would never produce.
        val output = """
            OPTIONAL_GET_MATCHES: 5
            MATCH: core/src/main/java/com/example/tx/TransactionId.java:29
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java:484
            MATCH: core/src/test/java/com/example/gremlin/CountStrategyTest.java:37
            MATCH: core/src/main/java/com/example/wal/DoubleWriteLog.java:390
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertFalse(s.exact)
        assertEquals(setOf("core/src/main/java/com/example/wal/DoubleWriteLog.java"), s.falsePositiveFiles)
        assertEquals(emptySet<String>(), s.missedFiles)
    }

    @Test
    fun `grep-style under-match scores missed files`() {
        // grep misses the chained findFirst().get() in the test file — a classic type-blind miss.
        val output = """
            OPTIONAL_GET_MATCHES: 2
            MATCH: core/src/main/java/com/example/tx/TransactionId.java:29
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java:484
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertFalse(s.exact)
        assertEquals(setOf("core/src/test/java/com/example/gremlin/CountStrategyTest.java"), s.missedFiles)
        assertEquals(emptySet<String>(), s.falsePositiveFiles)
    }

    @Test
    fun `absolute container paths and markdown formatting are normalized`() {
        // Agents report absolute in-container paths, backtick-quote them, and bold the marker.
        val output = """
            **MATCH:** `/home/agent/project/core/src/main/java/com/example/tx/TransactionId.java:29`
            MATCH: `/home/agent/project/server/src/main/java/com/example/gremlin/OpProcessor.java:484`
            - MATCH: /home/agent/project/core/src/test/java/com/example/gremlin/CountStrategyTest.java:37
            OPTIONAL_GET_MATCHES: 3
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertTrue(s.exact, "missed=${s.missedFiles} falsePos=${s.falsePositiveFiles}")
        assertEquals(3, s.reportedCount)
    }

    @Test
    fun `a shorter but unambiguous relative path still counts`() {
        val output = """
            MATCH: com/example/tx/TransactionId.java:29
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java:484
            MATCH: core/src/test/java/com/example/gremlin/CountStrategyTest.java:37
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertEquals(emptySet<String>(), s.missedFiles)
        assertEquals(emptySet<String>(), s.falsePositiveFiles)
    }

    @Test
    fun `windows-style separators and a missing line number are tolerated`() {
        val output = """
            MATCH: core\src\main\java\com\example\tx\TransactionId.java
            MATCH: server/src/main/java/com/example/gremlin/OpProcessor.java
            MATCH: core/src/test/java/com/example/gremlin/CountStrategyTest.java:37
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertEquals(emptySet<String>(), s.missedFiles)
    }

    @Test
    fun `duplicate MATCH lines for the same file do not inflate anything`() {
        val output = """
            MATCH: core/src/main/java/com/example/tx/TransactionId.java:29
            MATCH: core/src/main/java/com/example/tx/TransactionId.java:41
        """.trimIndent()
        val s = scoreSsrOptionalGet(output, truth)
        assertEquals(setOf("core/src/main/java/com/example/tx/TransactionId.java"), s.foundFiles)
        assertEquals(1, s.reportedFiles.size)
    }

    @Test
    fun `no MATCH lines at all scores everything missed and no count`() {
        val s = scoreSsrOptionalGet("I could not find any Optional.get() calls.", truth)
        assertFalse(s.exact)
        assertEquals(truth, s.missedFiles)
        assertEquals(null, s.reportedCount)
    }

    @Test
    fun `OPTIONAL_GET_MATCHES count is parsed from decorated lines too`() {
        val s = scoreSsrOptionalGet("**OPTIONAL_GET_MATCHES**: 36", truth)
        assertEquals(36, s.reportedCount)
    }
}
