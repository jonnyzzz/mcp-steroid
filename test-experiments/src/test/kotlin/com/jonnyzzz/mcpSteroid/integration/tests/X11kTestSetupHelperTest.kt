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
 * MCP-win experiment: **extract the copy-pasted test setup into a shared helper**
 * (jonnyzzz/mcp-steroid#169 family) on the pinned github.com/jonnyzzz/x11k commit `cfdf1f7d…`.
 *
 * x11k's protocol tests boot an in-process X server with the SAME 6-line block — construct
 * `XServer(ServerOptions(port = 0, …))`, spawn a daemon `serveForever()` thread, connect a
 * `Socket`, set `soTimeout`, run the handshake `setup(socket)`, and tear down with
 * `server.close()` + `serverThread.join(…)` — repeated 800+ times repo-wide. The experiment
 * scopes the migration to `XSyncProtocolTest.kt` (905 lines), where the block appears in every
 * one of its 15 test methods at hand-derived line numbers.
 *
 * With MCP the agent uses IntelliJ's Extract Method / Introduce Parameter refactorings and usage
 * search via `steroid_execute_code` to introduce a lambda-accepting helper and migrate the call
 * sites; without MCP it is 15 manual block rewrites. Verdict ([scoreTestSetupHelper]): helper
 * exists, ≥13 of the 15 ground-truth call sites reported migrated (original line numbers matched
 * against the hand-derived list, spam-capped), and `XSyncProtocolTest` still passes — emitted as
 * an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard
 * metric, not a hard gate.
 */
class X11kTestSetupHelperTest {

    // 80 min, following the KeycloakRenameTest/KeycloakChangeSignatureTest precedent: the
    // without-MCP baseline is edit-heavy (15 block rewrites + a Gradle test run per iteration) and
    // must complete and emit its [ARENA] block so the dashboard can show the gap, not die as a
    // timeout with no data. TC-side executionTimeoutMin=180 fits two 80-min methods.

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
                consoleTitle = "x11k-setuphelper-$agentName-$modeLabel",
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

            val score = scoreTestSetupHelper(combined, TARGET_FILE_NAME, EXPECTED_CALL_SITE_LINES, MIN_MIGRATED, LINE_TOLERANCE)
            println("[TEST] x11k setup-helper [$agentName+$modeLabel] safe=${score.safe} " +
                    "helper=${score.helperCreated} matched=${score.matchedCount}/${EXPECTED_CALL_SITE_LINES.size} " +
                    "reported=${score.reportedCount} testsGreen=${score.testsGreen}")
            if (score.missingLines.isNotEmpty()) {
                println("[TEST]   missed call sites at original lines: ${score.missingLines.sorted()}")
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
                summary = "helper=${score.helperCreated} matched=${score.matchedCount} " +
                        "reported=${score.reportedCount} testsGreen=${score.testsGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: `$TARGET_FILE` repeats the same test-setup block in EVERY one of its 15 test")
        appendLine("methods — construct `XServer(ServerOptions(port = 0, …))`, start a daemon thread running")
        appendLine("`server.serveForever()`, connect a `Socket` to `server.localPort`, set `soTimeout`, call the")
        appendLine("`setup(socket)` handshake, and tear down with `server.close()` + `serverThread.join(…)`.")
        appendLine()
        appendLine("Replace this duplication with ONE shared helper (e.g. a lambda-accepting")
        appendLine("`withSyncServer { server, socket -> … }` — you choose the name, signature and placement)")
        appendLine("and migrate ALL 15 test methods of this file to it.")
        appendLine()
        appendLine("Rules:")
        appendLine("- BEFORE editing, record the original line number of each duplicated block, e.g. via")
        appendLine("  `grep -n \"XServer(ServerOptions(\" $TARGET_FILE` — you must report THOSE original lines;")
        appendLine("- migrate only `$TARGET_FILE_NAME` — do NOT touch the other protocol test files;")
        appendLine("- no behavior change: same server options, same handshake, same teardown, same assertions;")
        appendLine("- afterwards run `./gradlew test --tests \"org.jonnyzzz.xserver.XSyncProtocolTest\"` and")
        appendLine("  report the result. (Do NOT run the full `test` task — the Docker-based suites cannot run here.)")
    }

    private fun outputFormat(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("HELPER_CREATED: <path of the file that now contains the shared helper>")
        appendLine("MIGRATED: $TARGET_FILE:<original line of the migrated block>   ← one line per migrated call site")
        appendLine("TESTS_AFTER_CHANGE: <SUCCESS or FAILURE>")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The x11k project is open in IntelliJ IDEA — a single-module Kotlin/JVM Gradle project")
        appendLine("(a headless X11 server implementation).")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ via `steroid_execute_code`: Extract Method (or introduce the helper once and")
        appendLine("apply it to each duplicate found by the IDE's duplicate detection / structural search),")
        appendLine("and let PSI keep imports and references consistent. Do NOT sed.")
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
        appendLine("Beware: the 15 blocks are NOT byte-identical (different soTimeout usage, different join")
        appendLine("timeouts, different body shapes) — a naive regex replace breaks the tests.")
        appendLine()
        append(outputFormat())
    }

    companion object {
        private const val SCENARIO = "x11k__test_setup_helper"
        private const val TARGET_FILE = "src/test/kotlin/org/jonnyzzz/xserver/XSyncProtocolTest.kt"
        private const val TARGET_FILE_NAME = "XSyncProtocolTest.kt"

        /**
         * Ground truth at the pinned x11k commit `cfdf1f7d171df2581b63f7dfe675c343f6c86882`:
         * `grep -n "XServer(ServerOptions(" src/test/kotlin/org/jonnyzzz/xserver/XSyncProtocolTest.kt`
         * — the start line of the duplicated setup block in each of the 15 test methods.
         */
        private val EXPECTED_CALL_SITE_LINES =
            setOf(14, 110, 163, 198, 240, 272, 302, 334, 387, 429, 484, 517, 574, 612, 650)

        /** 13 of 15 — tolerates a couple of mis-reported originals without letting a partial sweep win. */
        private const val MIN_MIGRATED = 13

        /** Agents occasionally report the `use {`/thread line instead of the constructor line. */
        private const val LINE_TOLERANCE = 5
    }
}
