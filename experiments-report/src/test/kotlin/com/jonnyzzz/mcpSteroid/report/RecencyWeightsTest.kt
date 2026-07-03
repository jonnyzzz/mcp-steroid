package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the recency-weighting math: exponential decay by age (half-life
 * [HALF_LIFE_DAYS]), the weighted median, and the lenient `finishDate` parsing — the three
 * building blocks of the run-history aggregation.
 */
class RecencyWeightsTest {

    // ── exponential decay ────────────────────────────────────────────────────

    @Test
    fun `a run finished today has full weight`() {
        assertEquals(1.0, decayWeight(0.0))
    }

    @Test
    fun `a run one half-life old has half the weight`() {
        assertEquals(0.5, decayWeight(HALF_LIFE_DAYS), 1e-12)
    }

    @Test
    fun `two half-lives quarter the weight`() {
        assertEquals(0.25, decayWeight(2 * HALF_LIFE_DAYS), 1e-12)
    }

    @Test
    fun `a half-year-old run contributes less than 7 percent`() {
        // ~4 half-lives at H=45 — the design point: runs from a previous plugin/LLM generation
        // are still visible in the aggregate but can no longer dominate it.
        assertTrue(decayWeight(180.0) < 0.07)
        assertEquals(0.0625, decayWeight(180.0), 1e-12)
    }

    @Test
    fun `decay is monotonically decreasing`() {
        assertTrue(decayWeight(1.0) > decayWeight(2.0))
        assertTrue(decayWeight(100.0) > decayWeight(101.0))
        assertTrue(decayWeight(10_000.0) > 0.0, "never hits zero — old runs keep a trace")
    }

    // ── weighted median ──────────────────────────────────────────────────────

    @Test
    fun `weighted median of nothing is null`() {
        assertNull(weightedMedian(emptyList()))
    }

    @Test
    fun `weighted median of a single value is that value`() {
        assertEquals(5.0, weightedMedian(listOf(5.0 to 0.3)))
    }

    @Test
    fun `equal weights pick the middle value`() {
        assertEquals(2.0, weightedMedian(listOf(3.0 to 1.0, 1.0 to 1.0, 2.0 to 1.0)))
    }

    @Test
    fun `a heavy fresh run pulls the median to itself`() {
        // old cheap run (w=1) vs fresh expensive run (w=9): cumulative weight crosses 50% at the
        // fresh value — the aggregate reads as "what a run costs NOW".
        assertEquals(10.0, weightedMedian(listOf(1.0 to 1.0, 10.0 to 9.0)))
    }

    @Test
    fun `input order does not matter`() {
        val a = weightedMedian(listOf(4.0 to 1.0, 1.0 to 2.0, 9.0 to 0.5))
        val b = weightedMedian(listOf(9.0 to 0.5, 4.0 to 1.0, 1.0 to 2.0))
        assertEquals(a, b)
    }

    @Test
    fun `all-zero weights yield null instead of dividing by zero`() {
        assertNull(weightedMedian(listOf(1.0 to 0.0, 2.0 to 0.0)))
    }

    @Test
    fun `exactly two equal weights pick the lower value (cumulative crossing rule)`() {
        // cum(100)=1.0 ≥ total/2=1.0 → 100. Documented: lower weighted median, deterministic.
        assertEquals(100.0, weightedMedian(listOf(200.0 to 1.0, 100.0 to 1.0)))
    }

    // ── lenient finishDate parsing ───────────────────────────────────────────

    @Test
    fun `parses an ISO-8601 instant`() {
        assertEquals(Instant.parse("2026-06-20T10:15:30Z"), parseFinishDate("2026-06-20T10:15:30Z"))
    }

    @Test
    fun `parses an ISO-8601 offset date-time`() {
        assertEquals(Instant.parse("2026-06-20T10:15:30Z"), parseFinishDate("2026-06-20T12:15:30+02:00"))
    }

    @Test
    fun `parses TeamCity's yyyyMMdd'T'HHmmssZ format`() {
        assertEquals(Instant.parse("2026-06-20T10:15:30Z"), parseFinishDate("20260620T101530+0000"))
        assertEquals(Instant.parse("2026-06-20T08:15:30Z"), parseFinishDate("20260620T101530+0200"))
    }

    @Test
    fun `garbage, blank and null yield null — never an exception`() {
        assertNull(parseFinishDate("not-a-date"))
        assertNull(parseFinishDate(""))
        assertNull(parseFinishDate("   "))
        assertNull(parseFinishDate(null))
        assertNull(parseFinishDate("2026-13-45T99:99:99Z"))
    }
}
