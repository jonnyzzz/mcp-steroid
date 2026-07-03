/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
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
 * MCP-win experiment: **IDE data-flow inspections find what grep can't** — the Kotlin twin of
 * [KeycloakInspectionsTest], on the pinned github.com/jonnyzzz/x11k commit `cfdf1f7d…`. Among a
 * fixed list of candidate files, the agent must report every LOCAL `var` that data-flow analysis
 * proves is never reassigned after initialization (should be `val`) or never read at all.
 *
 * Ground truth ([EXPECTED_ISSUES]) was derived mechanically: kotlinc 2.4.0 with
 * `compilerOptions.extraWarnings = true` (K2 `-Wextra`) over the pinned commit emits warnings at
 * exactly 9 sites — `X11Connection.kt:4400` (a `var idOffset` that is read but never reassigned,
 * buried in a 13.9k-line file) and two 4-line clusters of never-updated min/max trackers in
 * `XFramebuffer.kt`. The default compile shows ZERO warnings, so the answer cannot be read off a
 * plain build log.
 *
 * The decoys make grep lose: `X11State.kt` alone declares ~198 `var`s — every one of them IS
 * reassigned somewhere in its 11.6k lines, which text search cannot verify. With MCP the agent
 * runs IntelliJ's Kotlin inspections ("local `var` is never modified — can be `val`", unused
 * variable) per file via `steroid_execute_code`. Verdict ([scoreKotlinInspections]): reported
 * `file:line` pairs matched against ground truth with a spam guard — emitted as an `[ARENA]`
 * block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard metric.
 */
class X11kInspectionsTest {

    // 50 min (read-only precedent): no edits or rebuild sweeps, but the without-MCP leg may still
    // attempt per-var data-flow reasoning across an 11.6k-line decoy — it must finish and emit its
    // [ARENA] block so the dashboard can show the gap, not die as a timeout with no data.

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
                consoleTitle = "x11k-inspections-$agentName-$modeLabel",
                project = IntelliJProject.X11kPinnedProject,
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            )).waitForProjectReady()

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                else -> error("Unknown agent: $agentName")
            }

            val startedAt = System.currentTimeMillis()
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 1800)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreKotlinInspections(combined, EXPECTED_ISSUES, MIN_MATCHES)
            println("[TEST] x11k inspections [$agentName+$modeLabel] detected=${score.detected} " +
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
        appendLine("Task: among the candidate files below, find every LOCAL `var` for which data-flow")
        appendLine("analysis proves one of:")
        appendLine("- it is never reassigned after its initializer (so it should be declared `val`), or")
        appendLine("- its value is never read at all.")
        appendLine("Beware: these files declare hundreds of `var`s and almost all of them ARE genuinely")
        appendLine("mutated somewhere later in the same (very long) function or file — the declaration syntax")
        appendLine("alone proves nothing. Report ONLY the provable findings, with exact line numbers.")
        appendLine()
        appendLine("Candidate files:")
        for (file in CANDIDATE_FILES) appendLine("- $file")
    }

    private fun outputFormat(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("ISSUES_FOUND: <total count of provable findings>")
        appendLine("ISSUE: <path>:<line> — <short description>   ← one per finding, exact line number")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The x11k project is open in IntelliJ IDEA — a single-module Kotlin/JVM Gradle project")
        appendLine("(a headless X11 server implementation).")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's Kotlin inspections via `steroid_execute_code` on each candidate file —")
        appendLine("\"Local 'var' is never modified, can be declared 'val'\" (CanBeVal) and unused-variable")
        appendLine("analysis — and read their results. Do NOT guess from grep — \"never written after")
        appendLine("initialization\" requires data-flow analysis.")
        appendLine()
        append(outputFormat())
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The x11k project is checked out — a single-module Kotlin/JVM Gradle project")
        appendLine("(a headless X11 server implementation).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find/cat).")
        appendLine()
        append(taskDescription())
        appendLine()
        append(outputFormat())
    }

    companion object {
        private const val SCENARIO = "x11k__inspections"

        /**
         * 2 files with all 9 true findings + 4 var-heavy decoys with none. `X11State.kt` is the
         * flagship decoy: ~198 `var` declarations, every one genuinely mutated. Interleaved so the
         * true positives don't cluster at the top of the list.
         */
        private val CANDIDATE_FILES = listOf(
            "src/main/kotlin/org/jonnyzzz/xserver/X11State.kt",
            "src/main/kotlin/org/jonnyzzz/xserver/XFramebuffer.kt",
            "src/main/kotlin/org/jonnyzzz/xserver/SvgScreenRenderer.kt",
            "src/main/kotlin/org/jonnyzzz/xserver/X11Connection.kt",
            "src/main/kotlin/org/jonnyzzz/xserver/Main.kt",
            "src/main/kotlin/org/jonnyzzz/xserver/HttpScreenConnection.kt",
        )

        /**
         * Ground truth from kotlinc 2.4.0 `extraWarnings` (K2 `-Wextra`) at the pinned commit:
         *  - X11Connection.kt:4400   `var idOffset = 8` — read, never reassigned (can be `val`)
         *  - XFramebuffer.kt:1598-1601 and 1746-1749 — two clusters of `var min/maxPaintedX/Y`
         *    trackers that are never updated (never written, never read).
         */
        private val EXPECTED_ISSUES = mapOf(
            "X11Connection.kt" to setOf(4400),
            "XFramebuffer.kt" to setOf(1598, 1599, 1600, 1601, 1746, 1747, 1748, 1749),
        )

        /**
         * 6 of 9 — finding both XFramebuffer clusters (8 sites) or one cluster + the
         * X11Connection needle passes; tolerates inspection/kotlinc disagreement on single lines.
         */
        private const val MIN_MATCHES = 6
    }
}
