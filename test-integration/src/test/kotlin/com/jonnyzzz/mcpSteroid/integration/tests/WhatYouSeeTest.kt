/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class WhatYouSeeTest {

    @Test fun `describeMcp claude`() = describeMcp(session.aiAgents.claude)
    @Test fun `describeMcp codex`() = describeMcp(session.aiAgents.codex)
    @Test fun `describeMcp gemini`() = describeMcp(session.aiAgents.gemini)

    @Test fun `checkWhatYouSee claude`() = checkWhatYouSee(session.aiAgents.claude)
    @Test fun `checkWhatYouSee codex`() = checkWhatYouSee(session.aiAgents.codex)
    @Test fun `checkWhatYouSee gemini`() = checkWhatYouSee(session.aiAgents.gemini)

    @Test fun `executeCodeViaMcp claude`() = executeCodeViaMcp(session.aiAgents.claude)
    @Test fun `executeCodeViaMcp codex`() = executeCodeViaMcp(session.aiAgents.codex)
    @Test fun `executeCodeViaMcp gemini`() = executeCodeViaMcp(session.aiAgents.gemini)

    @Test fun `toolPreference claude`() = toolPreference(session.aiAgents.claude)
    @Test fun `toolPreference codex`() = toolPreference(session.aiAgents.codex)
    @Test fun `toolPreference gemini`() = toolPreference(session.aiAgents.gemini)

    private fun describeMcp(agent: AiAgentSession) {
        val result = agent.runPrompt(
            "List all MCP tools you have access to. " +
                    "For each tool, print its exact name on a separate line. " +
                    "If you have no MCP tools, respond with the word NO_MCP_TOOLS_FOUND.",
            timeoutSeconds = 180
        )
            .assertExitCode(0) { "Prompt failed" }
            .assertNoErrorsInOutput("describeMcp must have no errors")
            .assertNoMessageInOutput("NO_MCP_TOOLS_FOUND")

        // Verify the agent can see the core MCP Steroid tools
        result.assertOutputContains("execute_code")
        result.assertOutputContains("list_projects")
        result.assertOutputContains("take_screenshot")
    }

    private fun checkWhatYouSee(agent: AiAgentSession) {
        // Drive the agent through the MCP tools explicitly: first list projects + the available steroid_*
        // tools (which forces loading their schemas — Claude Code defers them, see
        // docs/claude-defers-mcp-tools.md), THEN describe the IDE. Without this, a client that defers the
        // tool schemas can answer NO_IDE_ACCESS in one turn without ever loading them.
        agent.runPrompt(
            "First, access my IDE through the MCP Steroid tools: call steroid_list_projects and list the " +
                    "open projects, and list the steroid_* tools you have available. If those tools are not " +
                    "loaded yet, load them first (e.g. via ToolSearch). " +
                    "Then describe the current state of my IntelliJ IDEA IDE — the open project name and what " +
                    "you can see. " +
                    "Respond with the word NO_IDE_ACCESS only if the steroid_* tools are genuinely unavailable " +
                    "after you have attempted to load them.",
            timeoutSeconds = 180
        )
            .assertExitCode(0) { "Prompt failed" }
            .assertNoErrorsInOutput("checkWhatYouSee must have no errors")
            .assertNoMessageInOutput("NO_IDE_ACCESS")
    }

    private fun executeCodeViaMcp(agent: AiAgentSession) {
        val result = agent.runPrompt(
            "Use the steroid_execute_code tool to run this Kotlin code: println(\"MCP_STEROID_WORKS\") " +
                    "Show the output of the execution. " +
                    "If the code execution fails, respond with the word CODE_EXECUTION_FAILED.",
            timeoutSeconds = 180
        )
            .assertExitCode(0) { "Prompt failed" }
            .assertNoErrorsInOutput("executeCodeViaMcp must have no errors")
            .assertNoMessageInOutput("CODE_EXECUTION_FAILED")

        result.assertOutputContains("MCP_STEROID_WORKS")
    }

    /**
     * Asks each agent to evaluate ALL its tools (built-in + MCP Steroid) for common
     * development tasks and choose the best tool for each. Verifies that MCP Steroid
     * tools are strongly preferred for IDE-specific operations.
     *
     * Tasks are selected to cover both steroid_execute_code (scripted IDE automation)
     * and dedicated steroid tools (list_projects, list_windows, take_screenshot, etc.).
     */
    private fun toolPreference(agent: AiAgentSession) {
        val result = agent.runPrompt(
            TOOL_PREFERENCE_PROMPT,
            timeoutSeconds = 300
        )
            .assertExitCode(0) { "Prompt failed" }
            .assertNoErrorsInOutput("toolPreference must have no errors")

        // Parse preferred tools from output
        val preferredLines = result.stdout.lines()
            .filter { it.startsWith("PREFERRED:") }
            .map { it.substringAfter("PREFERRED:").trim() }

        println("[${agent.displayName}] Tool preferences:")
        val taskLines = result.stdout.lines().filter { it.startsWith("TASK:") }
        for (i in preferredLines.indices) {
            val task = taskLines.getOrNull(i) ?: "TASK: ?"
            println("  $task -> ${preferredLines[i]}")
        }

        // Steroid detection: the preferred tool is a `steroid_*` MCP tool regardless of the agent's
        // tool-name prefixing. The decoded name can be bare (`steroid_execute_code`), Claude/Codex
        // double-underscore (`mcp__mcp-steroid__steroid_execute_code`), or the `mcp__mcp_steroid.steroid_*`
        // / `functions.mcp__…__steroid_*` forms — all contain the `steroid_<verb>` tool token.
        val steroidCount = preferredLines.count { it.contains("steroid_") }
        println("[${agent.displayName}] Steroid tool count: $steroidCount / ${preferredLines.size}")

        // Hard assertions. Some agents' decoded transcripts repeat the answer block (streamed + final),
        // so we get a MULTIPLE of TASK_COUNT PREFERRED lines (e.g. Claude emits 20 for 10 tasks). That
        // duplication is an output-capture artifact, not an agent error — require at least one preference
        // per task rather than exactly TASK_COUNT, and scale the steroid bar to the lines we actually saw.
        check(preferredLines.size >= TASK_COUNT) {
            "Expected at least $TASK_COUNT PREFERRED: lines but got ${preferredLines.size}. Output:\n${result.stdout}"
        }
        val minSteroid = (MIN_STEROID_COUNT * preferredLines.size + TASK_COUNT - 1) / TASK_COUNT  // ceil, scaled for duplication
        check(steroidCount >= minSteroid) {
            "Only $steroidCount/${preferredLines.size} preferences chose steroid tools (need ≥$minSteroid, i.e. $MIN_STEROID_COUNT/$TASK_COUNT). Output:\n${result.stdout}"
        }
        result.assertOutputContains("STEROID_COUNT:", message = "Agent must output summary count")
    }

    companion object {
        const val TASK_COUNT = 10
        const val MIN_STEROID_COUNT = 7

        val TOOL_PREFERENCE_PROMPT = """
            You have access to multiple tools: your built-in tools AND MCP tools from the "mcp-steroid" server.
            For each development task below, choose the SINGLE best tool you would use and explain why.

            Output must be plain text only. Do NOT use Markdown, bold, bullets, or code blocks.
            Do NOT add extra blank lines or commentary between answers.
            Format each answer EXACTLY as three lines:
            TASK: <task name>
            PREFERRED: <exact tool name>
            REASON: <one sentence explanation>

            Tasks:
            1. Run unit tests in the project
            2. Find all usages of a specific method across the codebase
            3. Refactor: rename a class across the entire codebase
            4. Check code for warnings and errors (inspections)
            5. Discover available quick-fixes and intentions at a specific code location
            6. List all open IDE windows and check their indexing and modal state
            7. View git blame annotations for a file
            8. List all open projects and their state
            9. Take a screenshot of the IDE to verify current UI state
            10. Open a new project in the IDE and verify it is ready

            After all 10 answers, print a summary line:
            STEROID_COUNT: <number of tasks where you chose an MCP steroid tool>
        """.trimIndent()

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "what-you-see",
            )).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger session creation (IDE start, MCP readiness)
            // The aiAgents lazy property will also call waitForMcpReady()
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
