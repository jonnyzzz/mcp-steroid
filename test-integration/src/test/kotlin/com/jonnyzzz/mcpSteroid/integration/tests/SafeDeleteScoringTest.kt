/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreSafeDelete] — the verdict for the Keycloak safe-delete A/B.
 * The point of the experiment: IntelliJ's SafeDeleteProcessor surfaces BLOCKING USAGES of a method
 * before deleting it, while a blind sed delete discovers the breakage only at compile time. The
 * scorer checks four things from the agent's marker output: the method declaration is gone, EVERY
 * hand-derived production usage site was migrated, the post-delete build was reported green, and
 * NO production files outside the known set were touched (collateral edits). No IDE/Docker needed.
 */
class SafeDeleteScoringTest {

    // A toy ground truth mirroring the Keycloak rsa256 shape: a declaration file plus three usage
    // files scattered across three modules.
    private val requiredUsageFiles = setOf(
        "core/src/main/java/org/kc/KeyPairVerifier.java",
        "integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java",
        "services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java",
    )
    private val allowedChangedFiles = requiredUsageFiles +
            "core/src/main/java/org/kc/jose/JWSBuilder.java"

    private fun score(output: String) = scoreSafeDelete(output, requiredUsageFiles, allowedChangedFiles)

    @Test
    fun `MCP-style answer with all migrations, clean diff and green build scores safe`() {
        val mcp = """
            Used SafeDeleteProcessor via steroid_execute_code; it surfaced 3 blocking usages.
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            CHANGED_FILE: core/src/main/java/org/kc/KeyPairVerifier.java
            CHANGED_FILE: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java
            CHANGED_FILE: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(mcp)
        assertTrue(s.methodDeleted)
        assertEquals(emptySet<String>(), s.missingMigrations)
        assertEquals(emptySet<String>(), s.collateralFiles)
        assertEquals(true, s.buildGreen)
        assertTrue(s.complete)
        assertTrue(s.safe)
    }

    @Test
    fun `sweep that misses the cross-module usage scores incomplete`() {
        // The classic blind-sed miss: the usage in another module never shows up in the local grep.
        val sweep = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            CHANGED_FILE: core/src/main/java/org/kc/KeyPairVerifier.java
            CHANGED_FILE: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            BUILD_AFTER_DELETE: FAILURE
        """.trimIndent()
        val s = score(sweep)
        assertTrue(s.methodDeleted)
        assertEquals(setOf("integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java"), s.missingMigrations)
        assertEquals(false, s.buildGreen)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `collateral edit to a production file outside the known set fails the verdict`() {
        // "While I'm here" over-edits: a sibling file changed although it was not part of the task.
        val out = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            CHANGED_FILE: core/src/main/java/org/kc/KeyPairVerifier.java
            CHANGED_FILE: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java
            CHANGED_FILE: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSInput.java
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(out)
        assertEquals(setOf("core/src/main/java/org/kc/jose/JWSInput.java"), s.collateralFiles)
        assertTrue(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `changed test files are not collateral`() {
        // Deleting the method legitimately ripples into unit tests / testsuite; those edits are out of
        // scope for the verdict (the build check covers production sources only).
        val out = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            CHANGED_FILE: core/src/main/java/org/kc/KeyPairVerifier.java
            CHANGED_FILE: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java
            CHANGED_FILE: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            CHANGED_FILE: core/src/test/java/org/kc/RSAVerifierTest.java
            CHANGED_FILE: testsuite/integration-arquillian/tests/base/src/test/java/org/kc/SomeTest.java
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(out)
        assertEquals(emptySet<String>(), s.collateralFiles)
        assertTrue(s.safe)
    }

    @Test
    fun `complete migration but red build is complete yet not safe`() {
        val out = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            BUILD_AFTER_DELETE: FAILURE — missing import of Algorithm in AuthUtil
        """.trimIndent()
        val s = score(out)
        assertTrue(s.complete)
        assertEquals(false, s.buildGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `markdown-formatted markers with backticked paths and status prefixes still parse`() {
        // Agents love markdown, and `git status --porcelain` output sneaks `M ` prefixes into
        // CHANGED_FILE lines — both must parse (the exact failure mode that broke raw substring
        // scoring before; see scoreSortedByDescendingRootCause).
        val md = """
            **METHOD_DELETED**: yes
            - MIGRATED: `core/src/main/java/org/kc/KeyPairVerifier.java:51`
            - MIGRATED: `integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:214`
            - MIGRATED: `services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:133`
            CHANGED_FILE: M core/src/main/java/org/kc/jose/JWSBuilder.java
            CHANGED_FILE: M core/src/main/java/org/kc/KeyPairVerifier.java
            CHANGED_FILE: M integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java
            CHANGED_FILE: M services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            **BUILD_AFTER_DELETE**: SUCCESS
        """.trimIndent()
        val s = score(md)
        assertTrue(s.methodDeleted, "markdown-wrapped METHOD_DELETED must parse")
        assertEquals(emptySet<String>(), s.missingMigrations, "backticked paths must parse")
        assertEquals(emptySet<String>(), s.collateralFiles, "git-status prefixes must be stripped")
        assertTrue(s.safe)
    }

    @Test
    fun `absolute in-container paths still match the relative ground truth`() {
        val out = """
            METHOD_DELETED: yes
            MIGRATED: /home/agent/project/core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: /home/agent/project/integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: /home/agent/project/services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            CHANGED_FILE: /home/agent/project/core/src/main/java/org/kc/jose/JWSBuilder.java
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(out)
        assertEquals(emptySet<String>(), s.missingMigrations)
        assertEquals(emptySet<String>(), s.collateralFiles)
        assertTrue(s.safe)
    }

    @Test
    fun `method not deleted scores unsafe regardless of everything else`() {
        val out = """
            METHOD_DELETED: no
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(out)
        assertFalse(s.methodDeleted)
        assertFalse(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `missing build marker yields null buildGreen and unsafe`() {
        val out = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java:50
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java:213
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java:132
        """.trimIndent()
        val s = score(out)
        assertNull(s.buildGreen)
        assertTrue(s.complete)
        assertFalse(s.safe)
    }

    @Test
    fun `no markers at all scores fully unsafe with all migrations missing`() {
        val s = score("I could not complete the task, the build kept failing.")
        assertFalse(s.methodDeleted)
        assertEquals(requiredUsageFiles, s.missingMigrations)
        assertNull(s.buildGreen)
        assertFalse(s.safe)
    }

    @Test
    fun `migration markers without line numbers still count by file`() {
        // Line numbers drift by a couple of lines when the import block above the call site grows —
        // matching is by file, the line is informational.
        val out = """
            METHOD_DELETED: yes
            MIGRATED: core/src/main/java/org/kc/KeyPairVerifier.java
            MIGRATED: integration/admin-cli/src/main/java/org/kc/cli/AuthUtil.java
            MIGRATED: services/src/main/java/org/kc/docker/DockerAuthV2Protocol.java
            CHANGED_FILE: core/src/main/java/org/kc/jose/JWSBuilder.java
            BUILD_AFTER_DELETE: SUCCESS
        """.trimIndent()
        val s = score(out)
        assertEquals(emptySet<String>(), s.missingMigrations)
        assertTrue(s.safe)
    }
}
