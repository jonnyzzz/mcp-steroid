package com.jonnyzzz.mcpSteroid.report

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure tests for [scenarioBucket] + the bucketed rendering: the dashboard groups scenarios into
 * dedicated buckets so the IDE-power experiments (Keycloak semantic tasks, SSR) are highlighted and
 * the DPAIA fix-the-build arena sits under its own heading instead of interleaving with everything.
 */
class ScenarioBucketTest {

    @Test
    fun `scenario ids map to their buckets by prefix`() {
        assertEquals(ScenarioBucket.IDE_SEMANTIC, scenarioBucket("keycloak__rename_safety"))
        assertEquals(ScenarioBucket.IDE_SEMANTIC, scenarioBucket("keycloak__change_signature"))
        assertEquals(ScenarioBucket.IDE_SEMANTIC, scenarioBucket("keycloak__call_hierarchy"))
        assertEquals(ScenarioBucket.IDE_SEMANTIC, scenarioBucket("youtrackdb__structural_search"))
        assertEquals(ScenarioBucket.DEBUGGER, scenarioBucket("debugger__sortedByDescending"))
        assertEquals(ScenarioBucket.DPAIA, scenarioBucket("dpaia__spring__petclinic-27"))
        assertEquals(ScenarioBucket.OTHER, scenarioBucket("something__new"))
    }

    @Test
    fun `buckets render as section headers in priority order with rows under the right one`() {
        val runs = listOf(
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true),
            AgentRun("keycloak__rename_safety", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("keycloak__rename_safety", "claude", McpMode.WITHOUT, claimedFix = false),
            AgentRun("debugger__sortedByDescending", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("debugger__sortedByDescending", "claude", McpMode.WITHOUT, claimedFix = true),
        )
        val html = HtmlRenderer.render(
            Report(title = "t", generatedAt = "now", comparisons = Aggregator.compare(runs), allRuns = runs)
        )

        // All three bucket headings present…
        val ide = html.indexOf(ScenarioBucket.IDE_SEMANTIC.title)
        val dbg = html.indexOf(ScenarioBucket.DEBUGGER.title)
        val dpaia = html.indexOf(ScenarioBucket.DPAIA.title)
        assertTrue(ide >= 0 && dbg >= 0 && dpaia >= 0, "all bucket headings rendered")
        // …in priority order: IDE power first, debugger next, DPAIA in its dedicated bucket after.
        assertTrue(ide < dbg && dbg < dpaia, "bucket order: IDE < debugger < DPAIA")
        // Rows land under their own bucket heading.
        assertTrue(html.indexOf("keycloak__rename_safety") in ide..dbg, "keycloak row inside IDE bucket")
        assertTrue(html.indexOf("dpaia__spring__petclinic-27") > dpaia, "dpaia row inside DPAIA bucket")
    }

    @Test
    fun `an empty bucket renders no heading`() {
        val runs = listOf(
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITH, claimedFix = true),
            AgentRun("dpaia__spring__petclinic-27", "claude", McpMode.WITHOUT, claimedFix = true),
        )
        val html = HtmlRenderer.render(
            Report(title = "t", generatedAt = "now", comparisons = Aggregator.compare(runs), allRuns = runs)
        )
        assertTrue(!html.contains(ScenarioBucket.IDE_SEMANTIC.title), "no IDE heading without IDE rows")
        assertTrue(html.contains(ScenarioBucket.DPAIA.title))
    }
}
