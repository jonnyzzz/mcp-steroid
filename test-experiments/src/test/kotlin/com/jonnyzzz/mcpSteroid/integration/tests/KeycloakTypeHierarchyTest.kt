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
 * MCP-win experiment: **transitive type hierarchy on a large codebase** (jonnyzzz/mcp-steroid#169).
 *
 * The agent must enumerate EVERY implementor of `org.keycloak.authentication.Authenticator`, including
 * the **transitive** ones (classes that extend an abstract base which implements the interface). On
 * Keycloak this is where the IDE wins: `ClassInheritorsSearch(..., checkDeep=true)` returns the full
 * set, whereas `grep "implements Authenticator"` finds only the ~direct implementors and MISSES the
 * indirect leaves (e.g. `UsernamePasswordForm extends AbstractUsernameFormAuthenticator extends
 * AbstractFormAuthenticator implements Authenticator`).
 *
 * A/B: each agent runs WITH and WITHOUT MCP. Scored purely on completeness ([scoreTypeHierarchy]) — did
 * the agent report the transitive implementors (and enough total) — identically for both modes, so the
 * dashboard shows whether the IDE's exact hierarchy walk beats grep. The verdict is emitted as an
 * `[ARENA]` block (read by the dashboard's ArenaLogParser); correctness is a dashboard metric, not a
 * hard pass gate, so a legitimate grep miss is a comparison result, not a red build.
 */
class KeycloakTypeHierarchyTest {

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
                // mode last in the title so the run-dir zip is mode-tagged (*-mcp / *-none) for the dashboard.
                consoleTitle = "keycloak-typehierarchy-$agentName-$modeLabel",
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

            val score = scoreTypeHierarchy(combined, REQUIRED_TRANSITIVE, MIN_TOTAL)
            println("[TEST] keycloak type-hierarchy [$agentName+$modeLabel] complete=${score.complete} " +
                    "reported=${score.reportedCount} missing=${score.missingRequired}")

            // Emit the full [ARENA] block (verdict + duration + tokens + tool-call counters) for the dashboard.
            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.complete,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "found ${score.reportedCount} subtypes; missing required ${score.missingRequired}",
            )

            if (withMcp) {
                // Prove MCP was actually exercised; completeness itself is the comparison metric, not a gate.
                assertUsedExecuteCodeEvidence(combined)
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        appendLine("Task: list EVERY class that implements the interface `$INTERFACE_FQN`,")
        appendLine("INCLUDING transitive/indirect implementors (a class that extends an abstract base which")
        appendLine("implements the interface still counts). Be exhaustive.")
        appendLine()
        appendLine("Use IntelliJ's PSI inheritance search via `steroid_execute_code`. BEFORE your first attempt,")
        appendLine("fetch `mcp-steroid://lsp/find-references` / the type-hierarchy recipe, then use")
        appendLine("`ClassInheritorsSearch.search(psiClass, GlobalSearchScope.allScope(project), /*checkDeep=*/ true)`")
        appendLine("to walk the FULL transitive hierarchy. Do not rely on text search.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SUBTYPES_FOUND: <total count>")
        appendLine("SUBTYPE: <fully.qualified.ClassName>   ← one line per implementor, including transitive ones")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find).")
        appendLine()
        appendLine("Task: list EVERY class that implements the interface `$INTERFACE_FQN`,")
        appendLine("INCLUDING transitive/indirect implementors (a class that extends an abstract base which")
        appendLine("implements the interface still counts). Be exhaustive.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SUBTYPES_FOUND: <total count>")
        appendLine("SUBTYPE: <fully.qualified.ClassName>   ← one line per implementor, including transitive ones")
    }

    companion object {
        private const val SCENARIO = "keycloak__type_hierarchy"
        private const val INTERFACE_FQN = "org.keycloak.authentication.Authenticator"

        // Transitive (indirect) implementors a naive `grep "implements Authenticator"` MISSES — they
        // extend an abstract base that implements the interface. Verified against the Keycloak source.
        private val REQUIRED_TRANSITIVE = setOf(
            "org.keycloak.authentication.authenticators.browser.UsernamePasswordForm",
            "org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator",
            "org.keycloak.authentication.authenticators.broker.IdpConfirmLinkAuthenticator",
        )

        // Keycloak has ~60 Authenticator implementors; require a healthy fraction so a near-empty
        // (or only-direct) answer scores incomplete.
        private const val MIN_TOTAL = 40
    }
}
