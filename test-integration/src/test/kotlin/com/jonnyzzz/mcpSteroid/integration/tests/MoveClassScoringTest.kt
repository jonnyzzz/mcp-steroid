/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreMoveClass] — the verdict for the Keycloak move-class A/B.
 * The point of the experiment: IntelliJ's MoveClassesOrPackagesProcessor moves a class between
 * packages and rewrites imports, FQN references, javadoc links AND non-Java text occurrences
 * atomically, while a grep-driven manual move misses (a) same-package usages that have NO import
 * line to rewrite and (b) FQN strings hiding in resource/script/doc files.
 *
 * The scorer is evidence-based: the move itself and the old-FQN residue are measured by the test
 * HARNESS (file-existence checks + a project-wide grep run in the container, identical for both
 * legs) and passed in as parameters — the agent's own "MOVE_DONE: yes" claim is never trusted.
 * Only the per-file `UPDATED:` list and the build claim come from the agent's output.
 * No IDE/Docker needed → unit-tested directly.
 */
class MoveClassScoringTest {

    // A toy ground truth mirroring the Keycloak shape: an import-based reference in another module,
    // a same-package (no-import) user, and a non-Java resource holding the FQN as a string.
    private val required = setOf(
        "services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java",
        "server-spi-private/src/main/java/org/kc/auth/FlowContext.java",
        "services/src/main/resources/scripts/template.js",
    )

    @Test
    fun `full move with all files updated, green build and zero residue scores safe`() {
        val out = """
            Used MoveClassesOrPackagesProcessor via steroid_execute_code.
            MOVE_DONE: yes
            UPDATED_COUNT: 3
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 0)
        assertTrue(s.moveDone)
        assertEquals(emptySet<String>(), s.missingUpdatedFiles)
        assertEquals(true, s.buildGreen)
        assertTrue(s.residueClean)
        assertTrue(s.complete)
        assertTrue(s.safe)
    }

    @Test
    fun `missing the same-package no-import file scores incomplete`() {
        // The classic grep-based miss: same-package usages have no import line to rewrite.
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: FAILURE
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 4)
        assertTrue(s.moveDone)
        assertTrue(s.missingUpdatedFiles.contains("server-spi-private/src/main/java/org/kc/auth/FlowContext.java"))
        assertEquals(false, s.buildGreen)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `nonzero old-FQN residue is not safe even when complete and green`() {
        // E.g. the agent fixed all sources but left the FQN string in a script template or doc —
        // the harness-run project-wide grep is the objective residue check.
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 2)
        assertTrue(s.complete)
        assertEquals(true, s.buildGreen)
        assertFalse(s.residueClean)
        assertFalse(s.safe)
    }

    @Test
    fun `class copied instead of moved scores moveDone false`() {
        // Old file still exists → it was a copy, not a move — harness evidence beats MOVE_DONE: yes.
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = true, oldFqnResidueCount = 0)
        assertFalse(s.moveDone)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `class never created at new path scores moveDone false regardless of claims`() {
        val out = """
            MOVE_DONE: yes
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = false, classAtOldPath = true, oldFqnResidueCount = 40)
        assertFalse(s.moveDone)
        assertFalse(s.safe)
    }

    @Test
    fun `failed harness verification (nulls) is conservative - not safe`() {
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = null, classAtOldPath = null, oldFqnResidueCount = null)
        assertFalse(s.moveDone)
        assertFalse(s.residueClean)
        assertNull(s.oldFqnResidueCount)
        assertFalse(s.safe)
    }

    @Test
    fun `markdown-formatted markers and backticked or absolute paths still parse`() {
        // Agents love markdown and absolute in-container paths — suffix matching must cope
        // (the exact failure mode that broke raw substring scoring in earlier experiments).
        val out = """
            **MOVE_DONE**: yes
            - UPDATED: `/home/agent/project/services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java`
            - UPDATED: `/home/agent/project/server-spi-private/src/main/java/org/kc/auth/FlowContext.java`
            - UPDATED: `/home/agent/project/services/src/main/resources/scripts/template.js`
            **BUILD_AFTER_MOVE**: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 0)
        assertEquals(emptySet<String>(), s.missingUpdatedFiles, "backticked absolute paths must parse")
        assertTrue(s.safe)
    }

    @Test
    fun `UPDATED lines with trailing line numbers still count`() {
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java:23
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java:7
            UPDATED: services/src/main/resources/scripts/template.js:1
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 0)
        assertEquals(emptySet<String>(), s.missingUpdatedFiles)
    }

    @Test
    fun `UPDATED_COUNT line is not mistaken for an UPDATED path`() {
        val out = """
            MOVE_DONE: yes
            UPDATED_COUNT: 3
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
            BUILD_AFTER_MOVE: SUCCESS
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 0)
        assertEquals(3, s.reportedUpdatedFiles.size, "UPDATED_COUNT must not contribute a fake path")
    }

    @Test
    fun `missing build marker yields null buildGreen and unsafe`() {
        val out = """
            MOVE_DONE: yes
            UPDATED: services/src/main/java/org/kc/auth/authenticators/FooAuthenticator.java
            UPDATED: server-spi-private/src/main/java/org/kc/auth/FlowContext.java
            UPDATED: services/src/main/resources/scripts/template.js
        """.trimIndent()
        val s = scoreMoveClass(out, required, classAtNewPath = true, classAtOldPath = false, oldFqnResidueCount = 0)
        assertNull(s.buildGreen)
        assertTrue(s.complete)
        assertFalse(s.safe)
    }
}
