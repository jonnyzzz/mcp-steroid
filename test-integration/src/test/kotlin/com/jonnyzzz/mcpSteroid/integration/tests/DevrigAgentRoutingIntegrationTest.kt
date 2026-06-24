/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.McpRegistrationTransport
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class DevrigAgentRoutingIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun claudeUsesDevrigStdioToDiscoverProjectAndExecuteCode() {
        val agent = session.aiAgents.claude
        val diagnostics = session.diagnosticsSummary()
        val registration = agent.mcpRegistrations.singleOrNull()
            ?: error("AI_DEVRIG must register exactly one MCP server for Claude\n$diagnostics\n${agent.mcpRegistrations}")
        assertEquals("mcp-steroid", registration.name)
        assertEquals(McpRegistrationTransport.STDIO, registration.transport)
        assertEquals("/home/agent/devrig", registration.command?.command)
        assertEquals(listOf("mpc"), registration.command?.args)
        assertNull(registration.url, "AI_DEVRIG must not register direct HTTP MCP for Claude")
        assertClaudeStrictConfigUsesOnlyDevrigStdio(agent.strictMcpConfigJson, diagnostics)

        val result = agent.runPrompt(
            prompt = """
                You are validating the MCP server named "mcp-steroid".

                Use MCP tools only for these steps:
                1. Call steroid_list_projects.
                2. Pick the single returned project_name exactly as returned.
                3. Call steroid_execute_code for that project_name with this Kotlin code:
                   println("DEVRIG_NPX_EXEC_OK")

                Print these final markers on separate lines:
                PROJECT_NAME: <the exact project_name you used>
                EXEC_RESULT: DEVRIG_NPX_EXEC_OK

                If any MCP call fails, print DEVRIG_NPX_FAILED and explain the failure.
                Output plain text only.
            """.trimIndent(),
            timeoutSeconds = 600,
        ).awaitForProcessFinish()
            .assertExitCode(0) {
                "[${agent.displayName}] devrig stdio MCP prompt failed with exit $exitCode: $stderr\n$diagnostics"
            }

        val combined = result.stdout + "\n" + result.stderr
        check(!combined.contains("DEVRIG_NPX_FAILED", ignoreCase = true)) {
            "agent reported DEVRIG_NPX_FAILED.\n$diagnostics\n$combined"
        }
        result.assertNoErrorsInOutput("devrig stdio MCP prompt must have no errors\n$diagnostics")

        result.assertOutputContains(
            "PROJECT_NAME:",
            message = "agent must report the routed project_name\n$diagnostics",
        )
        result.assertOutputContains(
            "EXEC_RESULT: DEVRIG_NPX_EXEC_OK",
            message = "agent must report execute_code marker\n$diagnostics",
        )

        check(combined.contains("steroid_list_projects") || combined.contains("list_projects")) {
            "agent output/logs should show project discovery through MCP.\n$diagnostics\n$combined"
        }
        check(combined.contains("steroid_execute_code") || combined.contains("execute_code")) {
            "agent output/logs should show execution through MCP.\n$diagnostics\n$combined"
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val lifetime by lazy { CloseableStackHost(DevrigAgentRoutingIntegrationTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "devrig stdio MCP agent routing",
                aiMode = AiMode.AI_DEVRIG,
            )).waitForProjectReady()
        }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            session.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }

        private fun assertClaudeStrictConfigUsesOnlyDevrigStdio(configJson: String?, diagnostics: String) {
            val config = json.parseToJsonElement(
                configJson ?: error("Claude AI_DEVRIG must provide --mcp-config\n$diagnostics")
            ).jsonObject
            val servers = config["mcpServers"]?.jsonObject
                ?: error("Claude --mcp-config missing mcpServers\n$diagnostics\n$config")
            assertEquals(setOf("mcp-steroid"), servers.keys, "Claude must receive exactly one MCP server")
            val server = servers.getValue("mcp-steroid").jsonObject
            assertEquals("stdio", server["type"]?.jsonPrimitive?.contentOrNull)
            assertEquals("/home/agent/devrig", server["command"]?.jsonPrimitive?.contentOrNull)
            assertEquals(
                listOf("mpc"),
                server["args"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull },
            )
            assertFalse(server.containsKey("url"), "Claude AI_DEVRIG config must not contain direct HTTP MCP URL")
        }
    }
}
