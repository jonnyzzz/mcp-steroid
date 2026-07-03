/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TDD unit tests for [scoreCompileTriage] — the verdict for the Keycloak compile-error triage A/B
 * (jonnyzzz/mcp-steroid#169 follow-up, time-to-green experiment).
 *
 * The scenario seeds 5 deterministic single-line compile errors across server-spi-private + services
 * via a patch applied at IDE start. The scorer is evidence-based (the lesson from the inspections
 * scorer, CI builds 991971406/991971408, where a hallucinated self-report won): each reported
 * `FIXED: <path>:<line>` is matched against the SEEDED sites by file simple name and line (±1),
 * with a spam guard, and the verdict additionally requires the agent-reported bounded build to be
 * green. Both legs (with/without MCP) are scored identically.
 */
class CompileTriageScoringTest {

    /** The real seeded sites of the Keycloak scenario (file simple name → line of the seeded edit). */
    private val seeded = mapOf(
        "JsonUtils.java" to setOf(152),
        "CredentialHelper.java" to setOf(77),
        "DenyAccessAuthenticator.java" to setOf(62),
        "ResetOTP.java" to setOf(140),
        "ConditionalUserAttributeValue.java" to setOf(39),
    )

    @Test
    fun `all five sites fixed and build green scores safe`() {
        val output = """
            ERRORS_FOUND: 5
            FIXED: server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java:152 — List<Integer> should be List<String> (splitClaimPath returns List<String>)
            FIXED: server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java:77 — return type must be ConfigurableAuthenticatorFactory, the common supertype of all three factory casts
            FIXED: services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java:62 — Boolean is not a covariant return for boolean requiresUser()
            FIXED: services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java:140 — getProvider has no (String, Class) overload; arguments were swapped
            FIXED: services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java:39 — getConfig() returns Map<String, String>
            BUILD_AFTER_FIX: SUCCESS
            TOOL_EVIDENCE: execution_id: eid_x
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(5, s.errorsFound)
        assertEquals(5, s.reportedCount)
        assertEquals(5, s.matchedCount)
        assertTrue(s.missingSites.isEmpty())
        assertEquals(true, s.buildGreen)
        assertTrue(s.allSitesFixed)
        assertTrue(s.safe)
    }

    @Test
    fun `fixing only the first module's errors is incomplete and unsafe`() {
        // The classic baseline trap: Maven stops at the first failing module (server-spi-private),
        // so a shell agent that does not re-iterate never even SEES the services errors.
        val output = """
            ERRORS_FOUND: 2
            FIXED: server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java:152 — wrong generic parameter
            FIXED: server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java:77 — wrong return type
            BUILD_AFTER_FIX: FAILURE
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(2, s.matchedCount)
        assertEquals(
            setOf("DenyAccessAuthenticator.java", "ResetOTP.java", "ConditionalUserAttributeValue.java"),
            s.missingSites.keys,
        )
        assertEquals(false, s.buildGreen)
        assertFalse(s.allSitesFixed)
        assertFalse(s.safe)
    }

    @Test
    fun `all sites fixed but red build is complete yet not safe`() {
        val output = """
            FIXED: JsonUtils.java:152 — a
            FIXED: CredentialHelper.java:77 — b
            FIXED: DenyAccessAuthenticator.java:62 — c
            FIXED: ResetOTP.java:140 — d
            FIXED: ConditionalUserAttributeValue.java:39 — e
            BUILD_AFTER_FIX: FAILURE — services still does not compile
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertTrue(s.allSitesFixed)
        assertEquals(false, s.buildGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `off-by-one line numbers still match`() {
        // javac reports the CredentialHelper error at the return statement (85) but the fix is the
        // declaration (77) — that stays a miss; genuine ±1 drift (e.g. 151 vs 152) is tolerated.
        val output = """
            FIXED: JsonUtils.java:151 — wrong generic
            FIXED: CredentialHelper.java:78 — wrong return type
            FIXED: DenyAccessAuthenticator.java:63 — boxed return
            FIXED: ResetOTP.java:139 — swapped args
            FIXED: ConditionalUserAttributeValue.java:40 — wrong map value type
            BUILD_AFTER_FIX: SUCCESS
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(5, s.matchedCount)
        assertTrue(s.safe)
    }

    @Test
    fun `patching over the symptom at a different line does not count as the seeded fix`() {
        // A cast at the return statement (line 85) makes CredentialHelper compile but is the wrong
        // repair (ClassCastException at runtime for FormAction/ClientAuthenticator factories) — it
        // is far from the seeded declaration line 77 and must not match.
        val output = """
            FIXED: CredentialHelper.java:85 — added a cast to AuthenticatorFactory
            FIXED: JsonUtils.java:152 — restored List<String>
            FIXED: DenyAccessAuthenticator.java:62 — boolean
            FIXED: ResetOTP.java:140 — argument order
            FIXED: ConditionalUserAttributeValue.java:39 — Map<String, String>
            BUILD_AFTER_FIX: SUCCESS
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(4, s.matchedCount)
        assertEquals(setOf("CredentialHelper.java"), s.missingSites.keys)
        assertFalse(s.allSitesFixed)
        assertFalse(s.safe)
    }

    @Test
    fun `markdown-formatted FIXED lines are parsed (backticks, bold, bullets)`() {
        // Agents love markdown — normalize before parsing (the exact failure mode that broke raw
        // substring scorers before, see scoreSortedByDescendingRootCause).
        val output = """
            **ERRORS_FOUND:** 5
            - FIXED: `server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java:152` — *wrong generic*
            - **FIXED**: **CredentialHelper.java:77** — wrong return type
            - FIXED: `DenyAccessAuthenticator.java:62`
            - FIXED: `ResetOTP.java:140` — swapped arguments
            - FIXED: `ConditionalUserAttributeValue.java:39` — value type
            **BUILD_AFTER_FIX**: SUCCESS
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(5, s.errorsFound)
        assertEquals(5, s.matchedCount)
        assertTrue(s.safe)
    }

    @Test
    fun `hallucinated fixes in the wrong files never match`() {
        val output = """
            ERRORS_FOUND: 5
            FIXED: AuthenticationManagementResource.java:729 — fixed factory type
            FIXED: AbstractClaimMapper.java:54 — fixed claim split
            FIXED: TotpLoginBean.java:54 — fixed provider lookup
            FIXED: OTPFormAuthenticator.java:170 — fixed provider lookup
            FIXED: ConditionalUserConfiguredAuthenticator.java:39 — fixed config map
            BUILD_AFTER_FIX: SUCCESS
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(0, s.matchedCount)
        assertFalse(s.allSitesFixed)
        assertFalse(s.safe)
    }

    @Test
    fun `shotgunning edits across the tree is rejected by the spam guard`() {
        // Even if the five true sites appear among dozens of claimed "fixes", an agent that carpet
        // edits cannot win: more than 3x the seeded count of FIXED lines fails the verdict.
        val output = buildString {
            appendLine("ERRORS_FOUND: 20")
            appendLine("FIXED: JsonUtils.java:152 — a")
            appendLine("FIXED: CredentialHelper.java:77 — b")
            appendLine("FIXED: DenyAccessAuthenticator.java:62 — c")
            appendLine("FIXED: ResetOTP.java:140 — d")
            appendLine("FIXED: ConditionalUserAttributeValue.java:39 — e")
            for (line in 1..16) appendLine("FIXED: SomeOtherFile.java:${line * 11} — speculative edit")
            appendLine("BUILD_AFTER_FIX: SUCCESS")
        }
        val s = scoreCompileTriage(output, seeded)
        assertEquals(5, s.matchedCount)
        assertEquals(21, s.reportedCount)
        assertTrue(s.allSitesFixed)
        assertFalse(s.safe)
    }

    @Test
    fun `missing build marker yields null buildGreen and unsafe`() {
        val output = """
            FIXED: JsonUtils.java:152 — a
            FIXED: CredentialHelper.java:77 — b
            FIXED: DenyAccessAuthenticator.java:62 — c
            FIXED: ResetOTP.java:140 — d
            FIXED: ConditionalUserAttributeValue.java:39 — e
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertNull(s.buildGreen)
        assertTrue(s.allSitesFixed)
        assertFalse(s.safe)
    }

    @Test
    fun `absolute in-container paths still match by file simple name`() {
        val output = """
            FIXED: /home/agent/project/server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java:152 — a
            FIXED: /home/agent/project/server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java:77 — b
            FIXED: /home/agent/project/services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java:62 — c
            FIXED: /home/agent/project/services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java:140 — d
            FIXED: /home/agent/project/services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java:39 — e
            BUILD_AFTER_FIX: SUCCESS
        """.trimIndent()
        val s = scoreCompileTriage(output, seeded)
        assertEquals(5, s.matchedCount)
        assertTrue(s.safe)
    }

    @Test
    fun `each reported line is consumed by at most one seeded site`() {
        val s = scoreCompileTriage(
            """
                FIXED: F.java:113 — x
                BUILD_AFTER_FIX: SUCCESS
            """.trimIndent(),
            mapOf("F.java" to setOf(112, 114)),
        )
        assertEquals(1, s.matchedCount)
        assertFalse(s.allSitesFixed)
    }

    @Test
    fun `empty answer parses cleanly and is unsafe`() {
        val s = scoreCompileTriage("I could not complete the task.", seeded)
        assertNull(s.errorsFound)
        assertEquals(0, s.reportedCount)
        assertEquals(0, s.matchedCount)
        assertNull(s.buildGreen)
        assertFalse(s.allSitesFixed)
        assertFalse(s.safe)
    }
}
