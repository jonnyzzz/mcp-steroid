package com.jonnyzzz.mcpSteroid.report

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Half-life of a run's influence on the history aggregates, in days.
 *
 * Both the plugin under test AND the LLMs behind the agents turn over on roughly a 1–2 month
 * cadence, so 45 days makes the current generation dominate: a run one half-life old counts half,
 * and anything beyond ~4 half-lives (≈ 6 months — the collector's fetch depth) contributes under
 * 7% — still visible as history, but powerless over the aggregate.
 */
const val HALF_LIFE_DAYS = 45.0

private const val MILLIS_PER_DAY = 86_400_000.0

/** Exponential recency decay: weight = 0.5^(ageDays / [HALF_LIFE_DAYS]). Never reaches zero. */
fun decayWeight(ageDays: Double): Double = 0.5.pow(ageDays / HALF_LIFE_DAYS)

/**
 * Weighted median: sort by value and return the first value whose cumulative weight reaches half
 * the total — the "lower weighted median": deterministic, no interpolation, always an actually
 * observed value. Null for an empty list or all-zero weights (never divides by zero).
 */
fun weightedMedian(valuesToWeights: List<Pair<Double, Double>>): Double? {
    val sorted = valuesToWeights.filter { it.second > 0.0 }.sortedBy { it.first }
    val total = sorted.sumOf { it.second }
    if (total <= 0.0) return null
    var cumulative = 0.0
    for ((value, weight) in sorted) {
        cumulative += weight
        if (cumulative >= total / 2) return value
    }
    return sorted.last().first // unreachable in exact arithmetic; guards float drift
}

/** TeamCity's `finishDate` shape, e.g. `20260620T101530+0000`. */
private val TEAMCITY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssZ")

/**
 * Lenient `finishDate` parsing: ISO-8601 instant (`2026-06-20T10:15:30Z`), ISO offset date-time
 * (`…+02:00`), TeamCity's `yyyyMMdd'T'HHmmssZ`, or a bare local date-time (read as UTC). Returns
 * null — never throws — on anything else, so an old cache without dates degrades gracefully.
 */
fun parseFinishDate(text: String?): Instant? {
    val s = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    runCatching { return Instant.parse(s) }
    runCatching { return OffsetDateTime.parse(s).toInstant() }
    runCatching { return OffsetDateTime.parse(s, TEAMCITY_DATE).toInstant() }
    runCatching { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC) }
    return null
}

/**
 * Recency-weighted aggregate of EVERY cached attempt at one (scenario, agent, mode) — not a re-run
 * median of an identical task, but the task's whole trajectory over ~6 months of collected builds,
 * where newer runs count more ([decayWeight]) because the plugin and the LLMs both change.
 *
 * Crashed attempts ([AgentRun.agentCrashed] — the tooling died, not the task) are COUNTED in
 * [crashed] but excluded from the rate and the medians so a 2-second 429 corpse can never drag a
 * median down. Attempts whose outcome is unknown ([succeeded] == null) stay out of the rate's
 * denominator.
 */
data class RunHistory(
    val scenario: String,
    val agent: String,
    val mode: McpMode,
    /** Total attempts, including crashed ones. */
    val runs: Int,
    /** Attempts where the agent tooling itself failed. */
    val crashed: Int,
    /** Σ w·success / Σ w over clean attempts with a known outcome, in percent; null if none. */
    val weightedSuccessPct: Int?,
    /** Weighted median agent duration over clean attempts; null when no clean attempt has one. */
    val weightedMedianDurationMs: Long?,
    /** Weighted median cost over clean attempts; null when no clean attempt has one. */
    val weightedMedianCostUsd: Double?,
    /** Oldest → newest dated attempt, in days; null when no attempt carries a date. */
    val spanDays: Double?,
    /** Distinct models seen, oldest → newest — discloses that old runs were a different beast. */
    val models: List<String>,
)

/**
 * Aggregate [allBuilds] (one merged run per scenario/agent/mode/build — [InputReader.readAll])
 * into one [RunHistory] per (scenario, agent, mode).
 *
 * [now] is the report's own generatedAt so the whole pipeline stays pure — pass null only when no
 * clock is known, in which case the newest dated run acts as "now".
 */
fun runHistories(allBuilds: List<AgentRun>, now: Instant?): List<RunHistory> =
    allBuilds.groupBy { Triple(it.scenario, it.agent, it.mode) }
        .map { (key, group) -> historyOf(key.first, key.second, key.third, group, now) }
        .sortedWith(compareBy({ it.scenario }, { it.agent }, { it.mode }))

private fun historyOf(scenario: String, agent: String, mode: McpMode, group: List<AgentRun>, now: Instant?): RunHistory {
    val ages = assignAgesDays(group, now)
    val weights = ages.map(::decayWeight)
    val clean = group.indices.filter { !group[it].agentCrashed() }

    var weightTotal = 0.0
    var weightSucceeded = 0.0
    for (i in clean) {
        val succeeded = group[i].succeeded() ?: continue
        weightTotal += weights[i]
        if (succeeded) weightSucceeded += weights[i]
    }

    val dated = group.mapNotNull { it.finishedAt }

    return RunHistory(
        scenario = scenario,
        agent = agent,
        mode = mode,
        runs = group.size,
        crashed = group.size - clean.size,
        weightedSuccessPct = if (weightTotal > 0.0) (weightSucceeded / weightTotal * 100).roundToInt() else null,
        weightedMedianDurationMs = weightedMedian(
            clean.mapNotNull { i -> group[i].agentDurationMs?.let { it.toDouble() to weights[i] } }
        )?.roundToLong(),
        weightedMedianCostUsd = weightedMedian(
            clean.mapNotNull { i -> group[i].costUsd?.let { it to weights[i] } }
        ),
        spanDays = if (dated.isEmpty()) null
        else Duration.between(dated.min(), dated.max()).toMillis() / MILLIS_PER_DAY,
        // oldest → newest: stable sort by age descending keeps same-age runs in input order.
        models = group.indices.sortedByDescending { ages[it] }.mapNotNull { group[it].model }.distinct(),
    )
}

/**
 * Age in days of every run in [runs] (a single (scenario, agent, mode) group), relative to [now].
 *
 * Graceful degradation, in order:
 *  1. a dated run ([AgentRun.finishedAt]) → its actual age, clamped at 0 (never negative);
 *  2. an undated run with a buildId, when the group has dated runs → borrows the age of the dated
 *     run with the NEAREST buildId (buildIds are monotonic, so neighbours finished around the
 *     same time);
 *  3. no dated runs at all → order-only decay: distinct buildIds newest-first, one half-life per
 *     step back (weights 1, ½, ¼, …);
 *  4. neither date nor buildId (flat local layout) → age 0: what you just ran is fresh.
 *
 * A null [now] falls back to the newest dated run — that run IS the clock, at age 0.
 */
fun assignAgesDays(runs: List<AgentRun>, now: Instant?): List<Double> {
    val clock: Instant? = now ?: runs.mapNotNull { it.finishedAt }.maxOrNull()

    fun ageOf(finishedAt: Instant): Double =
        maxOf(0.0, Duration.between(finishedAt, clock!!).toMillis() / MILLIS_PER_DAY)

    val anchors = runs.filter { it.finishedAt != null && it.buildId != null }
    val rankOfBuild: Map<Long, Int> = runs.mapNotNull { it.buildId }.distinct().sortedDescending()
        .withIndex().associate { (rank, id) -> id to rank }

    return runs.map { r ->
        val finishedAt = r.finishedAt
        val buildId = r.buildId
        when {
            finishedAt != null && clock != null -> ageOf(finishedAt)
            buildId != null && anchors.isNotEmpty() && clock != null ->
                ageOf(anchors.minBy { abs(it.buildId!! - buildId) }.finishedAt!!)
            buildId != null && anchors.isEmpty() -> (rankOfBuild[buildId] ?: 0) * HALF_LIFE_DAYS
            else -> 0.0
        }
    }
}
