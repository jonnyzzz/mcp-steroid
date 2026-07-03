/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreCallHierarchy] — the completeness verdict for the Keycloak call-hierarchy
 * (endpoint reachability) A/B. The point mirrors the type-hierarchy scorer: a grep walk cannot follow a
 * call chain through an interface dispatch or a DI provider lookup, so it misses the REQUIRED endpoints;
 * the IDE's caller hierarchy finds them. No IDE/Docker needed.
 */
class CallHierarchyScoringTest {

    // Mirrors the Keycloak ground truth shape: each required endpoint is a group of acceptable spellings
    // (agents name a nested JAX-RS resource by its outer class, the subclass, or the base class).
    private val required = listOf(
        setOf("org.kc.TokenEndpoint#processGrantRequest"),
        setOf(
            "org.kc.AbstractBrokerProvider.Endpoint#authResponse",
            "org.kc.OidcBrokerProvider.OidcEndpoint#authResponse",
        ),
    )

    @Test
    fun `MCP-style complete answer (marker lines with hash separator) scores complete`() {
        val mcp = """
            I walked the caller hierarchy from the target method.
            ENDPOINTS_FOUND: 3
            ENDPOINT: org.kc.TokenEndpoint#processGrantRequest
            ENDPOINT: org.kc.AbstractBrokerProvider.Endpoint#authResponse
            ENDPOINT: org.kc.LoginActionsService#authenticate
        """.trimIndent()
        val s = scoreCallHierarchy(mcp, required, minTotal = 3)
        assertTrue(s.complete, "missing=${s.missingRequired} count=${s.reportedCount}")
        assertEquals(emptySet<String>(), s.missingRequired)
        assertEquals(3, s.reportedCount)
    }

    @Test
    fun `grep-style answer that misses the interface-dispatch endpoints scores incomplete`() {
        // grep from the target upward finds the direct textual callers but cannot cross the DI provider
        // lookup (TokenEndpoint) or the callback interface (broker endpoint) — the classic miss.
        val grep = """
            I grepped for callers.
            ENDPOINTS_FOUND: 3
            ENDPOINT: org.kc.LoginActionsService#authenticate
            ENDPOINT: org.kc.LoginActionsService#authenticateForm
            ENDPOINT: org.kc.AuthorizationEndpoint#buildGet
        """.trimIndent()
        val s = scoreCallHierarchy(grep, required, minTotal = 3)
        assertFalse(s.complete)
        assertEquals(2, s.missingRequired.size, "both interface-hop endpoints should be flagged missing")
    }

    @Test
    fun `alternative spelling of a nested resource class still counts`() {
        val out = """
            ENDPOINT: org.kc.TokenEndpoint#processGrantRequest
            ENDPOINT: org.kc.OidcBrokerProvider.OidcEndpoint#authResponse
            ENDPOINT: org.kc.LoginActionsService#authenticate
        """.trimIndent()
        val s = scoreCallHierarchy(out, required, minTotal = 3)
        assertEquals(emptySet<String>(), s.missingRequired)
        assertTrue(s.complete)
    }

    @Test
    fun `markdown formatting and dot or dollar separators are tolerated`() {
        // Agents love backticks and `Class.method()` / `Outer${'$'}Inner.method` layouts.
        val out = """
            ENDPOINTS_FOUND: 3
            ENDPOINT: `org.kc.TokenEndpoint.processGrantRequest()`
            ENDPOINT: **org.kc.AbstractBrokerProvider${'$'}Endpoint.authResponse**
            ENDPOINT: org.kc.LoginActionsService::authenticate
        """.trimIndent()
        val s = scoreCallHierarchy(out, required, minTotal = 3)
        assertEquals(emptySet<String>(), s.missingRequired, "markdown/separator variants must be normalized")
        assertEquals(3, s.reportedCount)
        assertTrue(s.complete)
    }

    @Test
    fun `answer without ENDPOINT markers falls back to free-text class-and-method matching`() {
        val out = """
            The following REST endpoints reach the target:
            1. TokenEndpoint.processGrantRequest (the token grant POST)
            2. AbstractBrokerProvider.Endpoint.authResponse (broker callback GET)
            3. LoginActionsService.authenticate
        """.trimIndent()
        val s = scoreCallHierarchy(out, required, minTotal = 1)
        assertEquals(emptySet<String>(), s.missingRequired)
    }

    @Test
    fun `mentioning the class without the method does not count as found`() {
        val out = """
            ENDPOINT: org.kc.LoginActionsService#authenticate
            I also looked at TokenEndpoint but found no path from it.
        """.trimIndent()
        // "TokenEndpoint" appears in prose, but never adjacent to processGrantRequest → still missing.
        val s = scoreCallHierarchy(out, required, minTotal = 1)
        assertTrue(s.missingRequired.any { it.contains("TokenEndpoint") },
            "class mentioned without its endpoint method must not count; missing=${s.missingRequired}")
    }

    @Test
    fun `a too-short answer fails the minimum-count guard even if required are present`() {
        val out = """
            ENDPOINT: org.kc.TokenEndpoint#processGrantRequest
            ENDPOINT: org.kc.AbstractBrokerProvider.Endpoint#authResponse
        """.trimIndent()
        val s = scoreCallHierarchy(out, required, minTotal = 5)
        assertFalse(s.complete, "only 2 reported, minTotal=5")
        assertEquals(emptySet<String>(), s.missingRequired)
    }
}
