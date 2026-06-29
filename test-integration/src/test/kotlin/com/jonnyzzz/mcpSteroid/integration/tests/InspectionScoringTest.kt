/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreInspections] — the verdict for the Keycloak inspections A/B. The win
 * shape: IntelliJ's semantic inspection finds the redundant casts after `instanceof` in
 * ValidatorConfig.java; grep cannot. No IDE/Docker/agent needed.
 */
class InspectionScoringTest {

    private val target = "ValidatorConfig.java"

    @Test
    fun `an MCP-style answer naming the file and enough redundant casts is detected`() {
        val out = """
            I ran the 'Redundant type cast' inspection on ValidatorConfig.java.
            ISSUES_FOUND: 13
            ISSUE: redundant cast to String at line 100
        """.trimIndent()
        assertTrue(scoreInspections(out, minIssues = 5, targetFile = target).detected)
    }

    @Test
    fun `a grep-style answer that cannot tell casts are redundant is not detected`() {
        val out = """
            I grepped for '(String)' casts in ValidatorConfig.java but cannot tell which are redundant.
            ISSUES_FOUND: 0
        """.trimIndent()
        assertFalse(scoreInspections(out, minIssues = 5, targetFile = target).detected)
    }

    @Test
    fun `too few issues does not count even if redundant cast is mentioned`() {
        val out = "ValidatorConfig.java\nredundant cast\nISSUES_FOUND: 2"
        val s = scoreInspections(out, minIssues = 5, targetFile = target)
        assertEquals(2, s.issuesFound)
        assertFalse(s.detected)
    }

    @Test
    fun `mentioning redundant casts in the wrong file does not count`() {
        val out = "redundant cast\nISSUES_FOUND: 10\n(in SomeOtherFile.java)"
        assertFalse(scoreInspections(out, minIssues = 5, targetFile = target).detected)
    }
}
