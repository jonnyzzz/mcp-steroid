/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
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

/**
 * Simplified basic matrix: each agent reaches a **preinstalled** (harness-started) IDE through the
 * **devrig stdio MCP bridge** — devrig is the ONLY MCP server registered (`AiMode.AI_DEVRIG`), no direct
 * HTTP MCP. A trivial `steroid_execute_code` call proves the agent → devrig (stdio) → IDE path works.
 *
 * This is the STDIO half of the {HTTP, STDIO} × {Claude, Codex, Gemini} connection matrix; the HTTP half
 * is [WhatYouSeeTest.executeCodeViaMcp] (`AiMode.AI_MCP`). Kept as a separate single-container test so we
 * never hold two IDE containers at once. The matrix is enumerated as one method per agent on purpose — a
 * cleaner parameterization across {transport} × {agent} is backlogged.
 *
 * "Preinstalled IDE" (vs. the managed-backend download path in `DevrigManagedBackendGuiIntegrationTest`):
 * the harness starts the IDE, so this test isolates the devrig **bridge + agent connection**, not the IDE
 * download.
 */
class DevrigStdioAgentMatrixTest {

    @Test fun `exec_code via devrig stdio - claude`() = execViaDevrigStdio(session.aiAgents.claude)
    @Test fun `exec_code via devrig stdio - codex`() = execViaDevrigStdio(session.aiAgents.codex)
    @Test fun `exec_code via devrig stdio - gemini`() = execViaDevrigStdio(session.aiAgents.gemini)

    private fun execViaDevrigStdio(agent: AiAgentSession) {
        agent.runPrompt(
            "Use the steroid_execute_code tool to run this Kotlin code: println(\"MCP_STEROID_WORKS\") " +
                    "Show the output of the execution. If the steroid_* tools are not loaded yet, load them " +
                    "first (e.g. via ToolSearch). " +
                    "If the code execution fails, respond with the word CODE_EXECUTION_FAILED.",
            timeoutSeconds = 180,
        )
            .assertExitCode(0) { "Prompt failed (${agent.displayName} via devrig stdio)" }
            .assertNoErrorsInOutput("exec_code via devrig stdio must have no errors (${agent.displayName})")
            .assertNoMessageInOutput("CODE_EXECUTION_FAILED")
            .assertOutputContains("MCP_STEROID_WORKS")
    }

    companion object {
        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                IntelliJContainerOpts(
                    consoleTitle = "devrig-stdio-matrix",
                    // Preinstalled IDE, no project content needed for a trivial exec_code — EmptyProject is fast.
                    project = IntelliJProject.EmptyProject,
                    // Register ONLY the devrig stdio bridge as the agents' MCP server (no direct HTTP MCP).
                    aiMode = AiMode.AI_DEVRIG,
                ),
            ).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger session creation (IDE start, MCP readiness, devrig deploy).
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
