/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.mcp.JsonRpcErrorCodes
import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackDriver
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.StdoutCleanlinessHarness
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Integration tests that drive the devrig CLI launcher (`bin/devrig mcp`) and exchange MCP 2025-11-25
 * JSON-RPC frames over stdio.
 *
 * The launcher runs INSIDE the shared `mcp-cli` container ([DevrigCliContainer]), never on the host — a
 * host run would create the developer's real `~/.mcp-steroid` (devrig's home is hardcoded and no longer
 * overridable). The container is built once for the class; each test gets a fresh `devrig mcp` process.
 *
 * Scope: confirm the stdio MCP server boots, completes the initialize handshake, and answers list/ping
 * requests with shapes that match the spec.
 */
class CliMcpStdioIntegrationTest {

    private lateinit var methodStack: CloseableStackDriver
    private lateinit var process: StdioMcpProcess

    @BeforeEach
    fun setUp() {
        methodStack = lifetime.nestedStack("devrig-mpc-per-method")
        process = cli.startMpc(methodStack)
    }

    @AfterEach
    fun tearDown() {
        methodStack.closeAllStacks()
    }

    @Test
    fun `initialize returns server info and advertised capabilities`() {
        val response = process.initialize()

        assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.contentOrNull, "jsonrpc field")
        assertNull(response["error"], "initialize must not return an error: $response")

        val result = response["result"]?.jsonObject
            ?: error("initialize response missing `result`: $response")

        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.contentOrNull)

        val serverInfo = result["serverInfo"]?.jsonObject
            ?: error("initialize result missing `serverInfo`: $result")
        assertEquals("devrig", serverInfo["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(serverInfo["version"]?.jsonPrimitive?.contentOrNull, "serverInfo.version")

        val capabilities = result["capabilities"]?.jsonObject
            ?: error("initialize result missing `capabilities`: $result")
        assertNotNull(capabilities["tools"], "capabilities.tools must be advertised")
        // Since S6 (commit 919e1e03) devrig deliberately stopped advertising `prompts`
        // and `resources` — the corpus is reached via the steroid_fetch_resource TOOL
        // (which needs project_name for IDE-conditional rendering), not via the MCP
        // prompts/resources surfaces. `logging` is advertised so the update-notice
        // notifications/message is accepted. Assert that contract explicitly.
        assertNull(capabilities["prompts"], "devrig must NOT advertise prompts (S6): corpus is via steroid_fetch_resource")
        assertNull(capabilities["resources"], "devrig must NOT advertise resources (S6): corpus is via steroid_fetch_resource")
        assertNotNull(capabilities["logging"], "capabilities.logging must be advertised for update notices")
    }

    @Test
    fun `tools list contains every steroid_ tool registered by McpSteroidTools`() {
        process.initialize()

        val response = process.request("tools/list", buildJsonObject {})
        val tools = response["result"]?.jsonObject?.get("tools")?.jsonArray
            ?: error("tools/list result missing `tools` array: $response")

        val names = tools.map {
            val tool = it.jsonObject
            requireNotNull(tool["name"]?.jsonPrimitive?.contentOrNull) { "tool entry missing name: $tool" }
        }.toSet()

        // This protocol-level check (devrig mcp directly, no agent) is the
        // canonical guard against a McpSteroidTools.registerAll regression; the
        // live-agent path lives in :test-integration's DevrigStdioAgentMatrixTest.
        val missing = EXPECTED_STEROID_TOOL_NAMES - names
        assertTrue(
            missing.isEmpty(),
            "tools/list missing expected tools: $missing\nactual=$names"
        )

        // Each tool must have a name + description + inputSchema (Tool descriptor shape).
        tools.forEach {
            val tool = it.jsonObject
            assertNotNull(tool["description"]?.jsonPrimitive?.contentOrNull, "tool ${tool["name"]} missing description")
            assertNotNull(tool["inputSchema"]?.jsonObject, "tool ${tool["name"]} missing inputSchema")
        }
    }

    @Test
    fun `prompts list returns an array even when no prompts are registered`() {
        process.initialize()

        val response = process.request("prompts/list", buildJsonObject {})
        assertNull(response["error"], "prompts/list must not error: $response")

        val prompts = response["result"]?.jsonObject?.get("prompts")
        assertTrue(prompts is JsonArray, "result.prompts must be an array: $response")
    }

    @Test
    fun `resources list returns an array even when no resources are registered`() {
        process.initialize()

        val response = process.request("resources/list", buildJsonObject {})
        assertNull(response["error"], "resources/list must not error: $response")

        val resources = response["result"]?.jsonObject?.get("resources")
        assertTrue(resources is JsonArray, "result.resources must be an array: $response")
    }

    @Test
    fun `ping returns an empty result object`() {
        process.initialize()

        val response = process.request("ping", buildJsonObject {})
        assertNull(response["error"], "ping must not error: $response")

        val result = response["result"]?.jsonObject
            ?: error("ping must return a JSON object result: $response")
        assertTrue(result.isEmpty(), "ping result must be empty: $result")
    }

    @Test
    fun `unknown method returns JSON-RPC method-not-found error`() {
        process.initialize()

        val response = process.request("does/not/exist", buildJsonObject {})
        val error = response["error"]?.jsonObject
            ?: error("unknown method must return an error: $response")

        // JSON-RPC §5.1: -32601 = Method not found.
        assertEquals(
            -32601,
            error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            "method-not-found error code: $error"
        )
    }

    @Test
    fun `notifications without id receive no response`() {
        process.initialize()

        // Spec (JSON-RPC 2.0 §4.1): notifications must NEVER produce a response.
        // We send one, then drain the server's stdout for a window long enough
        // to catch a misbehaving "respond to every frame" implementation. Any
        // frame that arrives during the wait is a violation and fails the test.
        process.notify("notifications/cancelled", buildJsonObject {
            put("requestId", "this-request-does-not-exist")
            put("reason", "test")
        })

        val unexpected = process.drainNoMore(timeoutMillis = 500)
        assertTrue(
            unexpected.isEmpty(),
            "notifications must not yield any response, but server emitted: $unexpected",
        )

        // A real round-trip after the notification confirms the session is still
        // alive (i.e. the server didn't crash on the notification handling).
        val pingResponse = process.request("ping", buildJsonObject {})
        assertNull(pingResponse["error"], "ping after notification must succeed: $pingResponse")
    }

    @Test
    fun `a malformed line on stdin does not brick the session`() {
        // jonnyzzz/mcp-steroid#461: `(echo 'this is not json'; echo '<initialize>') | devrig mcp`
        // wrote ZERO bytes and exited 0 — the handshake queued behind the stray line was never
        // parsed, and nothing on stderr named the cause. A client-side CRLF, BOM, or wrapper
        // log line bricked the whole session invisibly.
        val garbage = "this is not json"
        val stdin = "$garbage\n".toByteArray(Charsets.UTF_8) + StdoutCleanlinessHarness.handshakeBytes

        val result = cli.runMpcWithStdin(stdin)

        assertEquals(
            0,
            result.exitCode,
            "devrig mcp must still shut down cleanly\nstdout=\n${result.stdout}\nstderr=\n${result.stderr}",
        )
        // Every handshake id round-trips — i.e. the stray line cost no requests — and every
        // stdout line is still a bare JSON-RPC envelope.
        StdoutCleanlinessHarness.assertStdoutClean(
            stdout = result.stdout,
            variantLabel = "docker:linux",
            stderrForDiagnostics = result.stderr,
        )

        val frames = result.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
        val parseErrors = frames.filter {
            it["error"]?.jsonObject?.get("code")?.jsonPrimitive?.int == JsonRpcErrorCodes.PARSE_ERROR
        }
        assertEquals(
            1,
            parseErrors.size,
            "the stray line must draw exactly one -32700 (JSON-RPC 2.0 §4.2)\nstdout=\n${result.stdout}",
        )
        assertTrue(
            parseErrors.single()["id"] is JsonNull,
            "a parse error has no addressable request: ${parseErrors.single()}",
        )
        assertTrue(
            garbage in result.stderr,
            "stderr must name the offending line so a hung client is diagnosable\nstderr=\n${result.stderr}",
        )
    }

    companion object {
        private val lifetime by lazy { CloseableStackHost(CliMcpStdioIntegrationTest::class.java.simpleName) }
        private val cli by lazy { lifetime.startDevrigCliContainer() }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            cli.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
