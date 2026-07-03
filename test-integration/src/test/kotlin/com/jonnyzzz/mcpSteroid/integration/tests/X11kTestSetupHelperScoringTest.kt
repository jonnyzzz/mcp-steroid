/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD unit tests for [scoreTestSetupHelper] — the evidence-based scorer of the x11k "replace the
 * copy-pasted test setup with a shared helper" A/B experiment (`x11k__test_setup_helper`).
 *
 * Ground truth at the pinned x11k commit cfdf1f7d: `XSyncProtocolTest.kt` boots the in-process
 * X server with the same 6-line block in every one of its 15 test methods; the block starts
 * (`XServer(ServerOptions(`) at 15 known line numbers. The agent must extract a shared helper and
 * migrate the call sites, reporting `MIGRATED: <file>:<line>` with the ORIGINAL line of each
 * migrated block — matched against the hand-derived list with a small tolerance and a spam cap,
 * so inventing line numbers or shotgunning every line of the file cannot win.
 */
class X11kTestSetupHelperScoringTest {

    /** `grep -n "XServer(ServerOptions(" XSyncProtocolTest.kt` at x11k commit cfdf1f7d. */
    private val expectedLines = setOf(14, 110, 163, 198, 240, 272, 302, 334, 387, 429, 484, 517, 574, 612, 650)

    private fun score(output: String) = scoreTestSetupHelper(
        output = output,
        expectedFile = "XSyncProtocolTest.kt",
        expectedLines = expectedLines,
        minMigrated = 13,
        lineTolerance = 5,
    )

    private fun migratedLines(lines: Collection<Int>, file: String = "src/test/kotlin/org/jonnyzzz/xserver/XSyncProtocolTest.kt") =
        lines.joinToString("\n") { "MIGRATED: $file:$it" }

    @Test
    fun `all call sites migrated with helper and green tests is safe`() {
        val output = """
            HELPER_CREATED: src/test/kotlin/org/jonnyzzz/xserver/XServerTestHarness.kt
            ${migratedLines(expectedLines)}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertTrue(s.helperCreated)
        assertEquals(15, s.matchedCount)
        assertEquals(true, s.testsGreen)
        assertTrue(s.safe)
    }

    @Test
    fun `lines drifted within tolerance still match`() {
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(expectedLines.map { it + 3 })}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertEquals(15, s.matchedCount)
        assertTrue(s.safe)
    }

    @Test
    fun `too few migrated call sites is not safe`() {
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(listOf(14, 110, 163, 198, 240))}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertEquals(5, s.matchedCount)
        assertFalse(s.safe)
    }

    @Test
    fun `shotgunning every 10th line cannot win`() {
        // 66 fabricated markers covering the whole file: enough to hit every expected line by
        // accident, but the spam cap (3x expected) rejects the answer.
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines((1..660 step 10).toList())}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertTrue(s.reportedCount > 45)
        assertFalse(s.migrated)
        assertFalse(s.safe)
    }

    @Test
    fun `hallucinated line numbers do not match`() {
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(listOf(30, 75, 130, 260, 320, 410, 470, 530, 590, 700, 720, 740, 760))}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertTrue(s.matchedCount < 13)
        assertFalse(s.safe)
    }

    @Test
    fun `missing helper marker is not safe`() {
        val output = """
            ${migratedLines(expectedLines)}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertFalse(s.helperCreated)
        assertFalse(s.safe)
    }

    @Test
    fun `red tests after change is not safe`() {
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(expectedLines)}
            TESTS_AFTER_CHANGE: FAILURE
        """.trimIndent()
        val s = score(output)
        assertEquals(false, s.testsGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `markers for a different file do not count`() {
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(expectedLines, file = "src/test/kotlin/org/jonnyzzz/xserver/XRandrProtocolTest.kt")}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertEquals(0, s.matchedCount)
        assertFalse(s.safe)
    }

    @Test
    fun `absolute container paths and markdown wrapping are tolerated`() {
        val output = """
            **HELPER_CREATED**: `src/test/kotlin/org/jonnyzzz/xserver/XServerTestHarness.kt`
            ${expectedLines.joinToString("\n") { "- **MIGRATED**: `/home/agent/project/src/test/kotlin/org/jonnyzzz/xserver/XSyncProtocolTest.kt:$it`" }}
            **TESTS_AFTER_CHANGE**: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertTrue(s.helperCreated)
        assertEquals(15, s.matchedCount)
        assertTrue(s.safe)
    }

    @Test
    fun `each expected line consumes at most one reported line`() {
        // 13 reports all pointing at the same call site must count as ONE match, not 13.
        val output = """
            HELPER_CREATED: XServerTestHarness.kt
            ${migratedLines(List(13) { 14 })}
            TESTS_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = score(output)
        assertEquals(1, s.matchedCount)
        assertFalse(s.safe)
    }
}
