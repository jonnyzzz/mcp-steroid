/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiAgentDriver
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.titleCase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1

/**
 * Integration test: debugger demo for Rider (.NET).
 *
 * Runs AI agents inside a Docker container with Rider + MCP Steroid plugin.
 * The agent is asked to debug LeaderboardTests.cs and find the OrderByDescending bug
 * in Player.cs — the C# equivalent of the Kotlin sortedByDescending bug pattern.
 *
 * The bug: `players.OrderByDescending(p => p.Score)` returns a new ordered sequence
 * but the return value is ignored, so the original unsorted list is returned.
 *
 * Each test creates its own container for full isolation.
 */
class RiderDebuggerTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude debugs dotnet test in Rider via debugger`() = runRiderDebugDemo(AiAgentDriver::claude)

    private fun runRiderDebugDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "Rider Debug with ${agentName.name.titleCase()}",
            distribution = IdeDistribution.Latest(IdeProduct.Rider),
        )).waitForProjectReady(projectJdkVersion = null)
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep(text = "Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug a failing .NET test using Rider via MCP Steroid")
            appendLine()
            appendLine("A test file is already open in the editor. Use the debugger to find why it fails.")
            appendLine("You MUST use the debugger — do NOT just read source code and guess.")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` for the debugger API workflow.")
            appendLine()
            appendLine("Do NOT use screenshots or UI input tools.")
            appendLine()
            appendLine("## Debugger workflow")
            appendLine()
            appendLine("1. Set a breakpoint at the suspected buggy line in the implementation file")
            appendLine("2. Launch the debug test session (use `RiderUnitTestDebugContextAction`)")
            appendLine("3. Wait for the breakpoint to be hit")
            appendLine("4. Evaluate variables at the breakpoint to see variable values")
            appendLine("5. **DO NOT step over** — the variable values at the breakpoint ARE the evidence")
            appendLine("   If `players` is unsorted at the breakpoint (Ada before Linus), that proves the bug.")
            appendLine("6. Stop the debug session")
            appendLine("7. Write your bug report with the markers below")
            appendLine()
            appendLine("Print these markers in your final answer:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <explain what the bug is and why it causes the test to fail>")
            appendLine("DEBUGGER_EVIDENCE: <variable values observed AT the breakpoint proving the bug>")
        }

        console.writeStep(text = "Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "Rider debugger demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(bugLine.contains("OrderByDescending", ignoreCase = true)) {
            "BUG_LINE must mention OrderByDescending.\nActual: $bugLine\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        result.assertOutputContains("OrderByDescending", message = "agent must mention OrderByDescending")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        assertRootCauseQuality(
            combined, output,
            firstAspectPatterns = listOf(
                "ignor", "unused", "discard", "return value", "not assigned", "not assigned back",
                "not used", "isn't assigned", "not stored", "not captured", "thrown away", "result is lost",
            ),
            secondAspectPatterns = listOf(
                "new sequence", "new collection", "returns new", "does not modify", "doesn't modify",
                "not in place", "original list", "new sorted", "sorted copy", "new ordered",
                "returns a new", "LINQ",
            ),
            explanation = "ROOT_CAUSE must explain that OrderByDescending returns a new sequence and its return value is ignored."
        )
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the OrderByDescending bug via Rider debugging")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully debugged the failing .NET test in Rider")
    }

    private fun assertDebuggerEvidence(combined: String, console: ConsoleDriver) {
        val suspensionPatterns = listOf(
            Regex("""(?i)suspended at:\s*\S+:\d+"""),
            Regex("""(?i)breakpoint hit.*:\d+"""),
            Regex("""(?i)stopped at.*:\d+"""),
        )
        val hasSuspension = suspensionPatterns.any { it.containsMatchIn(combined) }
        if (hasSuspension) {
            console.writeSuccess("Found breakpoint suspension evidence")
        }

        val hasBeforeValue = combined.contains("BEFORE_VALUE:", ignoreCase = true)
        val hasAfterValue = combined.contains("AFTER_VALUE:", ignoreCase = true)
        if (hasBeforeValue) console.writeSuccess("Found BEFORE_VALUE evidence")
        if (hasAfterValue) console.writeSuccess("Found AFTER_VALUE evidence")

        val evaluationPatterns = listOf(
            Regex("""(?i)\b(players|result|scores|leaderboard)\s*=\s*\S+"""),
            Regex("""(?i)evaluating:"""),
            Regex("""(?i)result:\s*\S+"""),
            Regex("""(?i)after step:"""),
            Regex("""(?i)value:\s*\S+"""),
            Regex("""(?i)\b(players|result|scores)\.(Count|Length|Name|Score|First)"""),
        )
        val hasEvalEvidence = evaluationPatterns.any { it.containsMatchIn(combined) }
        if (hasEvalEvidence) {
            console.writeSuccess("Found variable evaluation evidence")
        }

        check(hasSuspension) {
            "Agent must show evidence of debugger suspension.\n" +
                    "Expected patterns:\n" +
                    "  - 'Debugger suspended at: <file>:<line>'\n" +
                    "  - 'Breakpoint hit at: <file>:<line>'\n" +
                    "  - 'Stopped at: <file>:<line>'\n" +
                    "This proves the debugger actually hit a breakpoint.\n" +
                    "Combined output length: ${combined.length} chars"
        }
        check(hasBeforeValue || hasAfterValue || hasEvalEvidence) {
            "Agent must show evidence of debugger expression evaluation.\n" +
                    "Expected evidence:\n" +
                    "  - BEFORE_VALUE/AFTER_VALUE markers\n" +
                    "  - Variable evaluation output (e.g., 'variable = value')\n" +
                    "  - Expression evaluation output (e.g., 'evaluating: expression', 'Result: value')\n" +
                    "  - Variable property access (e.g., 'players.Count', 'result.Name')\n" +
                    "This proves the agent evaluated expressions during debugging, not just read source code.\n" +
                    "Combined output length: ${combined.length} chars"
        }
    }

}
