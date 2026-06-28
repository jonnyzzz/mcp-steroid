package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AggregatorTest {
    @Test
    fun `verdict uses the objective build outcome over the agent's self-claim`() {
        // Real Petclinic27 shape: BOTH agents claim a fix, but only the with-MCP build objectively
        // succeeded (80 tests, BUILD SUCCESS); without-MCP claimed success yet BUILD FAILURE (2 tests).
        // The verdict must follow the objective build/test outcome, not the optimistic claimedFix.
        val runs = listOf(
            AgentRun("petclinic-27", "claude", McpMode.WITH, claimedFix = true, buildSuccess = true, testsFail = 0, agentDurationMs = 436_000, costUsd = 3.21),
            AgentRun("petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true, buildSuccess = false, testsFail = 0, agentDurationMs = 524_000, costUsd = 4.10),
            // Fallback case: no build outcome reported → fall back to claimedFix.
            AgentRun("train-ticket-1", "codex", McpMode.WITH, claimedFix = true, agentDurationMs = 100_000),
            AgentRun("train-ticket-1", "codex", McpMode.WITHOUT, claimedFix = false, agentDurationMs = 120_000),
        )

        val comps = Aggregator.compare(runs)
        assertEquals(2, comps.size)

        val pet = comps.single { it.scenario == "petclinic-27" && it.agent == "claude" }
        assertEquals(Verdict.MCP_HELPED, pet.verdict, "with built, without failed -> MCP helped")
        assertEquals(-88_000L, pet.durationDeltaMs, "mcp run was faster by 88s")
        assertEquals(-0.89, pet.costDeltaUsd!!, 1e-9)

        val tt = comps.single { it.scenario == "train-ticket-1" && it.agent == "codex" }
        assertEquals(Verdict.MCP_HELPED, tt.verdict, "no build outcome -> falls back to claimedFix")
        assertEquals(-20_000L, tt.durationDeltaMs)
    }

    @Test
    fun `both builds succeeding is neutral`() {
        val runs = listOf(
            AgentRun("x", "claude", McpMode.WITH, buildSuccess = true, testsFail = 0, claimedFix = true),
            AgentRun("x", "claude", McpMode.WITHOUT, buildSuccess = true, testsFail = 0, claimedFix = true),
        )
        assertEquals(Verdict.NEUTRAL, Aggregator.compare(runs).single().verdict)
    }

    @Test
    fun `a build that fails its tests is not a success even if claimed`() {
        val runs = listOf(
            AgentRun("y", "claude", McpMode.WITH, buildSuccess = true, testsFail = 3, claimedFix = true),
            AgentRun("y", "claude", McpMode.WITHOUT, buildSuccess = true, testsFail = 0, claimedFix = true),
        )
        assertEquals(Verdict.MCP_HURT, Aggregator.compare(runs).single().verdict)
    }

    @Test
    fun `marks a comparison incomplete when a mode is missing`() {
        val runs = listOf(AgentRun("solo", "claude", McpMode.WITH, claimedFix = true))
        val comp = Aggregator.compare(runs).single()
        assertEquals(Verdict.INCOMPLETE, comp.verdict)
        assertEquals(null, comp.durationDeltaMs)
    }
}
