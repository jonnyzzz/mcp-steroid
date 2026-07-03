package com.jonnyzzz.mcpSteroid.report

/** Whether MCP Steroid was enabled for an agent run. */
enum class McpMode { WITH, WITHOUT }

/**
 * One agent run on one scenario in one MCP mode — the atomic unit the dashboard compares.
 *
 * Every metric is nullable: different sources fill different subsets. The build log (baseline,
 * always present) and the per-run summary JSON / comparison CSV (enhanced, when published) all
 * map onto this one shape, and CI test results supply the authoritative [testStatus] +
 * [testDurationMs]. Merging is "first non-null wins per field" across sources.
 */
data class AgentRun(
    val scenario: String,
    val agent: String,
    val mode: McpMode,
    val buildConfigId: String? = null,
    val buildId: Long? = null,
    /** When the build that produced this run finished (collector meta.json `finishDate`); null for old caches. */
    val finishedAt: java.time.Instant? = null,
    // JUnit / CI test occurrence (authoritative pass/fail of the *test*, lenient: passes if the
    // agent exited cleanly or claimed a fix — NOT a quality signal on its own).
    val testStatus: String? = null,
    val testDurationMs: Long? = null,
    // [ARENA]-reported run facts.
    val agentDurationMs: Long? = null,
    val exitCode: Int? = null,
    val claimedFix: Boolean? = null,
    val usedMcp: Boolean? = null,
    // Build/test outcome the agent produced inside the sandbox (the real quality signal).
    val testsRun: Int? = null,
    val testsFail: Int? = null,
    val buildSuccess: Boolean? = null,
    // Cost / effort.
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadTokens: Long? = null,
    val cacheCreationTokens: Long? = null,
    val costUsd: Double? = null,
    val numTurns: Int? = null,
    // Identity of the agent run, straight from the agent output (NDJSON).
    val model: String? = null,
    val agentVersion: String? = null,
    // Token budget the agent ran under (context window / max output), from the NDJSON modelUsage.
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    // Per-tool call counts (Read, Edit, Write, Bash, Glob, Grep, steroid_execute_code, …). The dashboard
    // diffs these between the with- and without-MCP runs.
    val toolCalls: Map<String, Int> = emptyMap(),
    val execCodeCalls: Int? = null,
    val summary: String? = null,
)
