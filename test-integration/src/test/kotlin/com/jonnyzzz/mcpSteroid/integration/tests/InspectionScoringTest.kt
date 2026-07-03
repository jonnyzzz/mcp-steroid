/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD unit tests for [scoreInspections] — the redesigned ground-truth-line scorer for the Keycloak
 * inspections A/B (jonnyzzz/mcp-steroid#169).
 *
 * Why the redesign: on CI builds 991971406/991971408 the OLD scorer graded `detected` from the
 * self-reported `ISSUES_FOUND` count alone. Both with-MCP legs ran IntelliJ's RedundantCast
 * inspection correctly and truthfully reported `ISSUES_FOUND: 0` (the old target file had none —
 * classic `instanceof` does not narrow types in Java, so its casts are REQUIRED) and scored
 * `detected=false`; meanwhile the codex without-MCP leg reported "ISSUES_FOUND: 17" with ZERO tool
 * calls — a pure hallucination — and scored `detected=true`. The scorer now checks the REPORTED
 * `file:line` pairs against known ground truth (derived via `javac -Xlint:cast` on the pinned
 * Keycloak 26.6.4 tag), with a spam guard, so a made-up or shotgunned answer cannot win.
 */
class InspectionScoringTest {

    /** The real ground truth of the scenario: `javac -Xlint:cast` on Keycloak 26.6.4. */
    private val expected = mapOf(
        "ActionTokenContext.java" to setOf(149),
        "ResourceAdminManager.java" to setOf(374),
        "SpnegoAuthenticator.java" to setOf(112),
        "SamlAbstractMetadataPublicKeyLoader.java" to setOf(107),
    )

    @Test
    fun `correct answer with matching file-line pairs is detected`() {
        val output = """
            I ran the RedundantCast inspection on every candidate file.
            ISSUES_FOUND: 4
            ISSUE: services/src/main/java/org/keycloak/authentication/actiontoken/ActionTokenContext.java:149 — Casting to 'String' is redundant
            ISSUE: services/src/main/java/org/keycloak/services/managers/ResourceAdminManager.java:374 — Casting to 'LoginProtocol' is redundant
            ISSUE: services/src/main/java/org/keycloak/authentication/authenticators/browser/SpnegoAuthenticator.java:112 — Casting to 'String' is redundant
            ISSUE: services/src/main/java/org/keycloak/protocol/saml/SamlAbstractMetadataPublicKeyLoader.java:107 — Casting to 'List<XMLStructure>' is redundant
            TOOL_EVIDENCE: execution_id: eid_x
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(4, score.issuesFound)
        assertEquals(4, score.matchedCount)
        assertEquals(4, score.reportedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `hallucinated lines are NOT detected even with a big ISSUES_FOUND count`() {
        // Real failure shape: codex+none on CI build 991971408 reported 17 issues in 17s with zero
        // tool calls — lines it never looked at cannot match ground truth.
        val output = buildString {
            appendLine("ISSUES_FOUND: 17")
            for (line in listOf(100, 113, 117, 133, 137, 153, 157, 173, 177, 193, 197, 213, 217, 233, 237, 253, 257)) {
                appendLine("ISSUE: server-spi/src/main/java/org/keycloak/validate/ValidatorConfig.java:$line — redundant cast after instanceof")
            }
        }
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(17, score.issuesFound)
        assertEquals(0, score.matchedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `off-by-one line numbers still match (multi-line expressions)`() {
        val output = """
            ISSUES_FOUND: 4
            ISSUE: ActionTokenContext.java:150 — redundant cast
            ISSUE: ResourceAdminManager.java:373 — redundant cast
            ISSUE: SpnegoAuthenticator.java:112 — redundant cast
            ISSUE: SamlAbstractMetadataPublicKeyLoader.java:108 — redundant cast
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(4, score.matchedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `markdown-formatted issue lines are parsed (backticks, bold, bullets)`() {
        // Agents love wrapping paths in backticks — raw substring matching broke scorers before
        // (see scoreSortedByDescendingRootCause). Normalize before parsing.
        val output = """
            **ISSUES_FOUND:** 3
            - ISSUE: `services/src/main/java/org/keycloak/authentication/actiontoken/ActionTokenContext.java:149` — *redundant* cast
            - **ISSUE**: **ResourceAdminManager.java:374** — redundant cast
            - ISSUE: `SpnegoAuthenticator.java:112`
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(3, score.issuesFound)
        assertEquals(3, score.matchedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `too few matches is not detected`() {
        val output = """
            ISSUES_FOUND: 2
            ISSUE: ActionTokenContext.java:149 — redundant cast
            ISSUE: ResourceAdminManager.java:374 — redundant cast
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(2, score.matchedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `truthful zero-issue answer is not detected but parses cleanly`() {
        val output = """
            The inspection ran successfully and reported zero redundant casts.
            ISSUES_FOUND: 0
            TOOL_EVIDENCE: execution_id: eid_y
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(0, score.issuesFound)
        assertEquals(0, score.reportedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `right line in the wrong file does not count`() {
        val output = """
            ISSUES_FOUND: 4
            ISSUE: SamlService.java:149 — redundant cast
            ISSUE: ValidatorConfig.java:374 — redundant cast
            ISSUE: LoginActionsService.java:112 — redundant cast
            ISSUE: SpnegoAuthenticator.java:112 — redundant cast
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(1, score.matchedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `spamming every cast line in every candidate file is not detected`() {
        // A shotgun answer that lists dozens of cast lines (including, by luck, the true ones) must
        // not win: the guard rejects answers reporting more than 3x the ground-truth count.
        val output = buildString {
            appendLine("ISSUES_FOUND: 40")
            appendLine("ISSUE: ActionTokenContext.java:149 — cast")
            appendLine("ISSUE: ResourceAdminManager.java:374 — cast")
            appendLine("ISSUE: SpnegoAuthenticator.java:112 — cast")
            appendLine("ISSUE: SamlAbstractMetadataPublicKeyLoader.java:107 — cast")
            for (line in 1..36) appendLine("ISSUE: ValidatorConfig.java:${line * 7} — cast")
        }
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(4, score.matchedCount)
        assertEquals(40, score.reportedCount)
        assertFalse(score.detected)
    }

    @Test
    fun `a few extra findings beyond ground truth are tolerated`() {
        // IntelliJ's RedundantCast can legitimately find slightly more than javac -Xlint:cast; a
        // superset answer within the spam cap still counts.
        val output = """
            ISSUES_FOUND: 6
            ISSUE: ActionTokenContext.java:149 — redundant cast
            ISSUE: ResourceAdminManager.java:374 — redundant cast
            ISSUE: SpnegoAuthenticator.java:112 — redundant cast
            ISSUE: SamlAbstractMetadataPublicKeyLoader.java:107 — redundant cast
            ISSUE: SamlService.java:210 — redundant cast
            ISSUE: Reflections.java:88 — redundant cast
        """.trimIndent()
        val score = scoreInspections(output, expected, minMatches = 3)
        assertEquals(4, score.matchedCount)
        assertEquals(6, score.reportedCount)
        assertTrue(score.detected)
    }

    @Test
    fun `each reported line is consumed by at most one expected line`() {
        // 113 may satisfy expected 112 OR 114, never both.
        val output = """
            ISSUES_FOUND: 1
            ISSUE: F.java:113 — redundant cast
        """.trimIndent()
        val score = scoreInspections(output, mapOf("F.java" to setOf(112, 114)), minMatches = 2)
        assertEquals(1, score.matchedCount)
        assertFalse(score.detected)
    }
}
