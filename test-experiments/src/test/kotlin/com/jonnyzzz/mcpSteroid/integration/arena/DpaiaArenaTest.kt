/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Integration test that runs a single dpaia.dev arena test case.
 *
 * Each test method starts a **fresh Docker container** with IntelliJ IDEA so agents
 * never see state left by a previous run. The test matrix is:
 *
 * - `[claude+mcp]` / `[claude+none]`
 * - `[codex+mcp]`  / `[codex+none]`
 * - `[gemini+mcp]` / `[gemini+none]`
 *
 * The dataset is downloaded from:
 * https://github.com/dpaia/ee-dataset/blob/main/datasets/java-spring-ee-dataset.json
 *
 * To run a specific test case, set the system property:
 *   -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
 *
 * To run only specific agents (comma-separated), set:
 *   -Darena.test.agents=claude
 *   -Darena.test.agents=claude,gemini
 * When omitted, all agents run.
 *
 * To run a single agent+mode:
 *   --tests '*DpaiaArenaTest.claude with mcp'
 */
class DpaiaArenaTest {

    // ── Claude ───────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() {
        runArenaTest(agentName = "claude", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() {
        runArenaTest(agentName = "claude", withMcp = false)
    }

    // ── Codex ────────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() {
        runArenaTest(agentName = "codex", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() {
        runArenaTest(agentName = "codex", withMcp = false)
    }

    // ── Gemini ───────────────────────────────────────────────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `gemini with mcp`() {
        runArenaTest(agentName = "gemini", withMcp = true)
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `gemini without mcp`() {
        runArenaTest(agentName = "gemini", withMcp = false)
    }

    // ── Test execution ───────────────────────────────────────────────────────

    private fun runArenaTest(agentName: String, withMcp: Boolean) {
        val enabledAgents = System.getProperty("arena.test.agents")
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
        Assumptions.assumeTrue(
            enabledAgents == null || agentName.lowercase() in enabledAgents,
            "Agent '$agentName' not in arena.test.agents=${enabledAgents?.joinToString(",")}"
        )

        val testCase = resolvedTestCase
        val modeLabel = if (withMcp) "mcp" else "none"
        val caseConfig = DpaiaCuratedCases.CASE_CONFIGS[testCase.instanceId]
            ?: DpaiaCuratedCases.CaseConfig()

        val lifetime = CloseableStackHost()
        try {
            val aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE
            val mcpMode = if (withMcp) null else McpConnectionMode.None

            val session = IntelliJContainer.create(
                lifetime,
                consoleTitle = "arena-${testCase.instanceId}-$agentName-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = testCase.cloneUrl,
                    repoOwnerAndName = testCase.repo.removeSuffix(".git"),
                    baseCommit = testCase.baseCommit,
                    testPatch = testCase.testPatch,
                    displayName = testCase.instanceId,
                    buildSystem = testCase.buildSystem,
                ),
                aiMode = aiMode,
                mcpConnectionMode = mcpMode,
                mountDockerSocket = true,
            ).waitForProjectReady(
                timeoutMillis = caseConfig.projectReadyTimeoutMs,
                buildSystem = when (testCase.buildSystem) {
                    "maven" -> BuildSystem.MAVEN
                    "gradle" -> BuildSystem.GRADLE
                    else -> BuildSystem.NONE
                },
                compileProject = true,
            )

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                "gemini" -> session.aiAgents.gemini
                else -> error("Unknown agent: $agentName")
            }

            val ideProjectDir = session.intellijDriver.getGuestProjectDir()
            val runner = ArenaTestRunner(
                container = session.scope,
                projectGuestDir = ideProjectDir,
            )

            val result = runner.runTest(
                testCase = testCase,
                agent = agent,
                withMcp = withMcp,
                timeoutSeconds = caseConfig.agentTimeoutSeconds,
                predeployedProjectDir = ideProjectDir,
            )

            val rawOutput = result.agentResult.stdout
            val tokens = extractTokenUsage(rawOutput)
            val testMetrics = extractTestMetrics(rawOutput)
            val decodedLogMetrics = findDecodedLogFile(session.runDirInContainer)
                ?.let { extractDecodedLogMetrics(it.readText()) }

            results.add(
                RunRecord(
                    instanceId = testCase.instanceId,
                    agentName = agentName,
                    withMcp = withMcp,
                    exitCode = result.agentResult.exitCode,
                    claimedFix = result.evaluation.agentClaimedFix,
                    usedMcpSteroid = result.evaluation.usedMcpSteroid,
                    summary = result.evaluation.agentSummary,
                    agentDurationMs = result.agentDurationMs,
                    tokenUsage = tokens,
                    testMetrics = testMetrics,
                    decodedLogMetrics = decodedLogMetrics,
                )
            )

            writeRunSummary(testCase, agentName, modeLabel, result, session.runDirInContainer, tokens, testMetrics, decodedLogMetrics)

            // Lenient assertion: agent either exited cleanly or claimed a fix.
            check(result.evaluation.agentExitedSuccessfully || result.evaluation.agentClaimedFix) {
                "Agent [$agentName+$modeLabel] neither exited successfully (exit=${result.agentResult.exitCode}) " +
                        "nor claimed a fix for ${testCase.instanceId}."
            }

            if (withMcp) {
                check(result.evaluation.usedMcpSteroid) {
                    "Agent [$agentName+mcp] did not use steroid_execute_code for ${testCase.instanceId}."
                }
            }

            println("[ARENA] [$agentName+$modeLabel] ${testCase.instanceId} — " +
                    "fix=${result.evaluation.agentClaimedFix}, " +
                    "mcp=${result.evaluation.usedMcpSteroid}, " +
                    "exit=${result.agentResult.exitCode}, " +
                    "duration=${result.agentDurationMs / 1000}s")
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val DATASET_URL =
            "https://raw.githubusercontent.com/dpaia/ee-dataset/main/datasets/java-spring-ee-dataset.json"

        private const val DEFAULT_INSTANCE_ID = "dpaia__empty__maven__springboot3-3"

        private val dataset by lazy {
            println("[ARENA] Downloading dataset from $DATASET_URL ...")
            val cases = DpaiaDatasetLoader.loadFromUrl(DATASET_URL)
            println("[ARENA] Loaded ${cases.size} test cases")
            cases
        }

        private val resolvedTestCase: DpaiaTestCase by lazy {
            val id = System.getProperty("arena.test.instanceId") ?: DEFAULT_INSTANCE_ID
            DpaiaDatasetLoader.findById(dataset, id)
        }

        val results = CopyOnWriteArrayList<RunRecord>()

        @JvmStatic
        @AfterAll
        fun printComparisonTable() {
            if (results.isEmpty()) {
                println("[ARENA] No results to compare.")
                return
            }

            println()
            println("╔════════════════════════════════════════════════════════════════════════════════╗")
            println("║                        ARENA COMPARISON TABLE                                 ║")
            println("╠════════════════════════════════════════════════════════════════════════════════╣")
            println("║ Agent+Mode       │ Fix? │ MCP? │ Exit │ Duration │ Summary                    ║")
            println("╠════════════════════════════════════════════════════════════════════════════════╣")
            for (r in results.sortedWith(compareBy({ it.agentName }, { !it.withMcp }))) {
                val mode = if (r.withMcp) "mcp " else "none"
                val label = "${r.agentName}+$mode".padEnd(16)
                val fix = if (r.claimedFix) " YES" else "  NO"
                val mcp = if (r.usedMcpSteroid) " YES" else "  NO"
                val exit = (r.exitCode?.toString() ?: "?").padStart(4)
                val dur = "${r.agentDurationMs / 1000}s".padStart(8)
                val summary = (r.summary ?: "").take(26).padEnd(26)
                println("║ $label │ $fix │ $mcp │ $exit │ $dur │ $summary ║")
            }
            println("╚════════════════════════════════════════════════════════════════════════════════╝")
            println()
        }

        private fun writeRunSummary(
            testCase: DpaiaTestCase,
            agentName: String,
            modeLabel: String,
            result: ArenaTestResult,
            runDir: File,
            tokens: TokenUsage? = null,
            testMetrics: TestMetrics? = null,
            decodedLogMetrics: DecodedLogMetrics? = null,
        ) {
            val summary = buildJsonObject {
                put("instance_id", testCase.instanceId)
                put("agent", agentName)
                put("mode", modeLabel)
                put("run_dir", runDir.absolutePath)
                put("exit_code", result.agentResult.exitCode ?: -1)
                put("agent_claimed_fix", result.evaluation.agentClaimedFix)
                put("used_mcp_steroid", result.evaluation.usedMcpSteroid)
                put("agent_duration_ms", result.agentDurationMs)
                tokens?.let { t ->
                    put("input_tokens", t.inputTokens)
                    put("output_tokens", t.outputTokens)
                    put("cache_read_tokens", t.cacheReadTokens)
                    put("cache_creation_tokens", t.cacheCreationTokens)
                    t.costUsd?.let { put("cost_usd", it) }
                    t.numTurns?.let { put("num_turns", it) }
                    t.durationApiMs?.let { put("duration_api_ms", it) }
                }
                testMetrics?.let { m ->
                    put("tests_run", m.testsRun)
                    put("tests_pass", m.testsPass)
                    put("tests_fail", m.testsFail)
                    m.buildSuccess?.let { put("build_success", it) }
                }
                decodedLogMetrics?.let { d ->
                    put("exec_code_calls", d.execCodeCalls)
                    put("read_calls", d.readCalls)
                    put("write_calls", d.writeCalls)
                    put("edit_calls", d.editCalls)
                    put("bash_calls", d.bashCalls)
                    put("glob_calls", d.globCalls)
                    put("grep_calls", d.grepCalls)
                }
                put("agent_summary", result.evaluation.agentSummary ?: "")
                put("timestamp", java.time.Instant.now().toString())
            }
            val summaryFile = IdeTestFolders.testOutputDir
                .resolve("dpaia-arena-run-${testCase.instanceId}-$agentName-$modeLabel.json")
            summaryFile.parentFile.mkdirs()
            summaryFile.writeText(summary.toString())
            println("[ARENA] Run summary written to: ${summaryFile.absolutePath}")

            // Append to comparison CSV
            val passLabel = System.getProperty("arena.pass.label", "")
            val csvFile = IdeTestFolders.testOutputDir.resolve("arena-comparison.csv")
            appendComparisonCsv(
                csvFile = csvFile,
                instanceId = testCase.instanceId,
                passLabel = passLabel,
                claimedFix = result.evaluation.agentClaimedFix,
                durationS = result.agentDurationMs / 1000,
                tokens = tokens,
                testMetrics = testMetrics,
                decoded = decodedLogMetrics,
            )
        }
    }

    data class RunRecord(
        val instanceId: String,
        val agentName: String,
        val withMcp: Boolean,
        val exitCode: Int?,
        val claimedFix: Boolean,
        val usedMcpSteroid: Boolean,
        val summary: String?,
        val agentDurationMs: Long,
        val tokenUsage: TokenUsage? = null,
        val testMetrics: TestMetrics? = null,
        val decodedLogMetrics: DecodedLogMetrics? = null,
    )
}
