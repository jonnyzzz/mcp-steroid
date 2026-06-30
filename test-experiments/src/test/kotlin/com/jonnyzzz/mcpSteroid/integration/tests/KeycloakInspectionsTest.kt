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
 * MCP-win experiment: **IDE inspections find what grep can't** (jonnyzzz/mcp-steroid#169). The agent must
 * report the redundant type casts after `instanceof` in Keycloak's
 * `server-spi/src/main/java/org/keycloak/validate/ValidatorConfig.java`.
 *
 * With MCP the agent runs IntelliJ's "Redundant type cast" inspection — semantic type-narrowing finds
 * each cast that is unnecessary after an `instanceof`. Without MCP, grep sees the cast syntax but cannot
 * determine whether a cast is redundant (that needs type inference), so it cannot reliably find them.
 *
 * Verdict ([scoreInspections]): the agent named the file and reported enough redundant casts — emitted
 * as an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard metric.
 */
class KeycloakInspectionsTest {

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
                consoleTitle = "keycloak-inspections-$agentName-$modeLabel",
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

            val score = scoreInspections(combined, MIN_ISSUES, TARGET_FILE)
            println("[TEST] keycloak inspections [$agentName+$modeLabel] detected=${score.detected} " +
                    "issuesFound=${score.issuesFound} cast=${score.mentionsRedundantCast} file=${score.mentionsTargetFile}")

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.detected,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "issuesFound=${score.issuesFound} redundantCast=${score.mentionsRedundantCast}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        appendLine("Task: find every REDUNDANT type cast in `$TARGET_PATH` — casts that are unnecessary")
        appendLine("because the variable was already narrowed by a preceding `instanceof` check.")
        appendLine()
        appendLine("Use IntelliJ's inspections via `steroid_execute_code` — run the \"Redundant type cast\"")
        appendLine("(RedundantCast) inspection on the file and read its results. Do NOT guess from grep.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("ISSUES_FOUND: <count of redundant casts>")
        appendLine("ISSUE: <file:line — short description>   ← one per redundant cast")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find).")
        appendLine()
        appendLine("Task: find every REDUNDANT type cast in `$TARGET_PATH` — casts that are unnecessary")
        appendLine("because the variable was already narrowed by a preceding `instanceof` check.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("ISSUES_FOUND: <count of redundant casts>")
        appendLine("ISSUE: <file:line — short description>   ← one per redundant cast")
    }

    companion object {
        private const val SCENARIO = "keycloak__inspections"
        private const val TARGET_FILE = "ValidatorConfig.java"
        private const val TARGET_PATH = "server-spi/src/main/java/org/keycloak/validate/ValidatorConfig.java"

        // ValidatorConfig.java has ~13 redundant casts after instanceof; require a meaningful fraction.
        private const val MIN_ISSUES = 5
    }
}
