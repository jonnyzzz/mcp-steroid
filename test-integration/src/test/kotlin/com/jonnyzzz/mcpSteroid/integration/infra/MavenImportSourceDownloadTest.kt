/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [mavenImportTriggerCode] — the script `mcpTriggerImportAndWait` runs to trigger the
 * Maven import. Library sources + javadoc auto-download is a deliberate must-have (agents get full API
 * docs in the editor), so the generated script must always enable it.
 */
class MavenImportSourceDownloadTest {

    @Test
    fun `always enables source and doc auto-download`() {
        val code = mavenImportTriggerCode()
        assertTrue(code.contains("isDownloadSourcesAutomatically = true"), code)
        assertTrue(code.contains("isDownloadDocsAutomatically = true"), code)
    }

    @Test
    fun `triggers the actual import`() {
        assertTrue(mavenImportTriggerCode().contains("forceUpdateAllProjectsOrFindAllAvailablePomFiles"))
    }
}
