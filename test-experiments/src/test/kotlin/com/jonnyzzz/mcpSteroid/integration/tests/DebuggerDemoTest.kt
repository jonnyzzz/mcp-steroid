/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiAgentDriver
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleDriver
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
 * Integration test: debugger demo from ai-tests/07-debugger.md.
 *
 * Runs AI agents inside a Docker container with IntelliJ IDEA + MCP Steroid plugin.
 * The agent is asked to debug DemoByJonnyzzz.kt and find the sortedByDescending bug.
 *
 * The prompt intentionally avoids giving the agent any IntelliJ API code. Instead,
 * it directs the agent to read MCP debugger resources (mcp-steroid://debugger/...)
 * which contain complete, copy-paste-ready code for each step. This tests whether
 * agents can discover and use MCP resources independently.
 *
 * Each test creates its own IdeContainer for full isolation.
 * The container is kept alive after the test for debugging (removed on next run).
 * Video is always recorded and mounted to the host for live preview.
 */
class DebuggerDemoTest {
    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude finds sortedByDescending bug via debugger`()  = runDebuggerDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `codex finds sortedByDescending bug via debugger`() = runDebuggerDemo(AiAgentDriver::codex)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `gemini finds sortedByDescending bug via debugger`() = runDebuggerDemo(AiAgentDriver::gemini)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `test debugger finds null-default bug with Claude`() = runNullDefaultDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `test debugger finds off-by-one bug with Claude`() = runOffByOneDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `test debugger finds string-format bug with Claude`() = runStringFormatDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude debugs failing unit test via debugger`() = runUnitTestDebugDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `codex debugs failing unit test via debugger`() = runUnitTestDebugDemo(AiAgentDriver::codex)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude debugs JonnyzzzDebugTest via debugger`() = runJonnyzzzDebugDemo(AiAgentDriver::claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `codex debugs JonnyzzzDebugTest via debugger`() = runJonnyzzzDebugDemo(AiAgentDriver::codex)

    private fun runDebuggerDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(
            lifetime, IntelliJContainerOpts(
                consoleTitle = "Debugger with ${agentName.name.titleCase()}",
            )
        ).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoByJonnyzzz.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate the bug.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoByJonnyzzz.kt` in the project and read it")
            appendLine("2. Use the debugger to set a breakpoint, run the program, and evaluate variables")
            appendLine("3. Step through the code and observe how variables change before and after key lines")
            appendLine("4. Identify the bug based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain what the bug is and why, mentioning both that sortedByDescending returns a new list AND that the return value is ignored/unused>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the issue>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints, evaluate variables, step through code)")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        // Use rawOutput for evidence checks: Claude's stream-json mode puts
        // execution IDs in NDJSON tool_result events, not in the final extracted text.
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep( "Validating agent output")

        // If CLI timed out but the agent already emitted required markers, keep validating the output.
        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "debugger demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must show evidence of MCP Steroid execute_code usage
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(bugLine.contains("sortedByDescending", ignoreCase = true)) {
            "BUG_LINE must mention sortedByDescending.\nOutput:\n$combined"
        }
        val hasExactBugStatement = bugLine.contains("players.sortedByDescending", ignoreCase = true) &&
                bugLine.contains("it.score", ignoreCase = true)
        val hasSortedLineEvidence = Regex("""(?im)sortedByDescending line\s*\(1-based\)\s*:\s*7""")
            .containsMatchIn(combined)
        check(hasExactBugStatement || hasSortedLineEvidence) {
            "BUG_LINE must identify the exact buggy statement, or execution logs must show line-number evidence " +
                    "for the sortedByDescending line.\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        // Agent must mention sortedByDescending in its analysis
        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        // Agent must identify the root cause: sortedByDescending returns a new list
        // but the return value is ignored
        console.writeInfo("Checking: ROOT_CAUSE marker")
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE: $rootCause")

        console.writeInfo("Checking: BUG_FOUND marker")
        val bugFound = findMarkerValue(output, "BUG_FOUND", "Bug found")
        val hasExplicitYes = bugFound?.equals("yes", ignoreCase = true) == true
        val inferredYes = bugFound == null && bugLine.isNotBlank() && rootCause.isNotBlank()
        check(hasExplicitYes || inferredYes) {
            "Agent did not confirm bug detection with 'BUG_FOUND: yes' and no valid fallback markers were found.\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_FOUND: ${bugFound ?: "(inferred)"}")

        console.writeInfo("Checking: ROOT_CAUSE quality")
        val ignoredReturnPatterns = listOf(
            "ignor", "unused", "discard", "return value", "not assigned", "not assigned back", "not used",
            "isn't assigned", "ignored/not assigned", "not stored", "not captured", "thrown away", "result is lost",
        )
        val returnsNewListPatterns = listOf(
            "new list", "returns new", "does not modify", "doesn't modify",
            "not in place", "immutable", "original list", "original unsorted list",
            "new sorted list", "sorted copy",
        )

        val mentionsIgnoredReturn = ignoredReturnPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        val mentionsNewListBehavior = returnsNewListPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true)
        }
        check(mentionsIgnoredReturn && mentionsNewListBehavior) {
            "ROOT_CAUSE must explain that sortedByDescending returns a new list and its return value is ignored.\n" +
                    "Expected ignored patterns: $ignoredReturnPatterns\n" +
                    "Expected new-list patterns: $returnsNewListPatterns\nOutput:\n$combined"
        }
        check(!rootCause.contains("it.first", ignoreCase = true)) {
            "ROOT_CAUSE should not claim a selector bug (`it.first` vs `it.score`).\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE quality validated")

        // Validate debugger evidence: the agent must have actually used the debugger,
        // not just read source code and guessed the answer.
        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the sortedByDescending bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully identified the sortedByDescending bug")
    }

    /**
     * Validates that the agent actually used the debugger (not just read source code).
     * Checks for:
     * 1. Suspension evidence -- the debugger hit a breakpoint ("suspended at:")
     * 2. Evaluation evidence -- the agent evaluated expressions at a breakpoint
     *    (BEFORE_VALUE or AFTER_VALUE markers, or evaluateExpression / variable evaluation output)
     */
    private fun assertDebuggerEvidence(combined: String, console: ConsoleDriver) {
        // Check for breakpoint suspension evidence (case-insensitive to match both
        // "Suspended at:" from custom code and "Debugger suspended at:" from MCP resources)
        val suspensionPatterns = listOf(
            Regex("""(?i)suspended at:\s*\S+:\d+"""),
            Regex("""(?i)breakpoint hit.*:\d+"""),
            Regex("""(?i)stopped at.*:\d+"""),
        )
        val hasSuspension = suspensionPatterns.any { it.containsMatchIn(combined) }
        if (hasSuspension) {
            console.writeSuccess("Found breakpoint suspension evidence")
        }

        // Check for debugger evaluation evidence (BEFORE_VALUE / AFTER_VALUE from step+eval)
        val hasBeforeValue = combined.contains("BEFORE_VALUE:", ignoreCase = true)
        val hasAfterValue = combined.contains("AFTER_VALUE:", ignoreCase = true)
        if (hasBeforeValue) console.writeSuccess("Found BEFORE_VALUE evidence")
        if (hasAfterValue) console.writeSuccess("Found AFTER_VALUE evidence")

        // Broader evaluation evidence: any expression evaluation output from the debugger
        // Look for common patterns:
        // - "variable = value" patterns
        // - "evaluating: expression" patterns
        // - "Result: value" patterns
        // - Variable names followed by values
        val evaluationPatterns = listOf(
            Regex("""(?i)\b(players|address|total|scores|registry|formatted|userId|items)\s*=\s*\S+"""),
            Regex("""(?i)evaluating:"""),
            Regex("""(?i)result:\s*\S+"""),
            Regex("""(?i)after step:"""),
            Regex("""(?i)value:\s*\S+"""),
            Regex("""(?i)\b(players|address|total|scores|registry|formatted|userId|items)\.toString\(\)"""),
            Regex("""(?i)\b(players|address|total|scores|registry|formatted|userId|items)\.(size|city|street|zip|displayName|email|first|last|name|status)"""),
        )
        val hasEvalEvidence = evaluationPatterns.any { it.containsMatchIn(combined) }
        if (hasEvalEvidence) {
            console.writeSuccess("Found variable evaluation evidence")
        }

        // Must have suspension evidence + at least some evaluation evidence
        check(hasSuspension) {
            "Agent must show evidence of debugger suspension.\n" +
                    "Expected patterns:\n" +
                    "  - 'Debugger suspended at: <file>:<line>'\n" +
                    "  - 'Breakpoint hit at: <file>:<line>'\n" +
                    "  - 'Stopped at: <file>:<line>'\n" +
                    "This proves the debugger actually hit a breakpoint.\n" +
                    "If you see 'Suspended at:' in the output but this check fails, verify the pattern matches.\n" +
                    "Combined output length: ${combined.length} chars"
        }
        check(hasBeforeValue || hasAfterValue || hasEvalEvidence) {
            "Agent must show evidence of debugger expression evaluation.\n" +
                    "Expected evidence:\n" +
                    "  - BEFORE_VALUE/AFTER_VALUE markers\n" +
                    "  - Variable evaluation output (e.g., 'variable = value')\n" +
                    "  - Expression evaluation output (e.g., 'evaluating: expression', 'Result: value')\n" +
                    "  - Variable property access (e.g., 'address.city', 'players.size')\n" +
                    "This proves the agent evaluated expressions during debugging, not just read source code.\n" +
                    "Combined output length: ${combined.length} chars"
        }
    }

    /**
     * Asks the agent to find [DemoByJonnyzzzTest] (a failing JUnit test in the test-project),
     * run it through the IntelliJ debugger, set a breakpoint inside [leaderboard()], and
     * identify why the assertion fails.
     *
     * The bug: [leaderboard] calls [sortedByDescending] but ignores the return value,
     * so the original unsorted list is returned. The agent must discover this via debugger
     * evidence rather than by just reading the source code.
     */
    private fun runUnitTestDebugDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(
            lifetime, IntelliJContainerOpts(
                consoleTitle = "Unit Test Debug with ${agentName.name.titleCase()}",
            )
        ).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug a failing JUnit test to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate why the test fails.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoByJonnyzzzTest.kt` in the project — it is a JUnit test class")
            appendLine("2. Find the corresponding `DemoByJonnyzzz.kt` source file that the test is exercising")
            appendLine("3. Use the debugger to run the failing test and set a breakpoint inside the `leaderboard()` function")
            appendLine("4. Step through the code and observe variable values before and after the sortedByDescending call")
            appendLine("5. Identify why the test assertion fails based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("IMPORTANT: You must debug the JUnit test (DemoByJonnyzzzTest), not run the main() function.")
            appendLine("Use `RunManager` + `JUnitConfiguration` or `steroid_execute_code` to trigger test debugging.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain that sortedByDescending returns a new list AND that the return value is ignored/not assigned back to players>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the issue, observed during test execution>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints inside the function under test, evaluate variables)")
            appendLine("- The debugging must occur in the context of running DemoByJonnyzzzTest, not main()")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "unit test debug demo")
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
        check(bugLine.contains("sortedByDescending", ignoreCase = true)) {
            "BUG_LINE must mention sortedByDescending.\nActual: $bugLine\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        result.assertOutputContains("sortedByDescending", message = "agent must mention sortedByDescending")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }

        val ignoredReturnPatterns = listOf(
            "ignor", "unused", "discard", "return value", "not assigned", "not assigned back",
            "not used", "isn't assigned", "not stored", "not captured", "thrown away", "result is lost",
        )
        val returnsNewListPatterns = listOf(
            "new list", "returns new", "does not modify", "doesn't modify",
            "not in place", "immutable", "original list", "new sorted list", "sorted copy",
        )
        val mentionsIgnoredReturn = ignoredReturnPatterns.any { rootCause.contains(it, ignoreCase = true) }
        val mentionsNewList = returnsNewListPatterns.any { rootCause.contains(it, ignoreCase = true) }
        check(mentionsIgnoredReturn && mentionsNewList) {
            "ROOT_CAUSE must explain that sortedByDescending returns a new list and its return value is ignored.\n" +
                    "Expected ignored-return patterns: $ignoredReturnPatterns\n" +
                    "Expected new-list patterns: $returnsNewListPatterns\n" +
                    "Actual ROOT_CAUSE: $rootCause\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the sortedByDescending bug via unit test debugging")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully debugged the failing unit test")
    }

    /**
     * Asks the agent to find [JonnyzzzDebugTest] (a failing JUnit test in the test-project),
     * run it through the IntelliJ debugger, set a breakpoint inside [filterActive()], and
     * identify why the assertion fails.
     *
     * The bug: [filterActive] uses `!=` instead of `==` when filtering by status,
     * so it returns inactive items instead of active ones. The agent must discover
     * this via debugger evidence rather than by just reading the source code.
     */
    private fun runJonnyzzzDebugDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "JonnyzzzDebugTest with ${agentName.name.titleCase()}",
        )).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug a failing JUnit test to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger — do NOT just read source code and guess.")
            appendLine()
            appendLine("1. Find `JonnyzzzDebugTest.kt` in the project and read it")
            appendLine("2. Debug the failing JUnit test (not any main() function) with breakpoints")
            appendLine("3. Evaluate variable values at the breakpoint to find the bug")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` for debugger API patterns.")
            appendLine()
            appendLine("Do NOT use screenshots or UI input tools.")
            appendLine()
            appendLine("Print these markers in your final answer:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line containing the filter condition>")
            appendLine("ROOT_CAUSE: <what condition is wrong and what effect it has on the returned items>")
            appendLine("DEBUGGER_EVIDENCE: <items values and filter results observed during test execution>")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "JonnyzzzDebugTest demo")
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
        check(
            bugLine.contains("!=", ignoreCase = false) ||
            bugLine.contains("status", ignoreCase = true)
        ) {
            "BUG_LINE must mention the != condition or status field.\nActual: $bugLine\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        result.assertOutputContains("filterActive", message = "agent must mention filterActive")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        assertRootCauseQuality(
            combined, output,
            firstAspectPatterns = listOf(
                "!=", "not equal", "inverted", "wrong condition", "incorrect condition",
                "opposite", "negated", "reversed condition",
            ),
            secondAspectPatterns = listOf(
                "inactive", "wrong items", "returns inactive", "instead of active",
                "active items", "should be ==", "should be equals",
            ),
            explanation = "ROOT_CAUSE must explain that the filter uses != instead of == for status comparison, " +
                    "causing inactive items to be returned instead of active ones."
        )
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the inverted filter bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully debugged JonnyzzzDebugTest")
    }

    private fun runStringFormatDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "Debugger with ${agentName.name.titleCase()}",
        )).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoStringFormat.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate the bug.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoStringFormat.kt` in the project and read it")
            appendLine("2. Use the debugger to set a breakpoint, run the program, and evaluate variables")
            appendLine("3. Step through the code and observe how the address string is formatted")
            appendLine("4. Identify the bug based on debugger evidence (fields are in the wrong order)")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain that city and street fields are swapped in the string template>")
            appendLine("DEBUGGER_EVIDENCE: <variable values showing the Address object fields and the formatted result>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints, evaluate variables, step through code)")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        // If CLI timed out but the agent already emitted required markers, keep validating the output.
        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "debugger demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must show evidence of MCP Steroid execute_code usage
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(
            bugLine.contains("address.city", ignoreCase = true) &&
            bugLine.contains("address.street", ignoreCase = true)
        ) {
            "BUG_LINE must mention the string template with address.city and address.street.\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_LINE: $bugLine")

        // Agent must identify the root cause: city and street are swapped
        console.writeInfo("Checking: ROOT_CAUSE marker")
        val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
        check(rootCause != null) {
            "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE: $rootCause")

        console.writeInfo("Checking: BUG_FOUND marker")
        val bugFound = findMarkerValue(output, "BUG_FOUND", "Bug found")
        val hasExplicitYes = bugFound?.equals("yes", ignoreCase = true) == true
        val inferredYes = bugFound == null && bugLine.isNotBlank() && rootCause.isNotBlank()
        check(hasExplicitYes || inferredYes) {
            "Agent did not confirm bug detection with 'BUG_FOUND: yes' and no valid fallback markers were found.\nOutput:\n$combined"
        }
        console.writeSuccess("BUG_FOUND: ${bugFound ?: "(inferred)"}")

        console.writeInfo("Checking: ROOT_CAUSE quality")
        val swapPatterns = listOf(
            "swap", "reversed", "wrong order", "incorrect order",
            "city.*street", "street.*city", "mixed up", "backwards",
        )

        val mentionsSwap = swapPatterns.any { pattern ->
            rootCause.contains(pattern, ignoreCase = true) ||
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(rootCause)
        }
        check(mentionsSwap) {
            "ROOT_CAUSE must explain that city and street fields are swapped in the string template.\n" +
                    "Expected patterns: $swapPatterns\nOutput:\n$combined"
        }
        console.writeSuccess("ROOT_CAUSE quality validated")

        // Validate debugger evidence: the agent must have actually used the debugger,
        // not just read source code and guessed the answer.
        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the string-format bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully identified the string-format bug")
    }

    private fun runNullDefaultDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "Null-Default Bug with ${agentName.name.titleCase()}",
        )).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoNullDefault.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate the bug.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoNullDefault.kt` in the project and read it")
            appendLine("2. Use the debugger to set a breakpoint, run the program, and evaluate variables")
            appendLine("3. Step through the code and observe how variables change before and after key lines")
            appendLine("4. Identify the bug based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain that the code searches by displayName field instead of using userId as the map key, AND that this causes the lookup to always return the default/fallback value>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the lookup result>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints, evaluate variables, step through code)")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "null-default demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        assertBugFound(combined, output, "registry.values.firstOrNull")
        console.writeSuccess("BUG_LINE validated")

        result.assertOutputContains("displayName", message = "agent must mention displayName")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        assertRootCauseQuality(
            combined, output,
            firstAspectPatterns = listOf(
                "displayName", "by displayName", "displayName field", "displayName instead",
                "searches by displayName", "using displayName", "matches displayName",
            ),
            secondAspectPatterns = listOf(
                "map key", "userId as key", "key lookup", "as the key",
                "default", "fallback", "guest", "always returns", "never matches",
            ),
            explanation = "ROOT_CAUSE must explain that the code searches by displayName field instead of using userId as the map key, " +
                    "AND that this causes the lookup to always return the default/fallback value."
        )
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the null-default bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully identified the null-default bug")
    }

    private fun runOffByOneDemo(agentName: KProperty1<AiAgentDriver, AiAgentSession>) {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "Off-by-One Bug with ${agentName.name.titleCase()}",
        )).waitForProjectReady()
        val console = session.console

        val agent = session.aiAgents.run { agentName(this) }
        console.writeStep("Building prompt for $agentName")

        val prompt = buildString {
            appendLine("# Task: Debug DemoOffByOne.kt to find the bug")
            appendLine()
            appendLine("You MUST use the IntelliJ debugger to investigate the bug.")
            appendLine("Do NOT just read source code and guess -- the test validates debugger evidence.")
            appendLine()
            appendLine("## Instructions")
            appendLine()
            appendLine("1. Find `DemoOffByOne.kt` in the project and read it")
            appendLine("2. Use the debugger to set a breakpoint, run the program, and evaluate variables")
            appendLine("3. Step through the code and observe how variables change before and after key lines")
            appendLine("4. Identify the bug based on debugger evidence")
            appendLine()
            appendLine("Read `mcp-steroid://prompt/debugger-skill` to learn how to use the debugger APIs.")
            appendLine("It links to individual resources with complete, copy-paste-ready code for each step.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("BUG_FOUND: yes")
            appendLine("BUG_LINE: <the exact buggy source line>")
            appendLine("ROOT_CAUSE: <must explain that the loop counter starts at 1 instead of 0, AND that this skips the first element of the list>")
            appendLine("DEBUGGER_EVIDENCE: <BEFORE and AFTER values showing the loop counter or accumulated sum>")
            appendLine()
            appendLine("Also print BEFORE_VALUE and AFTER_VALUE markers when evaluating variables")
            appendLine("before and after the suspected buggy line executes.")
            appendLine()
            appendLine("## Rules")
            appendLine()
            appendLine("- You MUST use the debugger (set breakpoints, evaluate variables, step through code)")
            appendLine("- Do NOT use screenshots or UI input tools")
            appendLine("- Read MCP debugger resources for API patterns -- do not invent API calls")
        }

        console.writeStep("Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep("Validating agent output")

        val hasFinalMarkers = hasAnyMarkerLine(output, "BUG_FOUND", "Bug found") &&
                hasAnyMarkerLine(output, "ROOT_CAUSE", "Root cause")
        if (result.exitCode != 0 && !hasFinalMarkers) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "off-by-one demo")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        console.writeInfo("Checking: BUG_LINE marker")
        assertBugFound(combined, output, "var i = 1")
        console.writeSuccess("BUG_LINE validated")

        result.assertOutputContains("index", message = "agent must mention index")

        console.writeInfo("Checking: ROOT_CAUSE marker")
        assertRootCauseQuality(
            combined, output,
            firstAspectPatterns = listOf(
                "starts at 1", "starting at 1", "index 1", "i = 1",
                "begins at 1", "initialized to 1", "starting index",
            ),
            secondAspectPatterns = listOf(
                "instead of 0", "should be 0", "skips first", "missing first",
                "first element", "element at index 0", "off-by-one",
            ),
            explanation = "ROOT_CAUSE must explain that the loop counter starts at 1 instead of 0, " +
                    "AND that this skips the first element of the list."
        )
        console.writeSuccess("ROOT_CAUSE quality validated")

        console.writeInfo("Checking: debugger evidence (suspension + evaluation)")
        assertDebuggerEvidence(combined, console)
        console.writeSuccess("Debugger evidence validated")

        console.writeSuccess("Agent '$agentName' identified the off-by-one bug")
        console.writeHeader("PASSED")

        println("[TEST] Agent '$agentName' successfully identified the off-by-one bug")
    }

    private fun assertBugFound(combined: String, output: String, expectedBugLine: String) {
        val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
        check(bugLine != null) {
            "Agent did not output required marker 'BUG_LINE:' (or equivalent).\nOutput:\n$combined"
        }
        check(bugLine.contains(expectedBugLine, ignoreCase = true)) {
            "BUG_LINE must mention '$expectedBugLine'.\nActual: $bugLine\nOutput:\n$combined"
        }
    }

}
