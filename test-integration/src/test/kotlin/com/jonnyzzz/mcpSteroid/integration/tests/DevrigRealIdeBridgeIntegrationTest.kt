/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigSteroidDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackDriver
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class DevrigRealIdeBridgeIntegrationTest {

    /**
     * A fresh nested stack per test method (JUnit 5 default per-method instance lifecycle), holding that
     * method's `devrig mpc` process so it is torn down in [tearDownMethodStack] before the next method runs.
     * Only ONE `mpc` may live per container at a time: its JDWP debug agent binds a FIXED port
     * (server=y, address=*:DEVRIG_DEBUG_PORT) — a second concurrent `mpc` would die at startup with
     * "Address already in use", and its MCP `initialize` would then time out. See [DevrigSteroidDriver].
     */
    private lateinit var methodStack: CloseableStackDriver

    @BeforeEach
    fun setUpMethodStack() {
        methodStack = lifetime.nestedStack("devrig-mpc-per-method")
    }

    @AfterEach
    fun tearDownMethodStack() {
        methodStack.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun devrigStdioDiscoversRunningIdeAndExecutesCode() {
        val diagnostics = session.diagnosticsSummary()
        val devrigCommand = DevrigSteroidDriver.deploy(session.scope, session.mcpSteroid).devrigCommand
        assertEquals("/home/agent/devrig", devrigCommand.command)
        assertEquals(listOf("mpc"), devrigCommand.args)

        val process = startContainerStdioMcp(methodStack, devrigCommand)
        process.initialize()

        val projectName = waitForProjectName(process, diagnostics)
        val marker = "DEVRIG_REAL_IDE_EXEC_OK"
        val result = toolCall(
            process = process,
            name = "steroid_execute_code",
            arguments = buildJsonObject {
                put("project_name", projectName)
                put("task_id", "real-ide-devrig-stdio")
                put("reason", "verify devrig stdio routes to the already-running IDE")
                put("code", """println("$marker")""")
            },
            diagnostics = diagnostics,
        )

        assertFalse(isToolError(result), "execute_code must not return a tool error\n$diagnostics\n$result")
        assertTrue(
            textContent(result).contains(marker),
            "execute_code result must contain marker $marker\n$diagnostics\n$result",
        )
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun devrigStdioForwardsExecuteCodeProgressNotifications() {
        val diagnostics = session.diagnosticsSummary()
        val devrigCommand = DevrigSteroidDriver.deploy(session.scope, session.mcpSteroid).devrigCommand

        val process = startContainerStdioMcp(methodStack, devrigCommand)
        process.initialize()

        val projectName = waitForProjectName(process, diagnostics)
        val progressToken = "devrig-progress-${System.nanoTime()}"
        val progressMarker = "DEVRIG_REAL_IDE_PROGRESS"
        val result = toolCallWithOutOfBandFrames(
            process = process,
            name = "steroid_execute_code",
            arguments = buildJsonObject {
                put("project_name", projectName)
                put("task_id", "real-ide-devrig-progress")
                put("reason", "verify devrig stdio forwards execute-code progress")
                put("code", """progress("$progressMarker"); println("DONE")""")
                putJsonObject("_meta") {
                    put("progressToken", progressToken)
                }
            },
            diagnostics = diagnostics,
        )

        assertFalse(isToolError(result.result), "execute_code must not return a tool error\n$diagnostics\n${result.result}")
        val progressFrames = result.outOfBandFrames + process.drainNoMore(timeoutMillis = 500)
        val matchingProgress = progressFrames
            .filter { it["method"]?.jsonPrimitive?.contentOrNull == "notifications/progress" }
            .mapNotNull { it["params"]?.jsonObject }
            .filter { it["progressToken"]?.jsonPrimitive?.contentOrNull == progressToken }
        assertTrue(
            matchingProgress.any { it["message"]?.jsonPrimitive?.contentOrNull?.contains(progressMarker) == true },
            "Expected progress notification with token $progressToken and marker $progressMarker\n" +
                    "$diagnostics\nframes=$progressFrames\nresult=${result.result}",
        )
    }

    private fun startContainerStdioMcp(
        stack: CloseableStack,
        devrigCommand: StdioMcpCommand,
    ): StdioMcpProcess =
        startStdioMcpProcess(
            lifetime = stack,
            resourceName = "container-devrig",
        ) { stdin: Flow<ByteArray> ->
            session.scope.startProcessInContainer {
                args(listOf(devrigCommand.command) + devrigCommand.args)
                    .interactive()
                    .stdin(stdin)
                    .timeoutSeconds(300)
                    .description("devrig stdio MCP against running IDE")
                    .quietly()
            }
        }

    private fun waitForProjectName(process: StdioMcpProcess, diagnostics: String): String {
        repeat(80) {
            val result = toolCall(
                process = process,
                name = "steroid_list_projects",
                arguments = buildJsonObject {},
                diagnostics = diagnostics,
            )
            assertFalse(isToolError(result), "list_projects returned a tool error\n$diagnostics\n$result")
            val projects = json.parseToJsonElement(textContent(result)).jsonObject["projects"]?.jsonArray
                ?: error("list_projects result missing projects\n$diagnostics\n$result")
            if (projects.isNotEmpty()) {
                val project = projects.singleOrNull()?.jsonObject
                    ?: error("Expected one devrig-discovered project, got ${projects.size}\n$diagnostics\n$projects")
                // #92: route by the unique project_name (the hash-suffixed key), not the raw folder name —
                // the IDE resolves only the unique key (no raw-name fallback).
                return project["project_name"]?.jsonPrimitive?.contentOrNull
                    ?: error("project missing project_name\n$diagnostics\n$project")
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for devrig to discover the running IDE project\n$diagnostics")
    }

    private fun toolCall(
        process: StdioMcpProcess,
        name: String,
        arguments: JsonObject,
        diagnostics: String,
    ): JsonObject {
        val response = process.request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis = 60_000,
        )
        assertNull(response["error"], "tools/call returned JSON-RPC error\n$diagnostics\n$response")
        return response["result"]?.jsonObject ?: error("tools/call response missing result\n$diagnostics\n$response")
    }

    private fun toolCallWithOutOfBandFrames(
        process: StdioMcpProcess,
        name: String,
        arguments: JsonObject,
        diagnostics: String,
    ): ToolCallExchange {
        val exchange = process.requestWithOutOfBandFrames(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis = 60_000,
        )
        assertNull(exchange.response["error"], "tools/call returned JSON-RPC error\n$diagnostics\n${exchange.response}")
        val result = exchange.response["result"]?.jsonObject
            ?: error("tools/call response missing result\n$diagnostics\n${exchange.response}")
        return ToolCallExchange(result = result, outOfBandFrames = exchange.outOfBandFrames)
    }

    private fun isToolError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

    private fun textContent(result: JsonObject): String =
        result["content"]?.jsonArray
            ?.joinToString("\n") { content ->
                content.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            ?: error("tool result missing content: $result")

    private data class ToolCallExchange(
        val result: JsonObject,
        val outOfBandFrames: List<JsonObject>,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val lifetime by lazy { CloseableStackHost(DevrigRealIdeBridgeIntegrationTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "devrig stdio MCP real IDE bridge",
                aiMode = AiMode.NONE,
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
    }
}
