/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreRenameSafety] — the verdict for the Keycloak rename A/B: a rename is "safe"
 * only if it was performed AND the project still compiles. The win shape: MCP (PSI rename) keeps the
 * build green; a sed rename breaks it. No IDE/Docker/agent needed.
 */
class RenameSafetyScoringTest {

    @Test
    fun `rename done and build green is safe (MCP-style)`() {
        val s = scoreRenameSafety("RENAME_DONE: yes\nBUILD_AFTER_RENAME: SUCCESS")
        assertTrue(s.safe)
        assertEquals(true, s.buildGreen)
    }

    @Test
    fun `rename done but build broken is unsafe (sed-style over-match)`() {
        val s = scoreRenameSafety("RENAME_DONE: yes\nBUILD_AFTER_RENAME: FAILURE — cannot find symbol getEmail")
        assertFalse(s.safe)
        assertEquals(false, s.buildGreen)
    }

    @Test
    fun `no rename performed is unsafe`() {
        val s = scoreRenameSafety("I could not complete the rename.\nBUILD_AFTER_RENAME: SUCCESS")
        assertFalse(s.safe)
        assertFalse(s.renameDone)
    }

    @Test
    fun `missing build report leaves build unknown and unsafe`() {
        val s = scoreRenameSafety("RENAME_DONE: yes")
        assertEquals(null, s.buildGreen)
        assertFalse(s.safe)
    }
}
