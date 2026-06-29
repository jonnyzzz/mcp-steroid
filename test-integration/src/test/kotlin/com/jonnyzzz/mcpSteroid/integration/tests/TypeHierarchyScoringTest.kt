/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreTypeHierarchy] — the completeness verdict for the Keycloak type-hierarchy
 * A/B. The whole point: a `grep "implements X"` answer misses the **transitive** subtype, so it scores
 * incomplete, while the PSI (ClassInheritorsSearch) answer lists it and scores complete. No IDE/Docker.
 */
class TypeHierarchyScoringTest {

    // A toy hierarchy that mirrors the Keycloak case: B implements X directly; C extends B (transitive).
    private val required = setOf("org.kc.B", "org.kc.C")

    @Test
    fun `MCP-style complete answer (lists the transitive subtype) scores complete`() {
        val mcp = """
            I walked the hierarchy with ClassInheritorsSearch.
            SUBTYPES_FOUND: 3
            SUBTYPE: org.kc.A
            SUBTYPE: org.kc.B
            SUBTYPE: org.kc.C
        """.trimIndent()
        val s = scoreTypeHierarchy(mcp, required, minTotal = 3)
        assertTrue(s.complete, "missing=${s.missingRequired} count=${s.reportedCount}")
        assertEquals(emptySet<String>(), s.missingRequired)
    }

    @Test
    fun `grep-style answer that misses the transitive subtype scores incomplete`() {
        // `grep "implements X"` finds A and B (direct) but NOT C (C extends B) — the classic miss.
        val grep = """
            I grepped for 'implements X'.
            SUBTYPE: org.kc.A
            SUBTYPE: org.kc.B
        """.trimIndent()
        val s = scoreTypeHierarchy(grep, required, minTotal = 3)
        assertFalse(s.complete)
        assertTrue(s.missingRequired.contains("org.kc.C"), "should flag the missed transitive subtype")
    }

    @Test
    fun `a reported FQN with the same simple name still counts (slightly different package)`() {
        // Agents sometimes report a subtype under a marginally different package; credit by simple name.
        val s = scoreTypeHierarchy("SUBTYPE: org.kc.B\nSUBTYPE: org.kc.impl.C", required, minTotal = 2)
        assertEquals(emptySet<String>(), s.missingRequired)
    }

    @Test
    fun `a too-short answer fails the minimum-count guard even if required are present`() {
        val s = scoreTypeHierarchy("SUBTYPE: org.kc.B\nSUBTYPE: org.kc.C", required, minTotal = 5)
        assertFalse(s.complete, "only 2 reported, minTotal=5")
        assertEquals(emptySet<String>(), s.missingRequired)
    }
}
