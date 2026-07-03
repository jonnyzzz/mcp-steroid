package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * History across the WHOLE fetch cache: superseded builds' folders stay on disk, and while the
 * PRIMARY comparison row is still built exclusively from the LATEST build (pinned by
 * [FailedLegAndStaleBuildTest] — do not regress), every cached build contributes one attempt to the
 * recency-weighted per-(scenario, agent, mode) run history.
 *
 * Built on the same VERBATIM fixtures: build 992109227 (both legs crashed, exit 1) is superseded by
 * build 992152358 (mcp leg clean 459s + claimed fix, none leg crashed). The metas carry the new
 * `finishDate` — one in TeamCity's `yyyyMMdd'T'HHmmssZ`, one in ISO-8601, both must parse.
 */
class RunHistoryFromCacheTest {
    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun place(dir: Path, rel: String, content: String) {
        val f = dir.resolve(rel).toFile()
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private val cfg = "mcp_steroid_IntegrationTests_KeycloakRename_Claude"

    private fun meta(buildId: Long, status: String, finishDate: String?) = buildString {
        append("""{"buildConfigId":"$cfg","buildId":$buildId,"scenario":"KeycloakRename","agent":"claude","status":"$status"""")
        if (finishDate != null) append(""","finishDate":"$finishDate"""")
        append("}")
    }

    /** Older crashed build finished 40 days before the newest — TC-format date; newest is ISO. */
    private fun placeBothBuilds(dir: Path) {
        place(dir, "builds/${cfg}__992109227/log.txt", fixtureText("arena-rename-crash429.txt"))
        place(dir, "builds/${cfg}__992109227/meta.json", meta(992109227, "FAILURE", "20260523T120000+0000"))
        place(dir, "builds/${cfg}__992152358/log.txt", fixtureText("arena-rename-final.txt"))
        place(dir, "builds/${cfg}__992152358/meta.json", meta(992152358, "SUCCESS", "2026-07-02T12:00:00Z"))
    }

    @Test
    fun `readAll keeps one merged run per build while latest stays latest-build-only`(@TempDir dir: Path) {
        placeBothBuilds(dir)

        val collected = InputReader.readAll(dir.toFile())

        // latest — exactly the runs the primary comparison is built from (with + without).
        assertEquals(2, collected.latest.size)
        assertTrue(collected.latest.all { it.buildId == 992152358L })

        // allBuilds — one merged run per (mode, build): 2 modes × 2 builds. The doubled [ARENA]
        // blocks inside one build's log still collapse into one run (source-merge stays per build).
        assertEquals(4, collected.allBuilds.size)
        val withRuns = collected.allBuilds.filter { it.mode == McpMode.WITH }.sortedBy { it.buildId }
        assertEquals(listOf(992109227L, 992152358L), withRuns.map { it.buildId })
        assertEquals(2_000L, withRuns[0].agentDurationMs, "crashed attempt kept as history")
        assertEquals(459_000L, withRuns[1].agentDurationMs)
    }

    @Test
    fun `runs are stamped with the build's finishDate in either format`(@TempDir dir: Path) {
        placeBothBuilds(dir)

        val all = InputReader.readAll(dir.toFile()).allBuilds
        assertEquals(
            Instant.parse("2026-05-23T12:00:00Z"),
            all.first { it.buildId == 992109227L }.finishedAt,
            "TeamCity yyyyMMdd'T'HHmmssZ finishDate parsed",
        )
        assertEquals(
            Instant.parse("2026-07-02T12:00:00Z"),
            all.first { it.buildId == 992152358L }.finishedAt,
            "ISO-8601 finishDate parsed",
        )
    }

    @Test
    fun `a meta without finishDate leaves runs undated and nothing crashes`(@TempDir dir: Path) {
        place(dir, "builds/${cfg}__992152358/log.txt", fixtureText("arena-rename-final.txt"))
        place(dir, "builds/${cfg}__992152358/meta.json", meta(992152358, "SUCCESS", finishDate = null))

        val all = InputReader.readAll(dir.toFile()).allBuilds
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.finishedAt == null })
        // the whole pipeline still renders
        assertTrue(HtmlRenderer.render(buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")).isNotEmpty())
    }

    @Test
    fun `report carries per-leg weighted history over all cached builds`(@TempDir dir: Path) {
        placeBothBuilds(dir)

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")

        val with = report.histories.single { it.mode == McpMode.WITH }
        assertEquals(2, with.runs)
        assertEquals(1, with.crashed, "the 429 corpse counts as a crash, not an attempt")
        assertEquals(100, with.weightedSuccessPct, "the only CLEAN attempt succeeded")
        assertEquals(459_000L, with.weightedMedianDurationMs, "median over clean attempts only — never the 2s corpse")
        assertEquals(40.0, with.spanDays!!, 0.5, "2026-05-23 → 2026-07-02")

        val without = report.histories.single { it.mode == McpMode.WITHOUT }
        assertEquals(2, without.runs)
        assertEquals(2, without.crashed, "both baseline attempts crashed (exit 1)")
        assertNull(without.weightedSuccessPct)

        // The PRIMARY comparison is untouched: still exclusively the latest build's runs.
        val cmp = report.comparisons.single()
        assertEquals(459_000L, cmp.withMcp?.agentDurationMs)
        assertEquals(true, cmp.withMcp?.claimedFix)
    }

    @Test
    fun `dashboard shows a history line for repeat runs`(@TempDir dir: Path) {
        placeBothBuilds(dir)

        val html = HtmlRenderer.render(buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z"))
        assertTrue(html.contains("run history"), "repeat runs must surface a history line")
        assertTrue(html.contains("2 runs"))
        assertTrue(html.contains("1 crashed"))
    }

    @Test
    fun `no history line when the cache holds a single build`(@TempDir dir: Path) {
        place(dir, "builds/${cfg}__992152358/log.txt", fixtureText("arena-rename-final.txt"))
        place(dir, "builds/${cfg}__992152358/meta.json", meta(992152358, "SUCCESS", "2026-07-02T12:00:00Z"))

        val html = HtmlRenderer.render(buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z"))
        assertFalse(html.contains("run history"), "n=1 is not a history")
    }
}
