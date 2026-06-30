/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [mavenImportTriggerCode] — the script `mcpTriggerImportAndWait` runs to trigger the
 * Maven import. Source/javadoc auto-download must be controllable per project: ON gives agents API docs,
 * but on huge projects (Keycloak) with many absent `*:sources` artifacts it churns roots so the import
 * never settles. See jonnyzzz/mcp-steroid#169.
 */
class MavenImportSourceDownloadTest {

    @Test
    fun `enables source and doc download when requested`() {
        val code = mavenImportTriggerCode(downloadSourcesAndDocs = true)
        assertTrue(code.contains("isDownloadSourcesAutomatically = true"), code)
        assertTrue(code.contains("isDownloadDocsAutomatically = true"), code)
    }

    @Test
    fun `disables source and doc download for large projects`() {
        val code = mavenImportTriggerCode(downloadSourcesAndDocs = false)
        assertTrue(code.contains("isDownloadSourcesAutomatically = false"), code)
        assertTrue(code.contains("isDownloadDocsAutomatically = false"), code)
        assertFalse(code.contains("isDownloadSourcesAutomatically = true"), code)
        assertFalse(code.contains("isDownloadDocsAutomatically = true"), code)
    }

    @Test
    fun `always triggers the actual import regardless of source-download choice`() {
        for (flag in listOf(true, false)) {
            val code = mavenImportTriggerCode(downloadSourcesAndDocs = flag)
            assertTrue(code.contains("forceUpdateAllProjectsOrFindAllAvailablePomFiles"), code)
        }
    }
}
