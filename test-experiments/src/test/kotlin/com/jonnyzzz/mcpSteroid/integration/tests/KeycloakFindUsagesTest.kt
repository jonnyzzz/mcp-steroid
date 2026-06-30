/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * MCP-win experiment: **find all overriders of an interface method via the call/override hierarchy**
 * (jonnyzzz/mcp-steroid#169). The agent must list every class that overrides
 * `org.keycloak.credential.CredentialInputValidator.isValid(RealmModel, UserModel, CredentialInput)`.
 *
 * With MCP the agent uses PSI override search (`OverridingMethodsSearch` / `ClassInheritorsSearch`) and
 * gets the exact set of providers that are reached by the runtime polymorphic dispatch. Without MCP,
 * `grep ".isValid("` both over-counts (unrelated same-named calls all over Keycloak) and can't connect
 * the single call site to its real implementations — so a faithful answer is hard to produce by text.
 *
 * Scored on completeness with the shared [scoreTypeHierarchy] (the verdict is "did the agent report the
 * real overriding providers"), emitted as an `[ARENA]` block the dashboard reads. A/B per agent; with-MCP
 * additionally asserts exec_code was used; correctness is a dashboard metric, not a hard pass gate.
 */
class KeycloakFindUsagesTest {

    @Test @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 40, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak-findusages-$agentName-$modeLabel",
                project = IntelliJProject.KeycloakProject,
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            )).waitForProjectReady(buildSystem = BuildSystem.MAVEN)

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                else -> error("Unknown agent: $agentName")
            }

            val startedAt = System.currentTimeMillis()
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 1500)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreTypeHierarchy(combined, REQUIRED_OVERRIDERS, MIN_TOTAL)
            println("[TEST] keycloak find-usages [$agentName+$modeLabel] complete=${score.complete} " +
                    "reported=${score.reportedCount} missing=${score.missingRequired}")

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.complete,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "found ${score.reportedCount} overriders; missing required ${score.missingRequired}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        appendLine("Task: list EVERY class that OVERRIDES the method")
        appendLine("`$METHOD` (i.e. every concrete implementation reached when that method is called).")
        appendLine()
        appendLine("Use IntelliJ's PSI override/inheritance search via `steroid_execute_code` —")
        appendLine("find the PsiMethod and use `OverridingMethodsSearch.search(method)` (or")
        appendLine("`ClassInheritorsSearch` on the interface and resolve the method). Do NOT use text search.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SUBTYPES_FOUND: <count>")
        appendLine("SUBTYPE: <fully.qualified.ClassName>   ← one line per overriding class")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find).")
        appendLine()
        appendLine("Task: list EVERY class that OVERRIDES the method")
        appendLine("`$METHOD` (i.e. every concrete implementation reached when that method is called).")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SUBTYPES_FOUND: <count>")
        appendLine("SUBTYPE: <fully.qualified.ClassName>   ← one line per overriding class")
    }

    companion object {
        private const val SCENARIO = "keycloak__find_usages"
        private const val METHOD =
            "org.keycloak.credential.CredentialInputValidator#isValid(RealmModel, UserModel, CredentialInput)"

        // Real overriding providers reached by the runtime polymorphic dispatch — verified against the
        // Keycloak source. A textual grep of ".isValid(" cannot reliably connect the call site to these.
        private val REQUIRED_OVERRIDERS = setOf(
            "org.keycloak.credential.PasswordCredentialProvider",
            "org.keycloak.credential.OTPCredentialProvider",
            "org.keycloak.credential.WebAuthnCredentialProvider",
            "org.keycloak.credential.RecoveryAuthnCodesCredentialProvider",
        )
        private const val MIN_TOTAL = 4
    }
}
