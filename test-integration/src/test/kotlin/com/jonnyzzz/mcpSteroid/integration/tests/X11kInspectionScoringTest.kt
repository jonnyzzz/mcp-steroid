/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD unit tests for [scoreKotlinInspections] — the Kotlin-file twin of [scoreInspections] (whose
 * `ISSUE:` parser only accepts `.java` paths). Used by the x11k inspections A/B
 * (`x11k__inspections`): ground truth is the set of `file:line` sites kotlinc's
 * `extraWarnings` (K2 `-Wextra`) flags at the pinned x11k commit cfdf1f7d — local `var`s that are
 * never reassigned / never read. Same evidence-based verdict as the Keycloak scorer: reported
 * `file:line` pairs are matched against ground truth (±1 tolerance, each consumed once) with a
 * spam cap, so a hallucinated `ISSUES_FOUND` count or shotgunning every `var` cannot win.
 */
class X11kInspectionScoringTest {

    /** kotlinc 2.4.0 `extraWarnings` on x11k @ cfdf1f7d — 9 distinct warning sites. */
    private val expected = mapOf(
        "X11Connection.kt" to setOf(4400),
        "XFramebuffer.kt" to setOf(1598, 1599, 1600, 1601, 1746, 1747, 1748, 1749),
    )

    @Test
    fun `correct answer with matching kt file-line pairs is detected`() {
        val output = buildString {
            appendLine("ISSUES_FOUND: 9")
            appendLine("ISSUE: src/main/kotlin/org/jonnyzzz/xserver/X11Connection.kt:4400 — 'var idOffset' is never reassigned")
            for (line in listOf(1598, 1599, 1600, 1601, 1746, 1747, 1748, 1749)) {
                appendLine("ISSUE: src/main/kotlin/org/jonnyzzz/xserver/XFramebuffer.kt:$line — unused local var")
            }
            appendLine("TOOL_EVIDENCE: execution_id: eid_x")
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertEquals(9, score.issuesFound)
        assertEquals(9, score.matchedCount)
        assertEquals(9, score.reportedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `finding only the XFramebuffer clusters still passes minMatches`() {
        val output = buildString {
            appendLine("ISSUES_FOUND: 8")
            for (line in listOf(1598, 1599, 1600, 1601, 1746, 1747, 1748, 1749)) {
                appendLine("ISSUE: src/main/kotlin/org/jonnyzzz/xserver/XFramebuffer.kt:$line — unused local var")
            }
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertEquals(8, score.matchedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `hallucinated big count with wrong lines is NOT detected`() {
        val output = buildString {
            appendLine("ISSUES_FOUND: 20")
            for (line in listOf(100, 200, 300, 400, 500, 600, 700, 800, 900)) {
                appendLine("ISSUE: src/main/kotlin/org/jonnyzzz/xserver/X11State.kt:$line — var could be val")
            }
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertEquals(0, score.matchedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `shotgunning every var line is rejected by the spam cap`() {
        // 40 reported pairs cover all true sites but exceed 3x the 9-line ground truth.
        val output = buildString {
            appendLine("ISSUES_FOUND: 40")
            for (line in 1590..1609) appendLine("ISSUE: XFramebuffer.kt:$line — var")
            for (line in 1740..1755) appendLine("ISSUE: XFramebuffer.kt:$line — var")
            for (line in listOf(4398, 4399, 4400, 4401)) appendLine("ISSUE: X11Connection.kt:$line — var")
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertTrue(score.reportedCount > 27)
        assertFalse(score.detected)
    }

    @Test
    fun `off-by-one line numbers are tolerated`() {
        val output = buildString {
            appendLine("ISSUES_FOUND: 9")
            appendLine("ISSUE: X11Connection.kt:4401 — var never reassigned")
            for (line in listOf(1597, 1599, 1600, 1601, 1746, 1747, 1748, 1750)) {
                appendLine("ISSUE: XFramebuffer.kt:$line — unused var")
            }
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertTrue(score.matchedCount >= 6)
        assertTrue(score.detected)
    }

    @Test
    fun `markdown wrapping and absolute paths are tolerated`() {
        val output = buildString {
            appendLine("**ISSUES_FOUND**: 9")
            appendLine("- **ISSUE**: `/home/agent/project/src/main/kotlin/org/jonnyzzz/xserver/X11Connection.kt:4400` — never reassigned")
            for (line in listOf(1598, 1599, 1600, 1601, 1746, 1747, 1748, 1749)) {
                appendLine("- **ISSUE**: `XFramebuffer.kt:$line` — unused")
            }
        }
        val score = scoreKotlinInspections(output, expected, minMatches = 6)
        assertEquals(9, score.matchedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `java paths still parse — the scorer is a superset of the java one`() {
        val output = """
            ISSUES_FOUND: 1
            ISSUE: services/src/main/java/org/keycloak/Foo.java:42 — redundant cast
        """.trimIndent()
        val score = scoreKotlinInspections(output, mapOf("Foo.java" to setOf(42)), minMatches = 1)
        assertEquals(1, score.matchedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `empty answer is NOT detected`() {
        val score = scoreKotlinInspections("I could not find any issues.", expected, minMatches = 6)
        assertEquals(0, score.reportedCount)
        assertFalse(score.detected)
    }
}
