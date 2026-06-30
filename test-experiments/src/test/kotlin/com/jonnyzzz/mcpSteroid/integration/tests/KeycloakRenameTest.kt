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
 * MCP-win experiment: **safe project-wide rename** (jonnyzzz/mcp-steroid#169). The agent renames the
 * widely-used `org.keycloak.models.UserModel.getEmail()` accessor (referenced across hundreds of files)
 * to `getEmailAddress()`, then verifies the project still compiles.
 *
 * With MCP the agent uses IntelliJ's rename refactoring (`RenameProcessor`) — every reference is updated
 * by PSI and the build stays green. Without MCP, a sed/text rename over-matches (`EmailValidator`,
 * `isEmailVerified`, `updateEmail`, …) and/or misses qualified references, breaking compilation.
 *
 * Verdict ([scoreRenameSafety]): rename performed AND post-rename build SUCCESS — emitted as an `[ARENA]`
 * block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard metric, not a hard gate.
 */
class KeycloakRenameTest {

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak-rename-$agentName-$modeLabel",
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
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 2400)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreRenameSafety(combined)
            println("[TEST] keycloak rename [$agentName+$modeLabel] safe=${score.safe} " +
                    "renameDone=${score.renameDone} buildGreen=${score.buildGreen}")

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.safe,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "renameDone=${score.renameDone} buildGreen=${score.buildGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        appendLine("Task: rename the method `$SYMBOL` to `getEmailAddress()` PROJECT-WIDE — every reference")
        appendLine("(callers, overrides, the declaration) must be updated so the project still compiles.")
        appendLine()
        appendLine("Use IntelliJ's rename refactoring via `steroid_execute_code` (`com.intellij.refactoring`")
        appendLine("RenameProcessor / RefactoringFactory) — it updates all references by PSI. Do NOT sed.")
        appendLine("After renaming, build the affected modules (e.g. server-spi + services) to verify.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("RENAME_DONE: yes")
        appendLine("BUILD_AFTER_RENAME: <SUCCESS or FAILURE>")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only.")
        appendLine()
        appendLine("Task: rename the method `$SYMBOL` to `getEmailAddress()` PROJECT-WIDE — every reference")
        appendLine("(callers, overrides, the declaration) must be updated so the project still compiles.")
        appendLine("Beware: a naive text replace will over-match unrelated identifiers and break the build.")
        appendLine("After renaming, build the affected modules (e.g. server-spi + services) to verify.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("RENAME_DONE: yes")
        appendLine("BUILD_AFTER_RENAME: <SUCCESS or FAILURE>")
    }

    companion object {
        private const val SCENARIO = "keycloak__rename_safety"
        private const val SYMBOL = "org.keycloak.models.UserModel#getEmail()"
    }
}
