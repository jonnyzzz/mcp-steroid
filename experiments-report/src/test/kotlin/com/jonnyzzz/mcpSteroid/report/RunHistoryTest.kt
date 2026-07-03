package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [runHistories]: recency-weighted aggregation of ALL past runs of one
 * (scenario, agent, mode) — one attempt per cached build, newer runs counting more
 * (weight = 0.5^(ageDays / [HALF_LIFE_DAYS])), crashes excluded from rates but reported.
 */
class RunHistoryTest {
    private val now: Instant = Instant.parse("2026-07-01T00:00:00Z")
    private fun daysAgo(d: Long): Instant = now.minus(d, ChronoUnit.DAYS)

    private fun run(
        buildId: Long? = null,
        finishedAt: Instant? = null,
        exitCode: Int? = 0,
        claimedFix: Boolean? = true,
        durationMs: Long? = null,
        costUsd: Double? = null,
        model: String? = null,
        mode: McpMode = McpMode.WITH,
        scenario: String = "s",
    ) = AgentRun(
        scenario = scenario, agent = "claude", mode = mode, buildId = buildId,
        finishedAt = finishedAt, exitCode = exitCode, claimedFix = claimedFix,
        agentDurationMs = durationMs, costUsd = costUsd, model = model,
    )

    // ── grouping ─────────────────────────────────────────────────────────────

    @Test
    fun `aggregates per scenario-agent-mode`() {
        val histories = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(1)),
                run(buildId = 2, finishedAt = daysAgo(0)),
                run(buildId = 9, finishedAt = daysAgo(0), mode = McpMode.WITHOUT),
            ),
            now,
        )
        assertEquals(2, histories.size)
        assertEquals(2, histories.single { it.mode == McpMode.WITH }.runs)
        assertEquals(1, histories.single { it.mode == McpMode.WITHOUT }.runs)
    }

    @Test
    fun `empty input yields no histories`() {
        assertEquals(emptyList(), runHistories(emptyList(), now))
    }

    // ── recency-weighted success rate ────────────────────────────────────────

    @Test
    fun `a fresh success outweighs an old failure`() {
        // failure 90 days ago (w = 0.25) vs success today (w = 1.0):
        // rate = 1.0 / 1.25 = 80% — history says "mostly works NOW", not 50/50.
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(90), claimedFix = false),
                run(buildId = 2, finishedAt = daysAgo(0), claimedFix = true),
            ),
            now,
        ).single()
        assertEquals(80, h.weightedSuccessPct)
    }

    @Test
    fun `equally fresh runs weigh equally`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(0), claimedFix = false),
                run(buildId = 2, finishedAt = daysAgo(0), claimedFix = true),
            ),
            now,
        ).single()
        assertEquals(50, h.weightedSuccessPct)
    }

    @Test
    fun `runs with unknown outcome are excluded from the rate denominator`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(0), claimedFix = null), // succeeded() == null
                run(buildId = 2, finishedAt = daysAgo(0), claimedFix = true),
            ),
            now,
        ).single()
        assertEquals(2, h.runs)
        assertEquals(100, h.weightedSuccessPct)
    }

    // ── crashes: counted, never aggregated ──────────────────────────────────

    @Test
    fun `crashed runs are counted but excluded from rate and medians`() {
        val h = runHistories(
            listOf(
                // crash (exit 2): its bogus 2s / $0 numbers must not poison anything
                run(buildId = 1, finishedAt = daysAgo(0), exitCode = 2, claimedFix = false, durationMs = 2_000, costUsd = 0.0),
                run(buildId = 2, finishedAt = daysAgo(0), exitCode = 0, claimedFix = true, durationMs = 400_000, costUsd = 1.0),
                run(buildId = 3, finishedAt = daysAgo(0), exitCode = 0, claimedFix = false, durationMs = 600_000, costUsd = 3.0),
            ),
            now,
        ).single()
        assertEquals(3, h.runs)
        assertEquals(1, h.crashed)
        assertEquals(50, h.weightedSuccessPct, "1 of 2 CLEAN attempts succeeded")
        assertEquals(400_000L, h.weightedMedianDurationMs, "medians over clean attempts only")
        assertEquals(1.0, h.weightedMedianCostUsd)
    }

    @Test
    fun `a timeout (exit -1) is a clean attempt, not a crash`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(0), exitCode = -1, claimedFix = false, durationMs = 900_000),
                run(buildId = 2, finishedAt = daysAgo(0), exitCode = 0, claimedFix = true, durationMs = 300_000),
            ),
            now,
        ).single()
        assertEquals(0, h.crashed)
        assertEquals(50, h.weightedSuccessPct)
    }

    @Test
    fun `all runs crashed yields counts but no rates or medians`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(0), exitCode = 7, durationMs = 1_000),
                run(buildId = 2, finishedAt = daysAgo(0), exitCode = 7, durationMs = 2_000),
            ),
            now,
        ).single()
        assertEquals(2, h.runs)
        assertEquals(2, h.crashed)
        assertNull(h.weightedSuccessPct)
        assertNull(h.weightedMedianDurationMs)
        assertNull(h.weightedMedianCostUsd)
    }

    // ── weighted medians ─────────────────────────────────────────────────────

    @Test
    fun `null durations and costs are skipped, not treated as zero`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(0), durationMs = null, costUsd = null),
                run(buildId = 2, finishedAt = daysAgo(0), durationMs = 100_000, costUsd = null),
            ),
            now,
        ).single()
        assertEquals(100_000L, h.weightedMedianDurationMs, "single known duration")
        assertNull(h.weightedMedianCostUsd, "no cost known anywhere")
    }

    @Test
    fun `a fresh duration dominates the weighted median over stale ones`() {
        // two ancient fast runs (w ≈ 0.0625 each) vs one fresh slow run (w = 1.0):
        // cumulative weight crosses 50% at the fresh value.
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(180), durationMs = 60_000),
                run(buildId = 2, finishedAt = daysAgo(180), durationMs = 90_000),
                run(buildId = 3, finishedAt = daysAgo(0), durationMs = 500_000),
            ),
            now,
        ).single()
        assertEquals(500_000L, h.weightedMedianDurationMs)
    }

    // ── missing-date fallback: order from buildId, never crash ───────────────

    @Test
    fun `with no dates at all, buildId order drives the decay — one half-life per step back`() {
        // newest build (id 3) w=1, next (id 2) w=0.5, oldest (id 1) w=0.25.
        // Only the newest succeeded: rate = 1 / 1.75 ≈ 57%.
        val h = runHistories(
            listOf(
                run(buildId = 1, claimedFix = false),
                run(buildId = 2, claimedFix = false),
                run(buildId = 3, claimedFix = true),
            ),
            now,
        ).single()
        assertEquals(57, h.weightedSuccessPct)
    }

    @Test
    fun `an undated run borrows the age of the dated run with the nearest buildId`() {
        val ages = assignAgesDays(
            listOf(
                run(buildId = 10, finishedAt = daysAgo(30)),
                run(buildId = 11, finishedAt = null),
                run(buildId = 100, finishedAt = daysAgo(1)),
            ),
            now,
        )
        assertEquals(30.0, ages[0], 1e-9)
        assertEquals(30.0, ages[1], 1e-9, "undated buildId=11 anchors to dated buildId=10, not 100")
        assertEquals(1.0, ages[2], 1e-9)
    }

    @Test
    fun `runs with neither date nor buildId are treated as fresh`() {
        val ages = assignAgesDays(listOf(run(buildId = null, finishedAt = null)), now)
        assertEquals(listOf(0.0), ages)
    }

    @Test
    fun `a null now falls back to the newest dated run`() {
        val ages = assignAgesDays(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(10)),
                run(buildId = 2, finishedAt = daysAgo(0)),
            ),
            now = null,
        )
        assertEquals(10.0, ages[0], 1e-9)
        assertEquals(0.0, ages[1], 1e-9, "the newest dated run IS the clock when now is unknown")
    }

    @Test
    fun `a run dated after now clamps to age zero`() {
        val ages = assignAgesDays(listOf(run(buildId = 1, finishedAt = now.plusSeconds(3600))), now)
        assertEquals(listOf(0.0), ages)
    }

    // ── age span + model drift ───────────────────────────────────────────────

    @Test
    fun `span covers oldest to newest dated run`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(120)),
                run(buildId = 2, finishedAt = daysAgo(30)),
                run(buildId = 3, finishedAt = daysAgo(0)),
            ),
            now,
        ).single()
        assertEquals(120.0, h.spanDays!!, 1e-9)
    }

    @Test
    fun `no dated runs means no span`() {
        val h = runHistories(listOf(run(buildId = 1), run(buildId = 2)), now).single()
        assertNull(h.spanDays)
    }

    @Test
    fun `distinct models are listed oldest to newest`() {
        val h = runHistories(
            listOf(
                run(buildId = 3, finishedAt = daysAgo(0), model = "claude-opus-4-8"),
                run(buildId = 1, finishedAt = daysAgo(90), model = "claude-opus-4-6"),
                run(buildId = 2, finishedAt = daysAgo(30), model = "claude-opus-4-6"),
            ),
            now,
        ).single()
        assertEquals(listOf("claude-opus-4-6", "claude-opus-4-8"), h.models)
    }

    @Test
    fun `runs without a model do not contribute a model entry`() {
        val h = runHistories(
            listOf(
                run(buildId = 1, finishedAt = daysAgo(1), model = null),
                run(buildId = 2, finishedAt = daysAgo(0), model = "claude-opus-4-8"),
            ),
            now,
        ).single()
        assertEquals(listOf("claude-opus-4-8"), h.models)
    }
}
