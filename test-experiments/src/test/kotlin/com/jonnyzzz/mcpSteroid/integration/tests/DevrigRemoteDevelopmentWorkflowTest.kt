/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
    fun `preserved backend log redacts Bearer credentials without dropping diagnostics`() {
        val original =
            "INFO marker headers={Authorization=Bearer secret_token-123+/=} diagnostics=remote-development-backend"

        val redacted = DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.redactBearerCredentials(original)

        assertTrue("secret_token-123+/=" !in redacted)
        assertTrue("<redacted>" in redacted)
        assertTrue("diagnostics=remote-development-backend" in redacted)
    }
}
