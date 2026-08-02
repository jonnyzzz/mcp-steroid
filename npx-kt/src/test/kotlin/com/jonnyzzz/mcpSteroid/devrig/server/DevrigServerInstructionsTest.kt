/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.mcp.FramingBuffer
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Tool
import com.jonnyzzz.mcpSteroid.mcp.encodeNdjsonMessage
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * jonnyzzz/mcp-steroid#417 — the `initialize` result must carry the steroid capability statement.
 *
 * A harness that defers MCP tools (Claude Code indexes tool NAMES only until a `ToolSearch`) never
 * reads a tool description on its own, so `instructions` is the one place a task-only prompt can
 * learn the IDE exists. This test drives the real `devrig mcp` server over the stdio transport and
 * asserts the text actually reaches the wire.
 */
class DevrigServerInstructionsTest {

    @Test
    fun `initialize carries the steroid capability statement`(@TempDir tempDir: Path) {
        val instructions = runDevrigMcp(tempDir).initializeResult["instructions"]?.jsonPrimitive?.contentOrNull
        assertEquals(
            DEVRIG_MCP_SERVER_INSTRUCTIONS,
            instructions,
            "devrig must advertise its capability statement in the initialize result",
        )
    }

    @Test
    fun `the capability statement names the tool prefix and the IDE provisioning commands`(
        @TempDir tempDir: Path,
    ) {
        val instructions = runDevrigMcp(tempDir).initializeResult["instructions"]?.jsonPrimitive?.contentOrNull
            ?: error("initialize result has no instructions")

        // The two facts an agent cannot discover any other way before loading a schema: the tool
        // name prefix to search for, and how to get an IDE when none is running.
        for (fact in listOf("steroid_", "devrig backend download", "devrig backend start")) {
            assertTrue(fact in instructions, "capability statement must mention '$fact': $instructions")
        }
    }

    @Test
    fun `the capability statement names only tools devrig actually advertises`(@TempDir tempDir: Path) {
        val run = runDevrigMcp(tempDir)
        val advertised = run.tools.map(Tool::name).toSet()
        val mentioned = Regex("steroid_\\w+").findAll(DEVRIG_MCP_SERVER_INSTRUCTIONS).map { it.value }.toSet()

        assertTrue(mentioned.isNotEmpty(), "capability statement must name the tools by name")
        assertEquals(
            emptySet(),
            mentioned - advertised,
            "capability statement names tools devrig does not advertise (advertised: $advertised)",
        )
    }

    private class DevrigMcpRun(val initializeResult: kotlinx.serialization.json.JsonObject, val tools: List<Tool>)

    /**
     * Boots the production `devrig mcp` server ([runStubStdioMcpServer]) on in-memory streams, feeds
     * it one NDJSON `initialize`, and returns the parsed result plus the tools it registered. The
     * stdin buffer ends right after the request, so the reader loop sees EOF and `run()` returns.
     */
    private fun runDevrigMcp(tempDir: Path): DevrigMcpRun {
        val initialize = encodeNdjsonMessage(
            """{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION",""" +
                """"capabilities":{},"clientInfo":{"name":"instructions-test","version":"1.0"}}}"""
        )
        val stdout = ByteArrayOutputStream()
        val lifetime = CloseableStackHost()
        var core: McpServerCore? = null
        try {
            runBlocking {
                runStubStdioMcpServer(
                    DevrigServices(
                        lifetime = lifetime,
                        homePaths = HomePaths(tempDir.resolve("devrig-home")).also { it.mkdirsAll() },
                        mcpStdin = ByteArrayInputStream(initialize.toByteArray(Charsets.UTF_8)),
                        mcpStdout = PrintStream(stdout, true, Charsets.UTF_8),
                    ),
                    onServerReady = { core = it },
                )
            }
        } finally {
            lifetime.closeAllStacks()
        }

        val buffer = FramingBuffer().also { it.append(stdout.toByteArray()) }
        val frame = buffer.readNextFrame() ?: error("devrig wrote no response to initialize")
        val response = Json.parseToJsonElement(frame.payloadText).jsonObject
        val result = response["result"]?.jsonObject ?: error("initialize failed: $response")
        return DevrigMcpRun(result, (core ?: error("onServerReady was never called")).toolRegistry.listTools())
    }
}
