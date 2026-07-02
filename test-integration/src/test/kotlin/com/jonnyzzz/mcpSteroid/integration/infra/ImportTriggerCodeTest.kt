/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for the import-trigger scripts [mavenImportTriggerCode] / [gradleImportTriggerCode] that
 * `mcpTriggerImportAndWait` runs. Two deliberate, unified guarantees:
 *  - Library source auto-download is a must-have (agents get full API docs in the editor), so BOTH build
 *    systems must enable everything their IDE integration offers: Maven sources + javadoc; Gradle sources
 *    (IntelliJ's Gradle integration has no IDE-side javadoc auto-download — sources carry the docs).
 *  - The trigger must AWAIT actual import completion via a project-level import-finished listener (not a
 *    blind delay) — Maven mirrors the Gradle logic.
 */
class ImportTriggerCodeTest {

    @Test
    fun `maven enables source and doc auto-download`() {
        val code = mavenImportTriggerCode()
        assertTrue(code.contains("isDownloadSourcesAutomatically = true"), code)
        assertTrue(code.contains("isDownloadDocsAutomatically = true"), code)
    }

    @Test
    fun `maven triggers the actual import and awaits completion (no blind delay)`() {
        val code = mavenImportTriggerCode()
        assertTrue(code.contains("forceUpdateAllProjectsOrFindAllAvailablePomFiles"), code)
        // Unified with Gradle: await the project-level MavenImportListener, not a fixed delay.
        assertTrue(code.contains("MavenImportListener.TOPIC"), code)
        assertTrue(code.contains("importDone.await()"), code)
        assertFalse(code.contains("delay(2_000L)"), "Maven must await import completion, not blindly delay: $code")
    }

    @Test
    fun `gradle enables source auto-download (the full available parity with maven)`() {
        val code = gradleImportTriggerCode()
        assertTrue(code.contains("isDownloadSources = true"), code)
        // There is deliberately NO javadoc toggle: IntelliJ's Gradle integration registers only
        // "gradle.download.sources" (no javadoc advanced setting exists); *-sources.jar carries the docs.
        // Asserting a made-up setting id would be false confidence — AdvancedSettings.setBoolean throws
        // IllegalArgumentException for unknown ids at IDE runtime.
        assertFalse(code.contains("gradle.download.javadoc"), code)
    }

    @Test
    fun `gradle triggers a refresh and awaits import completion`() {
        val code = gradleImportTriggerCode()
        assertTrue(code.contains("ExternalSystemUtil.refreshProject"), code)
        assertTrue(code.contains("ProjectDataImportListener.TOPIC"), code)
        assertTrue(code.contains("importDone.await()"), code)
    }
}
