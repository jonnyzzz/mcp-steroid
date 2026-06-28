package com.jonnyzzz.mcpSteroid.report

/**
 * Heuristic verdict for one (scenario, agent) with-vs-without-MCP pair.
 *
 * "Success" is taken from the agent's own outcome signal in precedence order
 * `claimedFix` → `buildSuccess` → `testStatus == SUCCESS` (see [AgentRun.succeeded]). This is the
 * arena's primary signal; the raw build/test/duration/cost columns are surfaced alongside so a human
 * (or a later RLM pass) can see the nuance the heuristic flattens — e.g. a "BUILD FAILURE" caused by
 * unrelated infrastructure noise even though the agent fixed the task.
 */
enum class Verdict { MCP_HELPED, MCP_HURT, NEUTRAL, INCOMPLETE }

/** A with-vs-without-MCP comparison for one (scenario, agent). */
data class Comparison(
    val scenario: String,
    val agent: String,
    val withMcp: AgentRun?,
    val without: AgentRun?,
) {
    val verdict: Verdict = run {
        val w = withMcp?.succeeded()
        val o = without?.succeeded()
        when {
            withMcp == null || without == null || w == null || o == null -> Verdict.INCOMPLETE
            w && !o -> Verdict.MCP_HELPED
            !w && o -> Verdict.MCP_HURT
            else -> Verdict.NEUTRAL
        }
    }

    /** with − without, in ms. Negative ⇒ the MCP run was faster. Null if either side lacks a duration. */
    val durationDeltaMs: Long? = delta(withMcp?.agentDurationMs, without?.agentDurationMs) { a, b -> a - b }

    /** with − without, in USD. Negative ⇒ the MCP run was cheaper. */
    val costDeltaUsd: Double? = delta(withMcp?.costUsd, without?.costUsd) { a, b -> a - b }

    private inline fun <T> delta(a: T?, b: T?, op: (T, T) -> T): T? = if (a != null && b != null) op(a, b) else null
}

object Aggregator {
    /**
     * Group runs into one [Comparison] per (scenario, agent). Deterministic order: scenario, then agent.
     * When a (scenario, agent) has multiple runs for the same mode (e.g. duplicated log blocks), the first
     * is kept — de-duplication/merging across sources is [mergeRuns]'s job and should happen before this.
     */
    fun compare(runs: List<AgentRun>): List<Comparison> =
        runs.groupBy { it.scenario to it.agent }
            .map { (key, group) ->
                Comparison(
                    scenario = key.first,
                    agent = key.second,
                    withMcp = group.firstOrNull { it.mode == McpMode.WITH },
                    without = group.firstOrNull { it.mode == McpMode.WITHOUT },
                )
            }
            .sortedWith(compareBy({ it.scenario }, { it.agent }))
}

/**
 * Best-available "did the agent solve the task" signal, or null when nothing tells us.
 *
 * Precedence puts the OBJECTIVE sandbox build/test outcome ahead of the agent's own `claimedFix`:
 * agents routinely claim success while the build actually failed (observed on Petclinic27 — the
 * without-MCP run claimed a fix yet produced BUILD FAILURE with only 2 tests). A build counts as
 * success only if it built AND no tests failed. Falls back to `claimedFix`, then the JUnit status.
 */
fun AgentRun.succeeded(): Boolean? = when {
    buildSuccess != null -> buildSuccess && (testsFail ?: 0) == 0
    claimedFix != null -> claimedFix
    testStatus != null -> testStatus.equals("SUCCESS", ignoreCase = true)
    else -> null
}
