/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.McpResourceUris
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * End-to-end smoke test for the Kotlin `devrig` MCP deployment path.
 *
 * The test starts IntelliJ in Docker, deploys the `:npx-kt` application ZIP into
 * the same container, registers that stdio command with Claude, and verifies the
 * agent can call MCP Steroid tools through devrig.
 */
class DevrigAgentIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude connects through devrig stdio`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "devrig-claude",
            aiMode = AiMode.AI_DEVRIG,
        )).waitForProjectReady()

        val execMarker = "DEVRIG_EXEC_OK"
        val prompt = buildString {
            appendLine("# Task: verify devrig MCP transport")
            appendLine()
            appendLine("Use the MCP Steroid tools from the mcp-steroid MCP server. Do not inspect files directly.")
            appendLine("1. Call steroid_list_projects and count the projects.")
            appendLine("2. Choose the current project_name from that response.")
            appendLine("3. Call steroid_fetch_resource with that project_name and URI ${McpResourceUris.promptSkill}.")
            appendLine("4. Call steroid_execute_code with that project_name and the code: println(\"$execMarker\")")
            appendLine("5. Reply with exactly these marker lines:")
            appendLine("DEVRIG_PROJECTS: <number>")
            appendLine("DEVRIG_RESOURCE_READ: yes")
            appendLine("DEVRIG_EXEC: yes")
            appendLine("DEVRIG_TRANSPORT: stdio")
        }

        val result = session.aiAgents.claude.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        val hasTransportMarker = hasMarker(output, "DEVRIG_TRANSPORT", "stdio")
        if (result.exitCode != 0 && !hasTransportMarker) {
            result.assertExitCode(0) { "Claude devrig transport test failed before markers" }
        }

        check(combined.contains("steroid_list_projects")) {
            "Agent must call steroid_list_projects through the devrig MCP server. Output:\n${combined.take(4000)}"
        }
        check(combined.contains("steroid_fetch_resource")) {
            "Agent must call steroid_fetch_resource through the devrig MCP server. Output:\n${combined.take(4000)}"
        }
        check(combined.contains("steroid_execute_code")) {
            "Agent must call steroid_execute_code through the devrig MCP server. Output:\n${combined.take(4000)}"
        }
        check(hasMarker(output, "DEVRIG_RESOURCE_READ", "yes")) {
            "Missing DEVRIG_RESOURCE_READ marker. Output:\n${output.take(4000)}"
        }
        check(hasMarker(output, "DEVRIG_EXEC", "yes")) {
            "Missing DEVRIG_EXEC marker. Output:\n${output.take(4000)}"
        }
        check(hasTransportMarker) {
            "Missing DEVRIG_TRANSPORT marker. Output:\n${output.take(4000)}"
        }

        // execute_code routes through the devrig bridge's tool-call stream. devrig reads the endpoint
        // URL from the marker's devrigEndpoint and posts to <rpcBaseUrl>/tools/call/stream — today that
        // is the plugin's Ktor bridge at the /api/jonnyzzz/mcp-steroid/v1 prefix (which server hosts it
        // is a plugin detail). Assert the advertised endpoint was hit, and that the retired /npx/v1
        // path is gone.
        val ideaLog = File(session.runDirInContainer, "intellij/ide-log/idea.log")
        val ideaLogText = ideaLog.readText()
        check(ideaLogText.contains("POST /api/jonnyzzz/mcp-steroid/v1/tools/call/stream")) {
            "devrig must dispatch IDE tool calls through the advertised bridge endpoint " +
                "(/api/jonnyzzz/mcp-steroid/v1/tools/call/stream). Missing stream call in ${ideaLog.absolutePath}"
        }
        val retiredBridgePath = listOf("", "npx", "v1", "tools", "call", "stream").joinToString("/")
        check(!ideaLogText.contains("POST $retiredBridgePath")) {
            "The legacy /npx/v1 bridge path was removed; devrig must use the advertised endpoint. " +
                "Unexpected legacy stream call in ${ideaLog.absolutePath}"
        }
    }

    private fun hasMarker(output: String, marker: String, expectedValue: String): Boolean {
        val expected = "$marker: $expectedValue"
        return output.lineSequence().any { line ->
            line.contains(expected, ignoreCase = true)
        }
    }
}
