package com.jonnyzzz.mcpSteroid.report

/**
 * Collapse runs that describe the same (scenario, agent, mode) into one, filling each field from the
 * first source that provides a non-null value. Lets the build log, the summary JSON, and the CI
 * test results each contribute the fields they know without clobbering each other, and de-duplicates the
 * doubled `[ARENA]` blocks CI emits. Group order is preserved (stable, deterministic output).
 */
fun mergeRuns(runs: List<AgentRun>): List<AgentRun> {
    val order = LinkedHashMap<Triple<String, String, McpMode>, AgentRun>()
    for (r in runs) {
        val key = Triple(r.scenario, r.agent, r.mode)
        val prev = order[key]
        order[key] = if (prev == null) r else mergeTwo(prev, r)
    }
    return order.values.toList()
}

/** Field-wise merge: [a] wins where it has a value, [b] fills the gaps. */
private fun mergeTwo(a: AgentRun, b: AgentRun): AgentRun = a.copy(
    buildConfigId = a.buildConfigId ?: b.buildConfigId,
    buildId = a.buildId ?: b.buildId,
    finishedAt = a.finishedAt ?: b.finishedAt,
    testStatus = a.testStatus ?: b.testStatus,
    testDurationMs = a.testDurationMs ?: b.testDurationMs,
    agentDurationMs = a.agentDurationMs ?: b.agentDurationMs,
    exitCode = a.exitCode ?: b.exitCode,
    claimedFix = a.claimedFix ?: b.claimedFix,
    usedMcp = a.usedMcp ?: b.usedMcp,
    testsRun = a.testsRun ?: b.testsRun,
    testsFail = a.testsFail ?: b.testsFail,
    buildSuccess = a.buildSuccess ?: b.buildSuccess,
    inputTokens = a.inputTokens ?: b.inputTokens,
    outputTokens = a.outputTokens ?: b.outputTokens,
    cacheReadTokens = a.cacheReadTokens ?: b.cacheReadTokens,
    cacheCreationTokens = a.cacheCreationTokens ?: b.cacheCreationTokens,
    costUsd = a.costUsd ?: b.costUsd,
    numTurns = a.numTurns ?: b.numTurns,
    model = a.model ?: b.model,
    agentVersion = a.agentVersion ?: b.agentVersion,
    contextWindow = a.contextWindow ?: b.contextWindow,
    maxOutputTokens = a.maxOutputTokens ?: b.maxOutputTokens,
    toolCalls = a.toolCalls.ifEmpty { b.toolCalls },
    execCodeCalls = a.execCodeCalls ?: b.execCodeCalls,
    summary = a.summary ?: b.summary,
)
