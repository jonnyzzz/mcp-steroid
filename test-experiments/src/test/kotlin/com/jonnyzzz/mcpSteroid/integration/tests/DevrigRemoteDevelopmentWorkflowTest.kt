/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class DevrigRemoteDevelopmentWorkflowTest {

    @Test
    fun `deep hierarchy verifier accepts multiline qualified search call`() {
        val code = """
            val inheritors = ClassInheritorsSearch
                .search(baseClass, GlobalSearchScope.allScope(project), true)
                .findAll()
        """.trimIndent()

        assertTrue(DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.DEEP_SEARCH_ARGUMENT.containsMatchIn(code))
    }

    @Test
    fun `deep hierarchy verifier rejects false followed by unrelated true`() {
        val code = """
            val inheritors = ClassInheritorsSearch.search(baseClass, scope, false).findAll()
            val unrelated = true
        """.trimIndent()

        assertTrue(!DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.DEEP_SEARCH_ARGUMENT.containsMatchIn(code))
    }

    @Test
    fun `preserved backend log redacts every marker credential without dropping diagnostics`() {
        val original =
            "INFO marker Authorization=Bearer bearer_secret-123+/= " +
                "aboutUrl=https://127.0.0.1/api/about?foo=1&_ijt=ijt_query_secret%2B&next=2 " +
                "headers={\"x-ijt\": \"ijt_header_secret-123\"} diagnostics=remote-development-backend"

        val redacted = DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.redactMarkerCredentials(original)

        assertTrue("bearer_secret-123+/=" !in redacted)
        assertTrue("ijt_query_secret%2B" !in redacted)
        assertTrue("ijt_header_secret-123" !in redacted)
        assertTrue("&_ijt=<redacted>&next=2" in redacted)
        assertTrue("\"x-ijt\": \"<redacted>\"" in redacted)
        assertTrue("diagnostics=remote-development-backend" in redacted)
    }

    @Test
    fun `final artifact sanitizer redacts both idea and launcher logs`(@TempDir tempDir: Path) {
        val ideaLog = tempDir.resolve("managed-backend-idea.log").toFile()
        val launcherLog = tempDir.resolve("managed-backend-launcher.log").toFile()
        ideaLog.writeText(
            "idea Authorization=Bearer idea_bearer " +
                "aboutUrl=http://localhost/api/about?_ijt=idea_query " +
                "headers={\"x-ijt\":\"idea_header\"} diagnostics=idea",
        )
        launcherLog.writeText(
            "launcher Authorization=Bearer launcher_bearer " +
                "aboutUrl=http://localhost/api/about?_ijt=launcher_query " +
                "headers={\"x-ijt\":\"launcher_header\"} diagnostics=launcher",
        )

        DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.sanitizePreservedBackendLogs(tempDir.toFile())

        assertEquals(
            "idea Authorization=<redacted> aboutUrl=http://localhost/api/about?_ijt=<redacted> " +
                "headers={\"x-ijt\":\"<redacted>\"} diagnostics=idea",
            ideaLog.readText(),
        )
        assertEquals(
            "launcher Authorization=<redacted> aboutUrl=http://localhost/api/about?_ijt=<redacted> " +
                "headers={\"x-ijt\":\"<redacted>\"} diagnostics=launcher",
            launcherLog.readText(),
        )
    }
}
