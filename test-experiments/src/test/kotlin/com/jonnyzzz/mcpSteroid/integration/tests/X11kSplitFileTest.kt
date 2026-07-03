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
 * MCP-win experiment: **split the god file** (jonnyzzz/mcp-steroid#169 family) on the pinned
 * github.com/jonnyzzz/x11k commit `cfdf1f7d…`. The agent splits `X11State.kt` — 11,567 lines —
 * into cohesive files while the project keeps compiling.
 *
 * Why THIS file: x11k's two biggest sources are `X11Connection.kt` (13,892 lines — but ONE
 * monolithic `internal class` with ~690 members, splitting it means deep class surgery) and
 * `X11State.kt`, which has clean seams: ~75 top-level declarations (the XSync/XKB/keyboard/
 * window/pixmap/Render model types, ~1,900 lines) surrounding the central `X11State` class.
 * Reaching the ≤10,000-line threshold requires moving essentially ALL of them out into cohesive
 * groups — a genuine multi-file split; the agent chooses the seams (the `X11State` class itself
 * may stay put or be split further).
 *
 * With MCP the agent drives IntelliJ's Move declarations refactoring via `steroid_execute_code`
 * — references, imports and KDoc links update atomically. Without MCP it is a manual cut-and-paste
 * sweep across 11.6k lines. Verdict ([scoreSplitFile]) is evidence-based and identical for both
 * legs: both run the SAME verification commands (`wc -l`, `./gradlew compileKotlin
 * compileTestKotlin`, sentinel `grep`s proving moved declarations exist exactly once) and report
 * raw results — emitted as an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code;
 * correctness is a dashboard metric, not a hard gate.
 */
class X11kSplitFileTest {

    // 80 min, following the KeycloakRenameTest/KeycloakChangeSignatureTest precedent: the
    // without-MCP baseline is edit-heavy — a manual 1,900-line declaration sweep plus rebuilds
    // legitimately needs a long tail, and the slow baseline IS the experiment's finding — it must
    // complete and emit its [ARENA] block so the dashboard can show the gap, not die as a timeout
    // with no data. TC-side executionTimeoutMin=180 fits two 80-min methods.

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "x11k-splitfile-$agentName-$modeLabel",
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
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 2400)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreSplitFile(combined, ORIGINAL_FILE_NAME, MAX_REMAINING_LINES, MIN_NEW_FILES, SENTINELS)
            println("[TEST] x11k split-file [$agentName+$modeLabel] safe=${score.safe} " +
                    "remaining=${score.remainingLines} newFiles=${score.newFiles.size} " +
                    "buildGreen=${score.buildGreen} residueClean=${score.residueClean}")
            if (!score.residueClean) {
                println("[TEST]   residue counts: ${score.residueCounts}")
            }

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.safe,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "remaining=${score.remainingLines} newFiles=${score.newFiles.size} " +
                        "buildGreen=${score.buildGreen} residueClean=${score.residueClean}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: split the file `$TARGET_FILE` (11,567 lines)")
        appendLine("into cohesive files. You choose the seams — the file mixes the central `X11State` class")
        appendLine("with dozens of top-level model declarations (sync counters/alarms/fences, keyboard/XKB")
        appendLine("maps, windows/pixmaps/drawables, Render pictures/gradients/glyphs, drawing commands…).")
        appendLine()
        appendLine("Requirements:")
        appendLine("- create at least $MIN_NEW_FILES new .kt files (same package), grouped by responsibility;")
        appendLine("- the original file must end up at most $MAX_REMAINING_LINES lines (or be removed entirely);")
        appendLine("- NO behavior change: declarations are MOVED, never rewritten, deleted or duplicated;")
        appendLine("- the project must still compile (main and test sources).")
        appendLine()
        appendLine("Then run these verification commands and report their raw results:")
        appendLine("- `wc -l $TARGET_FILE`")
        appendLine("- `./gradlew compileKotlin compileTestKotlin`")
        for (sentinel in SENTINELS) {
            appendLine("- `grep -rn \"data class $sentinel(\" src/main/kotlin | wc -l`   (must be exactly 1)")
        }
    }

    private fun outputFormat(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("SPLIT_DONE: yes")
        appendLine("REMAINING_LINES: <wc -l of the original file after the split; 0 if removed>")
        appendLine("NEW_FILE: <path of one newly created .kt file>   ← one line per new file")
        appendLine("BUILD_AFTER_SPLIT: <SUCCESS or FAILURE>")
        for (sentinel in SENTINELS) {
            appendLine("RESIDUE: $sentinel=<the grep count for $sentinel>")
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The x11k project is open in IntelliJ IDEA — a single-module Kotlin/JVM Gradle project")
        appendLine("(a headless X11 server implementation).")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's Move refactoring via `steroid_execute_code` (e.g. the Kotlin Move")
        appendLine("declarations processor — `org.jetbrains.kotlin.idea.refactoring.move…` — moving groups of")
        appendLine("top-level declarations into new files): PSI updates every reference and import atomically.")
        appendLine("Do NOT cut-and-paste text between files with sed/manual edits.")
        appendLine()
        append(outputFormat())
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The x11k project is checked out — a single-module Kotlin/JVM Gradle project")
        appendLine("(a headless X11 server implementation).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Beware: manual cut-and-paste must keep every declaration exactly once and keep all")
        appendLine("references compiling — a missed dependency between the moved declarations breaks the build.")
        appendLine()
        append(outputFormat())
    }

    companion object {
        private const val SCENARIO = "x11k__split_file"
        private const val TARGET_FILE = "src/main/kotlin/org/jonnyzzz/xserver/X11State.kt"
        private const val ORIGINAL_FILE_NAME = "X11State.kt"

        /**
         * At the pinned commit the file is 11,567 lines; its ~75 top-level non-`X11State`
         * declarations span ~1,900 lines (1–125 + 9,772–11,567). Reaching ≤10,000 requires moving
         * essentially all of them — a genuine multi-file split — without demanding surgery on the
         * central class.
         */
        private const val MAX_REMAINING_LINES = 10_000
        private const val MIN_NEW_FILES = 3

        /**
         * Top-level `internal data class` declarations from three different responsibility groups
         * of the file (sync, keyboard, drawing). Both legs report `grep -rn "data class <name>(" |
         * wc -l` — exactly 1 proves the declaration was MOVED (not deleted with its usages, not
         * left duplicated in both the old and the new file).
         */
        private val SENTINELS = setOf("XSyncCounter", "XKeyboardMapping", "XDrawingCommand")
    }
}
