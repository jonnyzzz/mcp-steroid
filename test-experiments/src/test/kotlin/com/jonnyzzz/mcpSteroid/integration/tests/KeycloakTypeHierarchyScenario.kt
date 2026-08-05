/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

/** Shared task contract for every Keycloak Authenticator hierarchy experiment. */
object KeycloakTypeHierarchyScenario {
    const val INTERFACE_FQN: String = "org.keycloak.authentication.Authenticator"
    const val MIN_TOTAL: Int = 40

    // Indirect implementors a text search for `implements Authenticator` misses.
    val requiredTransitive: Set<String> = setOf(
        "org.keycloak.authentication.authenticators.browser.UsernamePasswordForm",
        "org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator",
        "org.keycloak.authentication.authenticators.broker.IdpConfirmLinkAuthenticator",
    )

    val subInterfaces: Set<String> = setOf(
        "org.keycloak.authentication.AuthenticationFlowCallback",
        "org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator",
    )

    fun mcpTaskInstructions(): String = buildString {
        appendLine("Task: list EVERY class that implements the interface `$INTERFACE_FQN`,")
        appendLine("INCLUDING transitive/indirect implementors (a class that extends an abstract base which")
        appendLine("implements the interface still counts). Be exhaustive.")
        appendLine()
        appendLine("Use IntelliJ's PSI inheritance search via `steroid_execute_code`. BEFORE executing code,")
        appendLine("fetch `mcp-steroid://ide/type-hierarchy`, then adapt that recipe to use")
        appendLine("`ClassInheritorsSearch.search(psiClass, GlobalSearchScope.allScope(project), true)`.")
        appendLine("Print the FULL, untruncated transitive result (raise any recipe limit above 200).")
        appendLine("Do not rely on text search.")
        appendLine()
        append(outputFormat(includeToolEvidence = true))
    }

    fun baselineTaskInstructions(): String = buildString {
        appendLine("Task: list EVERY class that implements the interface `$INTERFACE_FQN`,")
        appendLine("INCLUDING transitive/indirect implementors (a class that extends an abstract base which")
        appendLine("implements the interface still counts). Be exhaustive.")
        appendLine()
        append(outputFormat(includeToolEvidence = false))
    }

    private fun outputFormat(includeToolEvidence: Boolean): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("SUBTYPES_FOUND: <total count>")
        appendLine("SUBTYPE: <fully.qualified.ClassName>   ← one line per implementor, including transitive ones")
        if (includeToolEvidence) appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }
}
