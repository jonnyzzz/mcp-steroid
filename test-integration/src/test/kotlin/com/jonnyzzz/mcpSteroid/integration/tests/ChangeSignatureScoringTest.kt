/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreChangeSignature] — the verdict for the Keycloak change-signature A/B.
 * The point of the experiment: IntelliJ's ChangeSignatureProcessor updates the interface method, EVERY
 * override (abstract bases, default methods, anonymous classes) and every call site atomically, while a
 * shell/editor sweep misses overrides or breaks call sites. The scorer checks three things from the
 * agent's marker output: the signature was changed, ALL ground-truth overrides were updated, and the
 * post-change build was reported green. No IDE/Docker needed.
 */
class ChangeSignatureScoringTest {

    // A toy ground truth mirroring the Keycloak shape: a concrete override, an abstract-base override,
    // and a default-method override in a sub-interface.
    private val required = setOf(
        "org.kc.auth.FooAuthenticator",
        "org.kc.auth.AbstractBarAuthenticator",
        "org.kc.auth.ConditionalBaz",
    )

    @Test
    fun `MCP-style answer with all overrides and green build scores safe`() {
        val mcp = """
            Used ChangeSignatureProcessor via steroid_execute_code.
            SIGNATURE_CHANGED: yes
            OVERRIDES_UPDATED: 3
            OVERRIDE_UPDATED: org.kc.auth.FooAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.AbstractBarAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.ConditionalBaz
            BUILD_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = scoreChangeSignature(mcp, required)
        assertTrue(s.signatureChanged)
        assertEquals(emptySet<String>(), s.missingOverrides)
        assertEquals(true, s.buildGreen)
        assertTrue(s.complete)
        assertTrue(s.safe)
    }

    @Test
    fun `sweep that misses the default-method override scores incomplete`() {
        // The classic manual-sweep miss: the `default void authenticate(...)` in a sub-interface.
        val sweep = """
            SIGNATURE_CHANGED: yes
            OVERRIDE_UPDATED: org.kc.auth.FooAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.AbstractBarAuthenticator
            BUILD_AFTER_CHANGE: FAILURE
        """.trimIndent()
        val s = scoreChangeSignature(sweep, required)
        assertTrue(s.signatureChanged)
        assertTrue(s.missingOverrides.contains("org.kc.auth.ConditionalBaz"))
        assertEquals(false, s.buildGreen)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `complete override list but red build is complete yet not safe`() {
        val out = """
            SIGNATURE_CHANGED: yes
            OVERRIDE_UPDATED: org.kc.auth.FooAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.AbstractBarAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.ConditionalBaz
            BUILD_AFTER_CHANGE: FAILURE — 12 call sites do not compile
        """.trimIndent()
        val s = scoreChangeSignature(out, required)
        assertTrue(s.complete)
        assertEquals(false, s.buildGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `markdown-formatted markers and backticked FQNs still parse`() {
        // Agents love markdown; markers may carry emphasis and FQNs may be backticked (the exact failure
        // mode that broke raw substring scoring before — see scoreSortedByDescendingRootCause).
        val md = """
            **SIGNATURE_CHANGED**: yes
            - OVERRIDE_UPDATED: `org.kc.auth.FooAuthenticator`
            - OVERRIDE_UPDATED: `org.kc.auth.AbstractBarAuthenticator`
            - OVERRIDE_UPDATED: `org.kc.auth.ConditionalBaz`
            **BUILD_AFTER_CHANGE**: SUCCESS
        """.trimIndent()
        val s = scoreChangeSignature(md, required)
        assertTrue(s.signatureChanged, "markdown-wrapped SIGNATURE_CHANGED must parse")
        assertEquals(emptySet<String>(), s.missingOverrides, "backticked FQNs must parse")
        assertTrue(s.safe)
    }

    @Test
    fun `no OVERRIDE_UPDATED markers falls back to FQNs anywhere in the answer`() {
        val prose = """
            SIGNATURE_CHANGED: yes
            I updated org.kc.auth.FooAuthenticator, org.kc.auth.AbstractBarAuthenticator and
            org.kc.auth.ConditionalBaz to the new signature.
            BUILD_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = scoreChangeSignature(prose, required)
        assertEquals(emptySet<String>(), s.missingOverrides)
        assertTrue(s.safe)
    }

    @Test
    fun `same simple name under a slightly different package still counts`() {
        val out = """
            SIGNATURE_CHANGED: yes
            OVERRIDE_UPDATED: org.kc.auth.FooAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.AbstractBarAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.impl.ConditionalBaz
            BUILD_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = scoreChangeSignature(out, required)
        assertEquals(emptySet<String>(), s.missingOverrides)
    }

    @Test
    fun `signature not changed at all scores unsafe regardless of build`() {
        val out = """
            SIGNATURE_CHANGED: no
            BUILD_AFTER_CHANGE: SUCCESS
        """.trimIndent()
        val s = scoreChangeSignature(out, required)
        assertFalse(s.signatureChanged)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `missing build marker yields null buildGreen and unsafe`() {
        val out = """
            SIGNATURE_CHANGED: yes
            OVERRIDE_UPDATED: org.kc.auth.FooAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.AbstractBarAuthenticator
            OVERRIDE_UPDATED: org.kc.auth.ConditionalBaz
        """.trimIndent()
        val s = scoreChangeSignature(out, required)
        assertNull(s.buildGreen)
        assertTrue(s.complete)
        assertFalse(s.safe)
    }
}
