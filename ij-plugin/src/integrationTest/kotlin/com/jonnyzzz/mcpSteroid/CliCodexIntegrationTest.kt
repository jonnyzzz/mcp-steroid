/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("RedundantOverride")

package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.testHelper.*
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests that run OpenAI Codex CLI against the MCP server.
 *
 * These tests use Docker to isolate Codex CLI from the local system,
 * preventing side effects from MCP server registrations.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - OPENAI_API_KEY must be available (either in ~/.openai or environment)
 *
 * The tests will automatically:
 * - Build the Docker image if needed
 * - Start a container for each test
 * - Run Codex commands inside the container
 * - Clean up the container after the test
 *
 * ============================================================================
 * IMPORTANT: TEST INTEGRITY RULES - DO NOT FAKE THESE TESTS
 * ============================================================================
 *
 * 1. NEVER ignore ERROR patterns in output - if Claude reports "ERROR:" or
 *    "**ERROR:" in its response, the test MUST fail.
 *
 * 2. ALWAYS verify actual tool calls happened - check for specific tool output
 *    patterns like "TOOL:" and "PROJECTS:" that indicate real execution.
 *
 * 3. NEVER use loose assertions like "contains steroid_" when Claude just
 *    mentions the word in an error message - verify actual successful calls.
 *
 * 4. If the test fails, report the failure. Do not modify assertions to make
 *    a failing test pass without fixing the underlying issue.
 *
 * 5. Compare with CodexCliIntegrationTest - both should have equivalent
 *    assertion strictness.
 * ============================================================================
 */
class CliCodexIntegrationTest : CliIntegrationTestBase() {
    private fun codexSession() = DockerCodexSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = codexSession()

    /**
     * Tests that Codex CLI is properly installed in the Docker container.
     */
    fun testCodexInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        val session = codexSession()

        // Check codex version
        session.runInContainer(listOf("--version"))
            .assertExitCode(0) { "Failed to exec --version command"}
            .assertNoErrorsInOutput("version command")
            .assertExitCode(0, "version command")
    }

    /**
     * Tests that MCP server can be added via `codex mcp add` command.
     * Codex uses a different syntax than Claude CLI.
     *
     * For HTTP-based servers, Codex uses: codex mcp add <name> --transport http --url <url>
     *
     * Note: If Codex CLI doesn't have mcp subcommand, the test is skipped.
     */
    fun testMcpServerAddCommand(): Unit = timeoutRunBlocking(180.seconds) {
        val session = codexSession()

        val mcpName = "intellij-steroid-test-${UUID.randomUUID()}"
        session.registerHttpMcp(resolveDockerUrl(), mcpName)

        session
            .runInContainer(listOf("mcp", "get", mcpName))
            .assertExitCode(0) { "MCP get command" }
            .assertOutputContains(
                "enabled: true",
                "transport: streamable_http",
                message = "MCP server registration")
            .assertNoErrorsInOutput(message = "MCP server registration")
    }


    override fun testDiscoversSteroidTools() {
        //needed to make test runner work
        super.testDiscoversSteroidTools()
    }

    override fun testSystemPropertyCanBeRead() {
        //needed to make test runner work
        super.testSystemPropertyCanBeRead()
    }

    override fun testCompilationErrorsDelivered() {
        //needed to make test runner work
        super.testCompilationErrorsDelivered()
    }

    override fun testCompilationWarningsDelivered() {
        //needed to make test runner work
        super.testCompilationWarningsDelivered()
    }

    override fun testExecSessionReset() {
        timeoutRunBlocking(360.seconds) {
            val firstSession = newAiSession()

            firstSession.runPrompt(
                """
                You are testing MCP integration. You MUST call steroid_execute_code exactly two times, in order.
                Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
                Reason: codex cli session reset test.
                First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
                For each steroid_execute_code call (#1 and #2), pass project_name=PROJECT_NAME.
                IMPORTANT: "intellij" is the MCP server name, not a valid project_name value.

                Call #1 code:
                ```
                println("EXEC1_OK")
                ```

                Call #2 code:
                ```
                ${SteroidsMcpServer::class.java.name}.getInstance().forgetAllForTest()
                println("SESSIONS_FORGOTTEN: OK")
                ```
                IMPORTANT: Call #2 restarts the MCP server. The connection WILL break and the call WILL fail.
                This is expected and correct. Do NOT retry call #2 in this prompt.

                After each call, print a result line:
                RESULT1: <line from call #1 output containing EXEC1_OK>
                RESULT2: RESET_CONNECTION_BROKEN (if call #2 failed, which is expected) or RESET_OK (if it somehow succeeded)

                Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
                """.trimIndent(),
                timeoutSeconds = 300
            )
                .assertExitCode(0){ "prompt #1" }
                .assertOutputContains("RESULT1:", "EXEC1_OK", message = "exec #1 should run before restart")
                .assertOutputContains("RESULT2:", "RESET_CONNECTION_BROKEN", message = "exec #2 must report connection broken (server restart kills the HTTP connection)")

            // Codex may keep a broken MCP transport state for the next `exec` after call #2.
            // Use a fresh Codex session to validate the restarted server behavior deterministically.
            val secondSession = newAiSession()

            secondSession.runPrompt(
                """
                You are continuing an MCP session reset test after a previous connection break.
                Use only the MCP server named "intellij" for tool calls. Do not call list_mcp_resources.
                First, call steroid_list_projects exactly once and take the first project's "name" as PROJECT_NAME.
                Then call steroid_execute_code exactly once with project_name=PROJECT_NAME and this code:
                ```
                val count = ${SteroidsMcpServer::class.java.name}.getInstance().getServer().sessionManager.getSessionCount()
                println("EXEC2_OK: sessions=" + count)
                ```
                Print exactly one line:
                RESULT3: <line from execute_code output containing EXEC2_OK>
                Output must be plain text only. Do NOT use Markdown, lists, or code blocks.
                """.trimIndent(),
                timeoutSeconds = 300
            )
                .assertExitCode(0) { "prompt #2" }
                .assertNoErrorsInOutput("prompt #2")
                .assertOutputContains("RESULT3:", "EXEC2_OK", message = "exec #3 should run on the restarted server")
                .assertOutputContains("sessions=1", message = "restarted server should have exactly 1 fresh session (old sessions were closed, this is a new session)")
        }
    }
}
