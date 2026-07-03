package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests over the CI collector layout, built from VERBATIM fixtures of the three real
 * KeycloakRename_Claude builds of 2026-07-02 plus a DPAIA timeout build — the case where the rendered
 * dashboard showed a nonsense comparison because one agent leg had failed:
 *
 *  - build 992109227: both legs CRASHED (agent CLI exit 1 — an org-level API 429; the mcp leg died at
 *    2s with 0/0 tokens),
 *  - build 992152358 (latest): mcp leg clean (exit 0, claimed fix), none leg crashed mid-run (exit 1),
 *  - build 986869666 (microshop-18): both legs TIMED OUT (exit -1 at the full 900s budget) — a
 *    legitimate "no fix within budget", not a crash.
 *
 * Two rules under test:
 *  1. **Latest build wins** — the incremental fetch cache keeps superseded builds' folders; runs from an
 *     older build of the same (scenario, agent, mode) must never leak fields into (or shadow) the
 *     latest build's runs.
 *  2. **A crashed leg is not a comparison** — exit 0 is a clean attempt, exit -1 is the harness timeout
 *     (budget exhausted ⇒ genuine "no fix"), any OTHER exit code means the agent tooling itself failed
 *     ⇒ the pair verdict is INCOMPLETE, never MCP_HELPED/MCP_HURT/NEUTRAL.
 */
class FailedLegAndStaleBuildTest {
    private fun fixtureText(name: String): String =
        requireNotNull(javaClass.classLoader.getResourceAsStream("fixtures/$name")) { "missing $name" }
            .bufferedReader().readText()

    private fun place(dir: Path, rel: String, content: String) {
        val f = dir.resolve(rel).toFile()
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    private fun meta(configId: String, buildId: Long, status: String) =
        """{"buildConfigId":"$configId","buildId":$buildId,"scenario":"KeycloakRename","agent":"claude","status":"$status"}"""

    private val cfg = "mcp_steroid_IntegrationTests_KeycloakRename_Claude"

    private fun placeCrashedBuild(dir: Path) {
        place(dir, "builds/${cfg}__992109227/log.txt", fixtureText("arena-rename-crash429.txt"))
        place(dir, "builds/${cfg}__992109227/meta.json", meta(cfg, 992109227, "FAILURE"))
    }

    private fun placeFinalBuild(dir: Path) {
        place(dir, "builds/${cfg}__992152358/log.txt", fixtureText("arena-rename-final.txt"))
        place(dir, "builds/${cfg}__992152358/meta.json", meta(cfg, 992152358, "SUCCESS"))
    }

    @Test
    fun `a superseded build's runs never leak into the latest build's comparison`(@TempDir dir: Path) {
        placeCrashedBuild(dir)  // older — the 2s/0-token 429 corpse that shadowed production data
        placeFinalBuild(dir)    // latest — mcp leg is a clean successful run

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val cmp = report.comparisons.single { it.scenario == "keycloak__rename_safety" && it.agent == "claude" }

        // The with-MCP leg must be the LATEST build's clean run — not the older build's crash.
        val w = requireNotNull(cmp.withMcp)
        assertEquals(true, w.claimedFix, "latest build's mcp leg claimed the fix")
        assertEquals(0, w.exitCode)
        assertEquals(459_000L, w.agentDurationMs, "459s from build 992152358 — not 2s from 992109227")
        assertEquals(25_730L, w.outputTokens)

        // The without leg likewise comes from the latest build only.
        assertEquals(1_089_000L, cmp.without?.agentDurationMs, "1089s from build 992152358 — not 412s")
    }

    @Test
    fun `a crashed leg makes the pair INCOMPLETE instead of a win for the other side`(@TempDir dir: Path) {
        placeFinalBuild(dir) // mcp: exit 0 + claimed fix; none: exit 1 (agent died mid-run)

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val cmp = report.comparisons.single { it.scenario == "keycloak__rename_safety" && it.agent == "claude" }

        assertEquals(1, cmp.without?.exitCode, "precondition: the baseline leg crashed")
        // Before the fix this read MCP_HELPED — a win claimed against an agent that never finished.
        assertEquals(Verdict.INCOMPLETE, cmp.verdict)
    }

    @Test
    fun `both legs crashed reads INCOMPLETE not NEUTRAL`(@TempDir dir: Path) {
        placeCrashedBuild(dir) // both legs exit 1

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val cmp = report.comparisons.single { it.scenario == "keycloak__rename_safety" && it.agent == "claude" }
        assertEquals(Verdict.INCOMPLETE, cmp.verdict)
    }

    @Test
    fun `a timeout leg (exit -1 at full budget) is still a legitimate comparison`(@TempDir dir: Path) {
        val ms = "mcp_steroid_IntegrationTests_DpaiaArena_Microshop18_Claude"
        place(dir, "builds/${ms}__986869666/log.txt", fixtureText("arena-microshop-timeout.txt"))
        place(
            dir, "builds/${ms}__986869666/meta.json",
            """{"buildConfigId":"$ms","buildId":986869666,"scenario":"Microshop18","agent":"claude","status":"FAILURE"}"""
        )

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val cmp = report.comparisons.single { it.scenario == "dpaia__spring__boot__microshop-18" && it.agent == "claude" }

        assertEquals(-1, cmp.withMcp?.exitCode, "precondition: harness killed the leg at its budget")
        // Budget exhaustion is a genuine experimental outcome — both legs failed to fix ⇒ NEUTRAL.
        assertEquals(Verdict.NEUTRAL, cmp.verdict)
    }

    @Test
    fun `an ndjson stream without a terminal result event contributes nulls not zeros`(@TempDir dir: Path) {
        // Direct parser pin: the crashed baseline's real NDJSON (init + one assistant turn, the agent
        // died before emitting `result`) must yield NULL usage — never fabricated zeros.
        val m = NdjsonParser.parse(fixtureText("ndjson-crashed-no-result.ndjson"))
        assertNull(m.numTurns)
        assertNull(m.outputTokens)
        assertNull(m.costUsd)

        // Through the pipeline: the crashed leg still surfaces the identity the stream DOES carry,
        // and the ARENA-recorded facts (cost $0.80, turns 1 — verbatim emitter output) stand.
        placeFinalBuild(dir)
        place(dir, "builds/${cfg}__992152358/runs/none/agent-claude-code-1-raw.ndjson",
            fixtureText("ndjson-crashed-no-result.ndjson"))
        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val without = requireNotNull(
            report.comparisons.single { it.scenario == "keycloak__rename_safety" }.without
        )
        assertEquals("claude-opus-4-8", without.model)
        assertEquals(0.80209375, without.costUsd)
    }

    @Test
    fun `the dashboard marks a crashed leg as an agent failure`(@TempDir dir: Path) {
        placeFinalBuild(dir)

        val report = buildReport(dir.toFile(), "t", "2026-07-03T00:00:00Z")
        val html = HtmlRenderer.render(report)
        assertTrue(html.contains("agent failed (exit 1)"), "crashed leg must be visibly marked in the cell")
    }
}
