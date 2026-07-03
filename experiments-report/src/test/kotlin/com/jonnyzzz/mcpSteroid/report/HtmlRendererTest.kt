package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlRendererTest {
    private fun sampleReport(): Report {
        val runs = listOf(
            AgentRun("petclinic-27", "claude", McpMode.WITH, claimedFix = true, agentDurationMs = 521_000, costUsd = 3.21, buildSuccess = false, testsRun = 96, testsFail = 0, usedMcp = true, execCodeCalls = 1, summary = "Created REST CRUD",
                model = "claude-opus-4-6", agentVersion = "2.1.119", contextWindow = 200_000, maxOutputTokens = 64_000, inputTokens = 23, outputTokens = 21_064,
                toolCalls = mapOf("Read" to 13, "Edit" to 2, "mcp__mcp-steroid__steroid_execute_code" to 1)),
            AgentRun("petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true, agentDurationMs = 711_000, buildSuccess = true, testsRun = 94, testsFail = 0, usedMcp = false,
                model = "claude-opus-4-6", agentVersion = "2.1.119", contextWindow = 200_000, inputTokens = 50, outputTokens = 30_000,
                toolCalls = mapOf("Read" to 25, "Edit" to 6, "Bash" to 9)),
            AgentRun("train-ticket-1", "codex", McpMode.WITH, claimedFix = true, agentDurationMs = 100_000),
            AgentRun("train-ticket-1", "codex", McpMode.WITHOUT, claimedFix = false, agentDurationMs = 120_000),
        )
        return Report("MCP Steroid — Experiments", "2026-06-26T20:00:00Z", Aggregator.compare(runs), runs)
    }

    @Test
    fun `renders a self-contained html dashboard with the primary comparison`() {
        val html = HtmlRenderer.render(sampleReport())

        assertTrue(html.startsWith("<!DOCTYPE html>"), "is an html document")
        assertTrue(html.contains("MCP Steroid — Experiments"), "has the title")
        // both scenarios and agents appear
        assertTrue(html.contains("petclinic-27"))
        assertTrue(html.contains("train-ticket-1"))
        assertTrue(html.contains("claude"))
        assertTrue(html.contains("codex"))
        // primary axis is with vs without MCP
        assertTrue(html.contains("with MCP"))
        assertTrue(html.contains("without MCP"))
        // a verdict the heuristic produced (codex train-ticket: with fixed, without not)
        assertTrue(html.contains("MCP helped"))
        // secondary section exists
        assertTrue(html.contains("Top problems"))
        // run detail carried through
        assertTrue(html.contains("Created REST CRUD"))
        // self-contained: no external stylesheet/script
        assertFalse(html.contains("<link"), "no external stylesheet")
        assertFalse(html.contains("src=\"http"), "no external script src")
    }

    @Test
    fun `states clearly that compared durations exclude the IDE preparation phase`() {
        val html = HtmlRenderer.render(sampleReport())
        assertTrue(html.contains("IDE preparation"), "must disclose the IDE-prep cutoff")
        assertTrue(html.contains("agent execution time"), "must say the metric is agent execution time")
        assertTrue(html.contains("Δ agent time"), "delta column is labelled as agent time, not wall clock")
    }

    @Test
    fun `shows model, agent version, token budget, tokens and a tool-call diff`() {
        val html = HtmlRenderer.render(sampleReport())
        // model + agent version + token budget from the agent output
        assertTrue(html.contains("claude-opus-4-6"), "model name shown")
        assertTrue(html.contains("2.1.119"), "agent version shown")
        assertTrue(html.contains("200,000") || html.contains("200000") || html.contains("200K"), "token budget shown")
        // tokens spent
        assertTrue(html.contains("tokens"), "token usage labelled")
        // tool-call diff: a tool present in both modes, plus the delta
        assertTrue(html.contains("Read"), "tool name shown")
        assertTrue(html.contains("tool calls", ignoreCase = true) || html.contains("Tool calls"), "tool-call section present")
    }

    @Test
    fun `renders an overview graph (inline svg) computed from the verdict counts`() {
        val html = HtmlRenderer.render(sampleReport())
        assertTrue(html.contains("<h2>Overview</h2>"), "has an Overview section")
        assertTrue(html.contains("<svg"), "draws an inline SVG chart")
        // the chart is built from the same verdict tallies as the cards (codex: 1 helped here)
        assertTrue(html.contains("class=\"seg helped\"") || html.contains("seg-helped"), "has a helped segment")
        // self-contained: SVG is inline, not an external image
        assertFalse(html.contains("<img"), "no external image")
    }

    @Test
    fun `is deterministic — identical input renders byte-for-byte identical output`() {
        val report = sampleReport()
        assertEquals(HtmlRenderer.render(report), HtmlRenderer.render(report))
    }

    @Test
    fun `discloses that the report is computed from data, not authored by an agent`() {
        val html = HtmlRenderer.render(sampleReport())
        assertTrue(html.contains("computed") && html.contains("from"), "states it is computed from data")
        // the only free text is the agent's own run summary, shown verbatim and labelled as such
        assertTrue(html.contains("summary:"))
    }

    @Test
    fun `shows a weighted history line under a leg with repeat runs and none for a single run`() {
        val runs = listOf(
            AgentRun("petclinic-27", "claude", McpMode.WITH, claimedFix = true, agentDurationMs = 312_000),
            AgentRun("petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true, agentDurationMs = 400_000),
        )
        val histories = listOf(
            // 9 attempts for the WITH leg spread over ~4 months and 3 model generations
            RunHistory(
                scenario = "petclinic-27", agent = "claude", mode = McpMode.WITH,
                runs = 9, crashed = 1, weightedSuccessPct = 82,
                weightedMedianDurationMs = 298_000, weightedMedianCostUsd = 1.02,
                spanDays = 120.0,
                models = listOf("claude-opus-4-6", "claude-opus-4-7", "claude-opus-4-8"),
            ),
            // single attempt for the WITHOUT leg — no history line
            RunHistory(
                scenario = "petclinic-27", agent = "claude", mode = McpMode.WITHOUT,
                runs = 1, crashed = 0, weightedSuccessPct = 100,
                weightedMedianDurationMs = 400_000, weightedMedianCostUsd = 2.0,
                spanDays = null, models = listOf("claude-opus-4-8"),
            ),
        )
        val html = HtmlRenderer.render(
            Report("t", "2026-07-03T00:00:00Z", Aggregator.compare(runs), runs, histories = histories)
        )
        assertTrue(html.contains("run history"), "repeat-run leg gets a history line")
        assertTrue(html.contains("9 runs"))
        assertTrue(html.contains("over 4 months"), "age span disclosed")
        assertTrue(html.contains("82% ✓ (weighted)"), "recency-weighted success rate, labelled as such")
        assertTrue(html.contains("median 4m 58s"))
        assertTrue(html.contains("$1.02"))
        assertTrue(html.contains("1 crashed"))
        assertTrue(html.contains("3 models"), "model drift disclosed compactly")
        assertTrue(html.contains("claude-opus-4-6 → claude-opus-4-8"), "oldest → newest model")
        // the n=1 leg renders NO history line: its numbers must not read as history
        assertFalse(html.contains("1 runs"), "a single run is not a history")
    }

    @Test
    fun `omits crash count and model note when there is nothing to disclose`() {
        val runs = listOf(
            AgentRun("x", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("x", "claude", McpMode.WITHOUT, claimedFix = true),
        )
        val histories = listOf(
            RunHistory(
                scenario = "x", agent = "claude", mode = McpMode.WITH,
                runs = 3, crashed = 0, weightedSuccessPct = 100,
                weightedMedianDurationMs = null, weightedMedianCostUsd = null,
                spanDays = 2.0, models = listOf("claude-opus-4-8"),
            ),
        )
        val html = HtmlRenderer.render(
            Report("t", "now", Aggregator.compare(runs), runs, histories = histories)
        )
        assertTrue(html.contains("run history"))
        assertFalse(html.contains("crashed"), "no crash note when crashed == 0")
        assertFalse(html.contains("models"), "no drift note for a single model")
    }

    @Test
    fun `no history line at all when the report carries no histories`() {
        val html = HtmlRenderer.render(sampleReport())
        assertFalse(html.contains("run history"))
    }

    @Test
    fun `escapes html special characters in agent text`() {
        val runs = listOf(
            AgentRun("x", "claude", McpMode.WITH, claimedFix = true, summary = "<script>alert(1)</script> & co"),
            AgentRun("x", "claude", McpMode.WITHOUT, claimedFix = true),
        )
        val html = HtmlRenderer.render(Report("t", "now", Aggregator.compare(runs), runs))
        assertFalse(html.contains("<script>alert(1)</script>"), "raw script must not leak")
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&amp; co"))
    }
}
