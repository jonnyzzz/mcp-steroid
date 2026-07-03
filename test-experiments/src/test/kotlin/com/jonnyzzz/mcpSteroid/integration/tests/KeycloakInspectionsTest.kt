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
 * MCP-win experiment: **IDE inspections find what grep can't** (jonnyzzz/mcp-steroid#169). Among a
 * fixed list of cast-heavy candidate files, the agent must report which casts are GENUINELY
 * redundant — removable with no compile error and no semantic change.
 *
 * With MCP the agent runs IntelliJ's "Redundant type cast" (RedundantCast) inspection per file —
 * type inference finds exactly the real ones. Without MCP, grep sees cast syntax everywhere but
 * cannot determine redundancy: most candidate files are DECOYS full of casts that only LOOK
 * unnecessary (casts after classic `instanceof` — Java does not narrow there; casts of fluent
 * builder chains returning a supertype) but are required.
 *
 * Ground truth ([EXPECTED_ISSUES]) was derived mechanically: `javac -Xlint:cast` over the compiled
 * `core`/`common`/`server-spi`/`server-spi-private`/`services` modules of the pinned Keycloak
 * 26.6.4 tag emits exactly 4 `[cast] redundant cast` warnings — those 4 `file:line` pairs. The
 * decoy files compile with ZERO cast warnings.
 *
 * History: the first version of this scenario asked for redundant casts in `ValidatorConfig.java`
 * — a false premise (its `instanceof`-guarded casts are all REQUIRED; the inspection correctly
 * reports 0 there). On CI builds 991971406/991971408 both with-MCP legs truthfully answered 0 and
 * "lost", while the without-MCP legs "won" by hallucinating 15-17 non-issues (codex did so in 17s
 * with zero tool calls) — the old scorer trusted the self-reported count. The redesigned
 * [scoreInspections] matches reported `file:line` pairs against ground truth with a spam guard.
 *
 * Verdict: at least [MIN_MATCHES] ground-truth pairs hit, without shotgunning — emitted as an
 * `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard metric.
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

            val score = scoreInspections(combined, EXPECTED_ISSUES, MIN_MATCHES)
            println("[TEST] keycloak inspections [$agentName+$modeLabel] detected=${score.detected} " +
                    "matched=${score.matchedCount}/${EXPECTED_ISSUES.values.sumOf { it.size }} " +
                    "reported=${score.reportedCount} issuesFound=${score.issuesFound}")

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.detected,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "matched=${score.matchedCount} reported=${score.reportedCount} issuesFound=${score.issuesFound}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: among the candidate files below, find every cast expression that is GENUINELY")
        appendLine("REDUNDANT — i.e. the cast can be deleted with no compilation error and no change in")
        appendLine("semantics. Beware: most casts in these files only LOOK unnecessary but are required —")
        appendLine("e.g. casts after a classic `instanceof` check (Java does not narrow the variable's type")
        appendLine("there) or casts of fluent builder chains whose setters return a supertype. Report ONLY")
        appendLine("the casts that are truly redundant.")
        appendLine()
        appendLine("Candidate files:")
        for (file in CANDIDATE_FILES) appendLine("- $file")
    }

    private fun outputFormat(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("ISSUES_FOUND: <total count of genuinely redundant casts>")
        appendLine("ISSUE: <path>:<line> — <short description>   ← one per redundant cast, exact line number")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's \"Redundant type cast\" (RedundantCast) inspection via `steroid_execute_code`")
        appendLine("on each candidate file and read its results. Do NOT guess from grep — cast redundancy")
        appendLine("requires type inference.")
        appendLine()
        append(outputFormat())
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find/cat).")
        appendLine()
        append(taskDescription())
        appendLine()
        append(outputFormat())
    }

    companion object {
        private const val SCENARIO = "keycloak__inspections"

        /**
         * 4 files with exactly one genuinely redundant cast each + 4 cast-heavy decoys with none
         * (including `ValidatorConfig.java`, the original false-premise target). Interleaved so
         * the true positives don't cluster.
         */
        private val CANDIDATE_FILES = listOf(
            "server-spi/src/main/java/org/keycloak/validate/ValidatorConfig.java",
            "services/src/main/java/org/keycloak/authentication/actiontoken/ActionTokenContext.java",
            "common/src/main/java/org/keycloak/common/util/reflections/Reflections.java",
            "services/src/main/java/org/keycloak/services/managers/ResourceAdminManager.java",
            "services/src/main/java/org/keycloak/protocol/saml/SamlService.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/SpnegoAuthenticator.java",
            "services/src/main/java/org/keycloak/services/resources/LoginActionsService.java",
            "services/src/main/java/org/keycloak/protocol/saml/SamlAbstractMetadataPublicKeyLoader.java",
        )

        /**
         * Ground truth from `javac --release 17 -proc:none -Xlint:cast` on Keycloak tag 26.6.4:
         *  - ActionTokenContext.java:149  `(String) (client == null ? null : client.getClientId())`
         *  - ResourceAdminManager.java:374  `(LoginProtocol) session.getProvider(LoginProtocol.class, protocol)`
         *  - SpnegoAuthenticator.java:112  `(String) output.getState().get(KerberosConstants.RESPONSE_TOKEN)`
         *  - SamlAbstractMetadataPublicKeyLoader.java:107  `(List<XMLStructure>) keyInfo.getContent()`
         */
        private val EXPECTED_ISSUES = mapOf(
            "ActionTokenContext.java" to setOf(149),
            "ResourceAdminManager.java" to setOf(374),
            "SpnegoAuthenticator.java" to setOf(112),
            "SamlAbstractMetadataPublicKeyLoader.java" to setOf(107),
        )

        /** 3 of 4 true positives — tolerates one inspection/javac disagreement. */
        private const val MIN_MATCHES = 3
    }
}
