/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * MCP stdio acceptance tests against the generic [McpStdioServer] + [McpServerCore].
 *
 * Each test:
 *   1. Builds an [McpServerCore] (with hand-rolled tool/resource/prompt fixtures
 *      where relevant).
 *   2. Pipes one or more JSON-RPC frames through [McpStdioServer] using
 *      [ByteArrayInputStream] / [ByteArrayOutputStream] (EOF terminates the loop).
 *   3. Re-parses the captured output through [FramingBuffer] and asserts JSON-RPC
 *      2.0 / MCP 2025-11-25 invariants.
 *
 * Coverage targets:
 *   - JSON-RPC 2.0: jsonrpc field, id echo (string/number/null), notification = no
 *     response, parse errors, invalid request, batch requests, error code mapping.
 *   - MCP 2025-11-25: initialize result shape, ping, tools/list+call, resources/list+read,
 *     prompts/list+get, server-initiated notifications + outgoing requests.
 *   - Framing: framed↔framed and ndjson↔ndjson parity, output mode locked on first
 *     inbound frame, multi-frame partial reads, UTF-8 multibyte payloads.
 */
class McpStdioServerProtocolTest {

    // =========================================================================
    // Test Harness
    // =========================================================================

    private class StdioHarness {
        val server: McpServerCore = newServer()
        private val inputBytes = ByteArrayOutputStream()

        fun sendFramed(jsonStr: String) {
            inputBytes.write(encodeFramedMessage(jsonStr).toByteArray(Charsets.UTF_8))
        }

        fun sendNdjson(jsonStr: String) {
            inputBytes.write(encodeNdjsonMessage(jsonStr).toByteArray(Charsets.UTF_8))
        }

        /** Writes [text] to stdin verbatim — no framing, no trailing newline. */
        fun sendRaw(text: String) {
            inputBytes.write(text.toByteArray(Charsets.UTF_8))
        }

        suspend fun runRaw(): ByteArray {
            val outputBuffer = ByteArrayOutputStream()
            val input = ByteArrayInputStream(inputBytes.toByteArray())
            McpStdioServer(server, input, outputBuffer).run()
            return outputBuffer.toByteArray()
        }

        suspend fun runAndGetElements(): List<JsonElement> {
            val raw = runRaw()
            val results = mutableListOf<JsonElement>()
            val buffer = FramingBuffer()
            buffer.append(raw)
            while (true) {
                val frame = buffer.readNextFrame() ?: break
                if (frame.payloadText.isNotBlank()) {
                    results.add(Json.parseToJsonElement(frame.payloadText))
                }
            }
            return results
        }

        suspend fun runAndGetObjects(): List<JsonObject> =
            runAndGetElements().filterIsInstance<JsonObject>()

        companion object {
            fun newServer(): McpServerCore {
                val server = McpServerCore(
                    serverInfo = ServerInfo(name = "test-stdio-server", version = "1.0.0"),
                    capabilities = ServerCapabilities(
                        tools = ToolsCapability(listChanged = false),
                        prompts = PromptsCapability(listChanged = false),
                        resources = ResourcesCapability(subscribe = false, listChanged = false)
                    ),
                    instructions = "Test stdio MCP server"
                )

                // Echo tool — returns the `text` arg back as content.
                server.toolRegistry.registerTool(object : McpTool {
                    override val name = "echo"
                    override val description = "Echoes input text"
                    override val inputSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("text", buildJsonObject { put("type", "string") })
                        })
                    }

                    override suspend fun call(context: ToolCallContext): ToolCallResult {
                        val text = context.params.arguments["text"]?.jsonPrimitive?.contentOrNull
                            ?: "<missing>"
                        return ToolCallResult(content = listOf(ContentItem.Text(text = text)))
                    }
                })

                // Failing tool — exercises tool-error path (isError=true, not a JSON-RPC error).
                server.toolRegistry.registerTool(object : McpTool {
                    override val name = "boom"
                    override val description = "Always throws"
                    override val inputSchema = buildJsonObject { put("type", "object") }

                    override suspend fun call(context: ToolCallContext): ToolCallResult {
                        error("intentional failure")
                    }
                })

                // Resource — returns a fixed body.
                server.resourceRegistry.registerResource(
                    uri = "test://hello",
                    name = "hello",
                    description = "Hello resource",
                    mimeType = "text/plain",
                    contentProvider = { "hello world" }
                )

                // Prompt — returns a tiny one-message prompt.
                server.promptRegistry.registerPrompt(
                    Prompt(name = "greet", description = "A greeting prompt")
                ) {
                    PromptGetResult(
                        description = "greeting",
                        messages = listOf(
                            PromptMessage(
                                role = "user",
                                content = PromptContent.Text(text = "Hello!")
                            )
                        )
                    )
                }

                return server
            }
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun init(id: String = "1") =
        """{"jsonrpc":"2.0","id":"$id","method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"""

    private fun req(id: String, method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","id":"$id","method":"$method","params":$params}"""

    private fun reqNoParams(id: String, method: String) =
        """{"jsonrpc":"2.0","id":"$id","method":"$method"}"""

    private fun notification(method: String, params: String = "{}") =
        """{"jsonrpc":"2.0","method":"$method","params":$params}"""

    // =========================================================================
    // initialize
    // =========================================================================

    @Test
    fun `initialize response has jsonrpc 2dot0`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val resp = h.runAndGetObjects()[0]
        assertEquals("2.0", resp["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize response id matches request id`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init("req-42"))
        val resp = h.runAndGetObjects()[0]
        assertEquals("req-42", resp["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result has protocolVersion`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result has serverInfo with name and version`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val serverInfo = result["serverInfo"] as JsonObject
        assertEquals("test-stdio-server", serverInfo["name"]?.jsonPrimitive?.content)
        assertEquals("1.0.0", serverInfo["version"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize result advertises tools prompts and resources capabilities`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val caps = result["capabilities"] as JsonObject
        assertNotNull(caps["tools"])
        assertNotNull(caps["prompts"])
        assertNotNull(caps["resources"])
    }

    @Test
    fun `initialize result has instructions field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertEquals("Test stdio MCP server", result["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize success has no error field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(init())
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    @Test
    fun `initialize with older protocol version still returns result`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""")
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    @Test
    fun `initialize result protocolVersion is server's version not client's`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}""")
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertEquals(MCP_PROTOCOL_VERSION, result["protocolVersion"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize without params returns -32602 invalid params per MCP lifecycle`() = runTest {
        // MCP 2025-11-25 §Lifecycle/Initialization mandates params (protocolVersion,
        // capabilities, clientInfo). The server now rejects rather than silently
        // succeeding against an uninitialized session.
        val h = StdioHarness()
        h.sendFramed(reqNoParams("1", "initialize"))
        val resp = h.runAndGetObjects()[0]
        val error = resp["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // ping
    // =========================================================================

    @Test
    fun `ping returns empty result object`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertEquals(0, result.size, "ping result must be empty object {}")
    }

    @Test
    fun `ping echoes string id`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("ping-id-99", "ping"))
        assertEquals("ping-id-99", h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ping echoes numeric id`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":42,"method":"ping"}""")
        assertEquals(42, h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `ping has jsonrpc 2dot0`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        assertEquals("2.0", h.runAndGetObjects()[0]["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ping has no error field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    @Test
    fun `ping works before initialize`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size)
        assertNull(responses[0]["error"])
    }

    @Test
    fun `ping with no params field still returns result`() = runTest {
        val h = StdioHarness()
        h.sendFramed(reqNoParams("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    // =========================================================================
    // Unknown / missing method
    // =========================================================================

    @Test
    fun `unknown method returns error code minus32601`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "no/such/method"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `unknown method error has message`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "totally/missing"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertNotNull(error["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `unknown method response has no result field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "nope"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["result"])
        assertNotNull(resp["error"])
    }

    @Test
    fun `unknown method echoes request id`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("err-id", "unknown"))
        assertEquals("err-id", h.runAndGetObjects()[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `missing method field returns invalid request minus32600`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1"}""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // Parse errors
    // =========================================================================

    @Test
    fun `truncated json returns parse error minus32700`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `json with missing closing brace returns parse error`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"id": "1", "method": "ping" """)
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `parse error response has jsonrpc 2dot0`() = runTest {
        val h = StdioHarness()
        h.sendFramed("bad json")
        assertEquals("2.0", h.runAndGetObjects()[0]["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parse error response has null id when id can not be extracted`() = runTest {
        val h = StdioHarness()
        h.sendFramed("not even json")
        val resp = h.runAndGetObjects()[0]
        // Per JSON-RPC spec, id MUST be null on parse-error responses
        assertTrue(resp.containsKey("id"))
        assertEquals("null", resp["id"].toString())
    }

    @Test
    fun `non-object non-array root returns invalid request`() = runTest {
        val h = StdioHarness()
        // "42" is valid JSON but not an object/array
        h.sendFramed("42")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // Notifications — must produce no response
    // =========================================================================

    @Test
    fun `notification without id field produces no response`() = runTest {
        val h = StdioHarness()
        h.sendFramed(notification("notifications/initialized"))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `cancellation notification produces no response`() = runTest {
        val h = StdioHarness()
        h.sendFramed(notification("notifications/cancelled", """{"requestId":"123","reason":"user"}"""))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `progress notification from client produces no response`() = runTest {
        val h = StdioHarness()
        h.sendFramed(notification("notifications/progress", """{"progressToken":"tok","progress":50}"""))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `roots list changed notification produces no response`() = runTest {
        val h = StdioHarness()
        h.sendFramed(notification("notifications/roots/list_changed"))
        assertEquals(0, h.runAndGetObjects().size)
    }

    @Test
    fun `unknown notification produces no response`() = runTest {
        val h = StdioHarness()
        h.sendFramed(notification("notifications/some/totally/made/up/event"))
        assertEquals(0, h.runAndGetObjects().size)
    }

    // =========================================================================
    // tools/list
    // =========================================================================

    @Test
    fun `tools list result has tools array`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/list"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertTrue(result["tools"] is JsonArray)
    }

    @Test
    fun `tools list contains registered tool names`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        val names = tools.map { (it as JsonObject)["name"]?.jsonPrimitive?.content }
        assertTrue("echo" in names)
        assertTrue("boom" in names)
    }

    @Test
    fun `each tool has a name field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        for (tool in tools) {
            assertNotNull((tool as JsonObject)["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `each tool has an inputSchema field of type object`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/list"))
        val tools = (h.runAndGetObjects()[0]["result"] as JsonObject)["tools"] as JsonArray
        for (tool in tools) {
            val schema = (tool as JsonObject)["inputSchema"] as? JsonObject
            assertNotNull(schema, "Tool ${tool["name"]} missing inputSchema")
            assertEquals("object", schema!!["type"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `tools list response has no error field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/list"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    // =========================================================================
    // tools/call
    // =========================================================================

    @Test
    fun `tools call echo returns text content`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"echo","arguments":{"text":"hi"}}"""))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val content = result["content"] as JsonArray
        val first = content[0] as JsonObject
        assertEquals("text", first["type"]?.jsonPrimitive?.content)
        assertEquals("hi", first["text"]?.jsonPrimitive?.content)
        // Successful tool: isError absent or false
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull
        assertTrue(isError == null || isError == "false")
    }

    @Test
    fun `tools call to nonexistent tool returns tool error not protocol error`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"no_such_tool","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        // Tool-not-found is a tool error (isError=true), not a JSON-RPC error.
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertEquals("true", result["isError"]?.jsonPrimitive?.content?.lowercase())
    }

    @Test
    fun `tools call with throwing tool returns isError true with stacktrace`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"boom","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        assertEquals("true", result["isError"]?.jsonPrimitive?.content?.lowercase())
        val content = result["content"] as JsonArray
        assertTrue(content.isNotEmpty())
        val text = (content[0] as JsonObject)["text"]?.jsonPrimitive?.content ?: ""
        assertTrue(text.contains("intentional failure"), "Expected error text to mention failure: $text")
    }

    /**
     * Stdio counterpart to `McpServerIntegrationTest.testExecuteCodeTimeoutReturnsCleanErrorNotHttp500`.
     *
     * The stdio transport has no per-call timeout of its own — the timeout
     * concern belongs to the tool itself (e.g. `ScriptExecutor.withTimeout`
     * inside `steroid_execute_code`). What the transport must guarantee is:
     *
     *  1. A tool that internally cancels via `kotlinx.coroutines.withTimeout`
     *     and catches the resulting `TimeoutCancellationException` to surface
     *     a structured error produces a well-formed JSON-RPC `result` envelope
     *     with `isError=true` — NOT a top-level JSON-RPC `error`.
     *  2. The stdio loop keeps processing subsequent requests on the same
     *     stream after the timed-out call. A follow-up `echo` must answer
     *     normally; if the timeout had crashed the read loop, this frame
     *     would never come back.
     *
     * This mirrors the contract the HTTP test pins via real
     * `steroid_execute_code`. Driving the real tool through stdio would
     * require ij-plugin wiring that doesn't exist (the IDE plugin uses HTTP
     * exclusively); the synthetic tool uses the same `withTimeout(...) {
     * delay(...) }` shape `ScriptExecutor` does internally, so the cancellation
     * code path is the same.
     *
     * **Out of scope on purpose.** A tool that lets `TimeoutCancellationException`
     * (a `CancellationException` subtype) escape would hit
     * `McpStdioServer.dispatch`'s `catch (CancellationException) { throw e }`
     * and tear down the dispatch coroutine instead of producing an `isError`
     * envelope. That is a tool-author contract bug, not a transport contract;
     * any tool wishing to surface a timeout as a structured error must catch
     * it itself, exactly as this test does and exactly as `ScriptExecutor`
     * does in `ij-plugin`.
     */
    @Test
    fun `tools call that internally times out returns isError result and stdio loop survives`() = runTest {
        val h = StdioHarness()
        h.server.toolRegistry.registerTool(object : McpTool {
            override val name = "timeout_simulator"
            override val description = "Internally bounded by withTimeout; surfaces a timeout error result"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult {
                return try {
                    withTimeout(100.milliseconds) {
                        // Cancellation-cooperative — withTimeout's cancellation
                        // propagates here exactly as it does inside ScriptExecutor.
                        delay(60.seconds)
                        ToolCallResult(content = listOf(ContentItem.Text(text = "UNREACHABLE")))
                    }
                } catch (e: TimeoutCancellationException) {
                    // Same shape ScriptExecutor.kt:154 produces: a structured
                    // tool error, not a top-level JSON-RPC error.
                    ToolCallResult.errorResult("Execution timed out after 100 milliseconds")
                }
            }
        })

        // 1) Trigger the timing-out tool. 2) Send a healthy echo to prove the
        // stdio read loop survives. Both frames are queued before run() is
        // entered, so the test exercises the "server processes another
        // message after a timeout" path end-to-end.
        h.sendFramed(req("timeout-call", "tools/call", """{"name":"timeout_simulator","arguments":{}}"""))
        h.sendFramed(req("echo-after", "tools/call", """{"name":"echo","arguments":{"text":"still alive"}}"""))

        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size, "Stdio loop must answer both frames: $responses")

        // The stdio server dispatches tool calls concurrently — the echo
        // (which finishes immediately) may return before the
        // `withTimeout(100.ms) { delay(60.s) }` path inside
        // `timeout_simulator` resolves. Index by `id` instead of position.
        val byId = responses.associateBy { it["id"]?.jsonPrimitive?.content }

        // (1) Timed-out call: well-formed JSON-RPC result, isError=true, no top-level error.
        val timeoutResp = byId["timeout-call"] ?: error("missing response for timeout-call: $responses")
        assertNull(timeoutResp["error"], "Timeout must NOT be a protocol-level JSON-RPC error")
        val timeoutResult = timeoutResp["result"] as JsonObject
        assertEquals(
            "true",
            timeoutResult["isError"]?.jsonPrimitive?.content?.lowercase(),
            "Timed-out tool must mark the result as isError=true",
        )
        val timeoutText = ((timeoutResult["content"] as JsonArray)[0] as JsonObject)["text"]
            ?.jsonPrimitive?.content ?: ""
        assertTrue(
            timeoutText.contains("timed out", ignoreCase = true) ||
                timeoutText.contains("timeout", ignoreCase = true),
            "Error content must name the failure mode (got: $timeoutText)",
        )

        // (2) Follow-up echo: stdio loop survived; healthy tool answers normally.
        val echoResp = byId["echo-after"] ?: error("missing response for echo-after: $responses")
        assertNull(echoResp["error"])
        val echoResult = echoResp["result"] as JsonObject
        val echoIsError = echoResult["isError"]?.jsonPrimitive?.contentOrNull
        assertTrue(echoIsError == null || echoIsError == "false", "Healthy echo must not be marked isError")
        val echoText = ((echoResult["content"] as JsonArray)[0] as JsonObject)["text"]
            ?.jsonPrimitive?.content
        assertEquals("still alive", echoText, "Echo must complete despite the timed-out call")
    }

    @Test
    fun `tools call without name parameter returns -32602 invalid params`() = runTest {
        // MCP 2025-11-25 ToolCallRequestParams.name is required. Missing the field is a
        // protocol error (-32602), not a tool-error result (isError=true).
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/call", """{"arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        val error = resp["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
        assertEquals("1", resp["id"]?.jsonPrimitive?.content, "id must be echoed even on protocol error")
    }

    @Test
    fun `tools call with missing params returns invalid params minus32602`() = runTest {
        val h = StdioHarness()
        h.sendFramed(reqNoParams("1", "tools/call"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `tools call with empty arguments object works`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "tools/call", """{"name":"echo","arguments":{}}"""))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val result = resp["result"] as JsonObject
        // Echo tool returns "<missing>" when text arg is absent, but the call should succeed.
        assertNotNull(result["content"])
    }

    // =========================================================================
    // resources/list
    // =========================================================================

    @Test
    fun `resources list result has resources array`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/list"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertTrue(result["resources"] is JsonArray)
    }

    @Test
    fun `resources list contains registered resource`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/list"))
        val resources = (h.runAndGetObjects()[0]["result"] as JsonObject)["resources"] as JsonArray
        val uris = resources.map { (it as JsonObject)["uri"]?.jsonPrimitive?.content }
        assertTrue("test://hello" in uris)
    }

    @Test
    fun `resources list response has no error field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/list"))
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
    }

    // =========================================================================
    // resources/read
    // =========================================================================

    @Test
    fun `resources read returns body for known uri`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/read", """{"uri":"test://hello"}"""))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val contents = result["contents"] as JsonArray
        assertTrue(contents.isNotEmpty())
        val first = contents[0] as JsonObject
        assertEquals("hello world", first["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `resources read with missing uri returns minus32602`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/read", """{}"""))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `resources read with unknown uri returns minus32002 RESOURCE_NOT_FOUND`() = runTest {
        // MCP 2025-11-25 §Resources/Error-Handling assigns -32002 (server-error range)
        // to "Resource not found", distinct from generic -32602 invalid params.
        val h = StdioHarness()
        h.sendFramed(req("1", "resources/read", """{"uri":"test://nonexistent"}"""))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.RESOURCE_NOT_FOUND, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `resources read with no params returns minus32602`() = runTest {
        val h = StdioHarness()
        h.sendFramed(reqNoParams("1", "resources/read"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // prompts/list
    // =========================================================================

    @Test
    fun `prompts list result has prompts array`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "prompts/list"))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        assertTrue(result["prompts"] is JsonArray)
    }

    @Test
    fun `prompts list contains registered prompt`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "prompts/list"))
        val prompts = (h.runAndGetObjects()[0]["result"] as JsonObject)["prompts"] as JsonArray
        val names = prompts.map { (it as JsonObject)["name"]?.jsonPrimitive?.content }
        assertTrue("greet" in names)
    }

    // =========================================================================
    // prompts/get
    // =========================================================================

    @Test
    fun `prompts get returns messages for known prompt`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "prompts/get", """{"name":"greet"}"""))
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val messages = result["messages"] as JsonArray
        assertEquals(1, messages.size)
    }

    @Test
    fun `prompts get with unknown name returns minus32602`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "prompts/get", """{"name":"unknown_prompt"}"""))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `prompts get with no params returns minus32602`() = runTest {
        val h = StdioHarness()
        h.sendFramed(reqNoParams("1", "prompts/get"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error["code"]?.jsonPrimitive?.int)
    }

    // =========================================================================
    // Response structure invariants
    // =========================================================================

    @Test
    fun `all responses have jsonrpc field equal to 2dot0`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "unknown/method"))
        h.sendFramed("bad json")
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        for (resp in responses) {
            assertEquals("2.0", resp["jsonrpc"]?.jsonPrimitive?.content,
                "Expected jsonrpc=2.0 in: $resp")
        }
    }

    @Test
    fun `success response has result and no error`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    @Test
    fun `error response has error and no result`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "no/such/method"))
        val resp = h.runAndGetObjects()[0]
        assertNotNull(resp["error"])
        assertNull(resp["result"])
    }

    @Test
    fun `error code is an integer`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "unknown"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        val code = error["code"]?.jsonPrimitive?.int
        assertNotNull(code)
    }

    @Test
    fun `error object has message string field`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "unknown"))
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertNotNull(error["message"]?.jsonPrimitive?.content)
    }

    // =========================================================================
    // Multiple sequential requests
    // =========================================================================

    @Test
    fun `three sequential pings return three responses with matching ids`() = runTest {
        // Non-initialize dispatch is concurrent (commit 584143ba), so wire-order is
        // not guaranteed even for fast handlers. Assert by id-set instead.
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "ping"))
        h.sendFramed(req("3", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        val ids = responses.mapNotNull { it["id"]?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf("1", "2", "3"), ids)
    }

    @Test
    fun `mixed request types in sequence all get responses`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "tools/list"))
        h.sendFramed(req("3", "resources/list"))
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size)
        assertNull(responses[0]["error"])
        assertNull(responses[1]["error"])
        assertNull(responses[2]["error"])
    }

    @Test
    fun `notifications interspersed with requests do not add extra responses`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        h.sendFramed(notification("notifications/initialized"))
        h.sendFramed(req("2", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
    }

    @Test
    fun `empty input produces no output`() = runTest {
        val h = StdioHarness()
        assertEquals(0, h.runAndGetObjects().size)
    }

    // =========================================================================
    // Batch requests (JSON array)
    // =========================================================================

    @Test
    fun `batch with two pings returns array with two responses`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"ping"}]""")
        val elements = h.runAndGetElements()
        assertEquals(1, elements.size)
        val batch = elements[0] as JsonArray
        assertEquals(2, batch.size)
    }

    @Test
    fun `batch responses all have jsonrpc 2dot0`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"ping"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        for (item in batch) {
            assertEquals("2.0", (item as JsonObject)["jsonrpc"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `batch with mixed methods returns responses for requests only`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","id":"2","method":"tools/list"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        assertEquals(2, batch.size)
        val ids = batch.map { (it as JsonObject)["id"]?.jsonPrimitive?.content }.toSet()
        assertTrue("1" in ids)
        assertTrue("2" in ids)
    }

    @Test
    fun `batch with valid and invalid items returns one error per invalid`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},42,{"jsonrpc":"2.0","id":"3","method":"ping"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        assertEquals(3, batch.size, "Each item — including invalid scalars — gets an error response")
        val invalidResponse = batch.find { (it as JsonObject)["error"] != null } as? JsonObject
        assertNotNull(invalidResponse)
    }

    // =========================================================================
    // Framing mode — framed input → framed output
    // =========================================================================

    @Test
    fun `framed input produces framed output with Content-Length header`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("Content-Length:"), "Expected Content-Length header in: $raw")
    }

    @Test
    fun `framed output Content-Length matches body byte length`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val raw = h.runRaw().toString(Charsets.UTF_8)
        // Parse the header to extract advertised length, then compare to actual body bytes.
        val sep = "\r\n\r\n"
        val headerEnd = raw.indexOf(sep)
        assertTrue(headerEnd > 0)
        val advertised = raw.substring("Content-Length:".length, headerEnd).trim().toInt()
        val body = raw.substring(headerEnd + sep.length)
        assertEquals(advertised, body.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `framed output round-trips through FramingBuffer`() = runTest {
        val h = StdioHarness()
        h.sendFramed(req("1", "ping"))
        val raw = h.runRaw()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frame = buffer.readNextFrame()
        assertNotNull(frame)
        assertEquals("framed", frame!!.mode)
    }

    // =========================================================================
    // Framing mode — NDJSON input → NDJSON output
    // =========================================================================

    @Test
    fun `ndjson input produces ndjson output without Content-Length header`() = runTest {
        val h = StdioHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertFalse(raw.contains("Content-Length"), "NDJSON output must not have Content-Length header")
        assertTrue(raw.endsWith("\n"), "NDJSON output must end with newline")
    }

    @Test
    fun `ndjson output is valid JSON followed by newline`() = runTest {
        val h = StdioHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        val withoutTrailingNewline = raw.trimEnd('\n', '\r')
        val parsed = Json.parseToJsonElement(withoutTrailingNewline)
        assertTrue(parsed is JsonObject)
        assertEquals("2.0", parsed.jsonObject["jsonrpc"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ndjson output round-trips through FramingBuffer`() = runTest {
        val h = StdioHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        val raw = h.runRaw()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frame = buffer.readNextFrame()
        assertNotNull(frame)
        assertEquals("ndjson", frame!!.mode)
    }

    @Test
    fun `output mode stays NDJSON when all inbound frames are NDJSON`() = runTest {
        val h = StdioHarness()
        h.sendNdjson("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        h.sendNdjson("""{"jsonrpc":"2.0","id":"2","method":"ping"}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertFalse(raw.contains("Content-Length"),
            "Output must stay NDJSON when peer is NDJSON throughout")
    }

    // =========================================================================
    // UTF-8 / multibyte payloads
    // =========================================================================

    @Test
    fun `utf8 multibyte payload round-trips through framed mode`() = runTest {
        val h = StdioHarness()
        // 사용자 (Korean), 🚀 (emoji), Ω (Greek) — all multi-byte in UTF-8.
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"echo","arguments":{"text":"사용자 🚀 Ω"}}}""")
        val result = h.runAndGetObjects()[0]["result"] as JsonObject
        val content = result["content"] as JsonArray
        val text = (content[0] as JsonObject)["text"]?.jsonPrimitive?.content
        assertEquals("사용자 🚀 Ω", text)
    }

    @Test
    fun `framed Content-Length counts bytes not characters for multibyte payload`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"echo","arguments":{"text":"🚀🚀🚀"}}}""")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        val sep = "\r\n\r\n"
        val headerEnd = raw.indexOf(sep)
        assertTrue(headerEnd > 0)
        val advertised = raw.substring("Content-Length:".length, headerEnd).trim().toInt()
        val body = raw.substring(headerEnd + sep.length)
        assertEquals(advertised, body.toByteArray(Charsets.UTF_8).size,
            "Content-Length must equal UTF-8 byte length, not char count")
    }

    // =========================================================================
    // Partial/multi-frame reads — exercise FramingBuffer streaming path
    // =========================================================================

    @Test
    fun `single read containing two framed messages produces two responses`() = runTest {
        val h = StdioHarness()
        // Both frames written to the same input buffer in one shot.
        h.sendFramed(req("1", "ping"))
        h.sendFramed(req("2", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
        // Non-initialize requests are dispatched as parallel child coroutines
        // (see McpStdioServer.readLoop) — the server is explicitly allowed to
        // emit responses in either order, and the client correlates by `id`
        // per JSON-RPC 2.0. On Linux+Mac the local scheduler happens to keep
        // submission order; on Windows TC agents the order can flip
        // (observed on TC build 958838055). Assert the set of returned ids
        // matches the set sent, not the per-position correspondence.
        val responseIds = responses.mapNotNull { it["id"]?.jsonPrimitive?.content }.toSet()
        assertEquals(setOf("1", "2"), responseIds)
    }

    @Test
    fun `framed message split across multiple bytes is reassembled`() = runTest {
        // Build a single Content-Length frame, then feed it byte-by-byte. The reader
        // calls `read(buf, off, len)` on an 8KB buffer, so we override **that** method
        // (returning 1) — overriding the no-arg `read()` would leave the bulk-read path
        // backed by the default impl, which doesn't honour our pacing.
        val payload = req("1", "ping")
        val framed = encodeFramedMessage(payload)
        val bytes = framed.toByteArray(Charsets.UTF_8)
        val outputBuffer = ByteArrayOutputStream()
        val server = StdioHarness.newServer()
        val driblet = object : java.io.InputStream() {
            var pos = 0
            override fun read(): Int = if (pos >= bytes.size) -1 else (bytes[pos++].toInt() and 0xff)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                b[off] = bytes[pos++]
                return 1
            }
        }
        McpStdioServer(server, driblet, outputBuffer).run()

        val raw = outputBuffer.toByteArray()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frame = buffer.readNextFrame()
        assertNotNull(frame)
        val resp = Json.parseToJsonElement(frame!!.payloadText) as JsonObject
        assertEquals("1", resp["id"]?.jsonPrimitive?.content)
        assertNull(resp["error"])
    }

    // =========================================================================
    // Server-initiated notifications via session
    // =========================================================================

    @Test
    fun `session sendNotification appears on stdout as JSON-RPC notification`() = runTest {
        // Custom server that triggers a notification when a tool is called.
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "notif-test", version = "1.0"),
            capabilities = ServerCapabilities(tools = ToolsCapability())
        )
        server.toolRegistry.registerTool(object : McpTool {
            override val name = "notify"
            override val description = "Sends a notification then returns OK"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult {
                context.session.sendNotification(
                    JsonRpcNotification(
                        method = "notifications/message",
                        params = buildJsonObject { put("hello", "world") }
                    )
                )
                return ToolCallResult(content = listOf(ContentItem.Text(text = "ok")))
            }
        })

        val inputBytes = ByteArrayOutputStream()
        inputBytes.write(encodeFramedMessage(req("1", "tools/call", """{"name":"notify","arguments":{}}""")).toByteArray(Charsets.UTF_8))

        val outputBuffer = ByteArrayOutputStream()
        McpStdioServer(server, ByteArrayInputStream(inputBytes.toByteArray()), outputBuffer).run()

        val raw = outputBuffer.toByteArray()
        val buffer = FramingBuffer()
        buffer.append(raw)
        val frames = mutableListOf<JsonObject>()
        while (true) {
            val frame = buffer.readNextFrame() ?: break
            frames.add(Json.parseToJsonElement(frame.payloadText) as JsonObject)
        }

        val notification = frames.find { it["method"]?.jsonPrimitive?.content == "notifications/message" }
        assertNotNull(notification, "Server-sent notification must appear on stdout. Got: $frames")
        // Notification has no `id` per JSON-RPC 2.0
        assertNull(notification!!["id"])
        assertEquals("2.0", notification["jsonrpc"]?.jsonPrimitive?.content)

        val response = frames.find { it["id"]?.jsonPrimitive?.content == "1" }
        assertNotNull(response, "Tool-call response must also appear on stdout")
    }

    // =========================================================================
    // Edge cases from MCP SDK references (TypeScript / Python / Rust)
    // =========================================================================

    // ---- ID echo invariants ------------------------------------------------

    @Test
    fun `numeric id 0 is preserved (not coerced to null or omitted)`() = runTest {
        // TS SDK regression: `if (msg.id)` falsy-check breaks id=0.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":0,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(0, resp["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `negative id is preserved`() = runTest {
        // rmcp: NumberOrString must accept negatives.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":-1,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(-1, resp["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `large i64 id is preserved without truncation`() = runTest {
        // rmcp: Long.MAX_VALUE; some JSON libs decode large ints as Double, losing precision.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":9223372036854775807,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(9223372036854775807L, resp["id"]?.jsonPrimitive?.long)
    }

    @Test
    fun `numeric id 42 is echoed as number not string`() = runTest {
        // Python SDK regression: id type-coercion breaks client correlation.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":42,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        // jsonPrimitive.int succeeds only if id is JSON number, not "42".
        assertEquals(42, resp["id"]?.jsonPrimitive?.int)
        // And the wire form should not be quoted — assert by re-encoding.
        val raw = Json.encodeToString(JsonObject.serializer(), resp)
        assertTrue(raw.contains("\"id\":42"), "Numeric id must serialize unquoted: $raw")
    }

    // ---- Notifications ------------------------------------------------------

    @Test
    fun `unknown notification with arbitrary params produces no response and no error`() = runTest {
        // TS SDK: Notification handlers absent → silently ignored, NO method-not-found error
        // (since notifications never produce errors).
        val h = StdioHarness()
        h.sendFramed(notification("notifications/never-heard-of", """{"random":42}"""))
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        // Only the ping after the notification gets a response.
        assertEquals(1, responses.size)
        assertEquals("after", responses[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancelled notification with unknown requestId is silently ignored`() = runTest {
        // rmcp + Python + TS: cancellation for unknown id never errors (race tolerance).
        val h = StdioHarness()
        h.sendFramed(notification("notifications/cancelled", """{"requestId":"never-existed","reason":"x"}"""))
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size, "Server must keep running after cancel-of-unknown-id")
    }

    // ---- Batch ---------------------------------------------------------------

    @Test
    fun `empty batch array returns single -32600 error response per JSON-RPC 2dot0 section 6`() = runTest {
        // JSON-RPC 2.0 §6: empty array MUST yield a single Invalid Request error response,
        // not an empty array. The id MUST be null because no request id was extractable.
        val h = StdioHarness()
        h.sendFramed("[]")
        val elements = h.runAndGetElements()
        assertEquals(1, elements.size)
        val resp = elements[0] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("null", resp["id"].toString())
    }

    @Test
    fun `all-notifications batch produces no output`() = runTest {
        // JSON-RPC 2.0 §6: if every entry is a notification, the response MUST be omitted entirely.
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":"x"}}]""")
        assertEquals(0, h.runAndGetElements().size)
    }

    @Test
    fun `batch with request and notification returns single-element array`() = runTest {
        // The notification must NOT contribute a response slot.
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"1","method":"ping"},{"jsonrpc":"2.0","method":"notifications/initialized"}]""")
        val elements = h.runAndGetElements()
        assertEquals(1, elements.size)
        val batch = elements[0] as JsonArray
        assertEquals(1, batch.size, "Notification entries do not produce response array slots")
    }

    @Test
    fun `batch with duplicate ids produces two responses both echoing that id`() = runTest {
        // Spec doesn't dedup — both processed.
        val h = StdioHarness()
        h.sendFramed("""[{"jsonrpc":"2.0","id":"5","method":"ping"},{"jsonrpc":"2.0","id":"5","method":"ping"}]""")
        val batch = h.runAndGetElements()[0] as JsonArray
        assertEquals(2, batch.size)
        for (item in batch) {
            assertEquals("5", (item as JsonObject)["id"]?.jsonPrimitive?.content)
        }
    }

    // ---- Parse error recovery -----------------------------------------------

    @Test
    fun `parse error does not terminate the read loop`() = runTest {
        // rmcp + Python regressions: a bad frame must NOT kill the dispatcher.
        val h = StdioHarness()
        h.sendFramed("not valid json")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size, "Server must keep reading after parse error")
        // First is the parse error (-32700), second is the ping response.
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, (responses[0]["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("after", responses[1]["id"]?.jsonPrimitive?.content)
        assertNull(responses[1]["error"])
    }

    @Test
    fun `multiple sequential parse errors each get own response`() = runTest {
        // Python: 10 concurrent malformed requests; each gets -32700 / -32600, server stays alive.
        val h = StdioHarness()
        h.sendFramed("garbage1")
        h.sendFramed("garbage2")
        h.sendFramed("garbage3")
        h.sendFramed(req("ok", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(4, responses.size)
        // Three parse errors + one valid response.
        assertEquals(3, responses.count { it["error"] != null })
        assertEquals(1, responses.count { it["result"] != null })
    }

    // ---- Unparsable (non-JSON) input recovery — jonnyzzz/mcp-steroid#461 -----

    @Test
    fun `garbage line before initialize gets a parse error and initialize is still answered`() = runTest {
        // #461: one non-JSON line on stdin used to jam the framing buffer forever — the
        // `initialize` behind it was never parsed, nothing was written, and the process
        // exited 0. The line must produce -32700 and the session must survive it.
        val h = StdioHarness()
        h.sendRaw("this is not json\n")
        h.sendNdjson(init("1"))

        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size, "expected a parse error plus the initialize result: $responses")
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, (responses[0]["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("1", responses[1]["id"]?.jsonPrimitive?.content)
        assertNotNull(responses[1]["result"], "initialize must still be answered: ${responses[1]}")
    }

    @Test
    fun `the parse error for an unframed garbage line is itself ndjson`() = runTest {
        // Nothing has locked the output mode yet, and MCP stdio is NDJSON — the error
        // must not go out with a Content-Length header a spec-only client can't read.
        val h = StdioHarness()
        h.sendRaw("this is not json\n")
        val raw = h.runRaw().toString(Charsets.UTF_8)
        assertTrue(raw.startsWith("{"), "parse error must be a bare NDJSON line: $raw")
        assertTrue(raw.endsWith("\n"), "NDJSON frames are newline-terminated: $raw")
    }

    @Test
    fun `garbage between two valid frames is reported without dropping either`() = runTest {
        val h = StdioHarness()
        h.sendNdjson(req("before", "ping"))
        h.sendRaw("2026-08-07 INFO wrapper script says hello\n")
        h.sendNdjson(req("after", "ping"))

        // Dispatch of non-initialize requests is concurrent, so assert on the set of
        // responses, not their order.
        val responses = h.runAndGetObjects()
        assertEquals(3, responses.size, "both pings plus one parse error: $responses")
        assertEquals(
            setOf("before", "after"),
            responses.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }.toSet(),
            "the frames on either side of the garbage must both be answered: $responses",
        )
        val errors = responses.filter { it["error"] != null }
        assertEquals(1, errors.size, "exactly one parse error: $responses")
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, (errors[0]["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `unterminated garbage at EOF still gets a parse error`() = runTest {
        // No trailing newline: the line can only be judged once stdin ends.
        val h = StdioHarness()
        h.sendRaw("this is not json")
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size, "EOF residue must be reported, not silently dropped: $responses")
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, (responses[0]["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a framed body truncated by EOF gets a parse error`() = runTest {
        // A client that dies mid-frame leaves a legal header with a short body. That is
        // evidence worth reporting, not a clean shutdown.
        val h = StdioHarness()
        h.sendRaw("Content-Length: 200\r\n\r\n{\"jsonrpc\":\"2.0\"")
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size, "truncated final frame must be reported: $responses")
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, (responses[0]["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `a trailing newline after the last frame is not a parse error`() = runTest {
        // Stray line breaks between frames are noise, not protocol errors.
        val h = StdioHarness()
        h.sendNdjson(req("1", "ping"))
        h.sendRaw("\n\n")
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size, "blank lines must not produce parse errors: $responses")
        assertNull(responses[0]["error"])
    }

    // ---- params shape -------------------------------------------------------

    @Test
    fun `params can be null`() = runTest {
        // Python SDK: some clients send "params":null defensively.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"ping","params":null}""")
        val resp = h.runAndGetObjects()[0]
        // Either succeed or return invalid-params; both acceptable. Must not crash.
        assertTrue(resp["result"] != null || resp["error"] != null)
    }

    // ---- Stdout cleanliness -------------------------------------------------

    @Test
    fun `no bytes written to stdout before any input arrives`() = runTest {
        // Python SDK invariant: stdout is sacred for JSON-RPC; logs go to stderr.
        // Empty input should result in zero output bytes.
        val h = StdioHarness()
        val raw = h.runRaw()
        assertEquals(0, raw.size, "Idle server must not emit anything to stdout")
    }

    // ---- ID echo across mixed responses -------------------------------------

    @Test
    fun `ids from interleaved string and numeric requests are not swapped`() = runTest {
        // Bidirectional id type tracking — a server keying by `id.toString()` would alias these.
        // Wire-order isn't guaranteed under concurrent dispatch (commit 584143ba), so we
        // identify each response by its id type, not its position in the output stream.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"ping"}""")
        h.sendFramed("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
        val stringResp = responses.firstOrNull { it["id"]?.jsonPrimitive?.isString == true }
        val numericResp = responses.firstOrNull { it["id"]?.jsonPrimitive?.isString == false }
        assertNotNull(stringResp, "Must have one response with a string id")
        assertNotNull(numericResp, "Must have one response with a numeric id")
        assertEquals("1", stringResp!!["id"]?.jsonPrimitive?.content)
        assertEquals(1, numericResp!!["id"]?.jsonPrimitive?.int)
    }

    // ---- Tool-call concurrency ---------------------------------------------

    @Test
    fun `two echo calls in sequence both round-trip correct text`() = runTest {
        // Confirms id-correlation under sequential dispatch.
        val h = StdioHarness()
        h.sendFramed(req("a", "tools/call", """{"name":"echo","arguments":{"text":"first"}}"""))
        h.sendFramed(req("b", "tools/call", """{"name":"echo","arguments":{"text":"second"}}"""))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
        val byId = responses.associateBy { it["id"]?.jsonPrimitive?.content }
        val firstText = ((byId["a"]?.get("result") as JsonObject)["content"] as JsonArray)
            .let { (it[0] as JsonObject)["text"]?.jsonPrimitive?.content }
        val secondText = ((byId["b"]?.get("result") as JsonObject)["content"] as JsonArray)
            .let { (it[0] as JsonObject)["text"]?.jsonPrimitive?.content }
        assertEquals("first", firstText)
        assertEquals("second", secondText)
    }

    // ---- Round-2 review fixes: JSON-RPC envelope validation ---------------

    @Test
    fun `request missing jsonrpc field returns -32600 invalid request`() = runTest {
        // JSON-RPC 2.0 §4: jsonrpc MUST be present and equal to "2.0".
        val h = StdioHarness()
        h.sendFramed("""{"id":"1","method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        val error = resp["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with jsonrpc 1dot0 returns -32600 invalid request`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"1.0","id":"1","method":"ping"}""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with non-string jsonrpc returns -32600`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":2.0,"id":"1","method":"ping"}""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with boolean id returns -32600 with id=null`() = runTest {
        // JSON-RPC 2.0 §4: id MUST be String, Number, or Null.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":true,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        val error = resp["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
        assertEquals("null", resp["id"].toString(),
            "Server cannot trust a structurally-invalid id, so the response id MUST be null")
    }

    @Test
    fun `request with object id returns -32600 with id=null`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":{"x":1},"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("null", resp["id"].toString())
    }

    @Test
    fun `request with array id returns -32600 with id=null`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":[1,2],"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("null", resp["id"].toString())
    }

    @Test
    fun `request with non-string method returns -32600 echoing original id`() = runTest {
        // method MUST be a string. method=true is not "ping" coerced to "true".
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":true}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
        assertEquals("1", resp["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `request with numeric method returns -32600`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":42}""")
        val error = h.runAndGetObjects()[0]["error"] as JsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, error["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with array params returns -32600`() = runTest {
        // MCP only uses object params. Per JSON-RPC 2.0 §4.2 params can be Array or
        // Object generally, but our server rejects arrays as Invalid Request.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"ping","params":[1,2]}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with primitive params returns -32600`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"ping","params":42}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, (resp["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with params=null is accepted as missing params`() = runTest {
        // null is the only non-object/non-array value accepted; treated as "no params".
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"ping","params":null}""")
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        assertNotNull(resp["result"])
    }

    @Test
    fun `notification with non-string method is silently ignored`() = runTest {
        // Notifications never get error responses, even when malformed.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","method":42}""")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size, "Only the trailing ping should produce a response")
        assertEquals("after", responses[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `notification missing method is silently ignored`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0"}""")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(1, responses.size)
    }

    @Test
    fun `request with large positive Long id is preserved without precision loss`() = runTest {
        // Long.MAX_VALUE is 9223372036854775807 — JSON libraries that decode large ints
        // as Double would lose the trailing digits.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":9223372036854775807,"method":"ping"}""")
        val resp = h.runAndGetObjects()[0]
        assertEquals(9223372036854775807L, resp["id"]?.jsonPrimitive?.long)
    }

    // ---- Round-4 review fixes: concurrent dispatch races + EOF cleanup ---

    @Test
    fun `EOF returns promptly even if a tool is suspended in sampling`() = runTest {
        // Round-4 finding: an in-flight `sampling/createMessage` parked on
        // session.sendRequest's Deferred would otherwise block run() until the
        // 60s sampling timeout fires after stdin EOF. Closing the session in run()'s
        // finally cancels pending Deferreds first, so the suspended dispatch resumes
        // with CancellationException and the scope exits.
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "sampling-test", version = "1.0"),
            capabilities = ServerCapabilities(tools = ToolsCapability())
        )
        server.toolRegistry.registerTool(object : McpTool {
            override val name = "ask"
            override val description = "calls sampling and returns the text"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult {
                // The harness's session has no sampling capability set, so this returns
                // null fast. To exercise the long-wait path, force a request anyway:
                val r = context.session.sendRequest(
                    method = "sampling/createMessage",
                    params = buildJsonObject { put("messages", buildJsonArray { }) },
                    timeout = 60.seconds
                )
                return ToolCallResult(content = listOf(ContentItem.Text(text = r?.toString() ?: "null")))
            }
        })

        val inputBytes = encodeFramedMessage(
            """{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"ask","arguments":{}}}"""
        ).toByteArray(Charsets.UTF_8)

        val outputBuffer = ByteArrayOutputStream()
        val started = System.currentTimeMillis()
        // No virtual-time tricks; runTest+Dispatchers.IO is real wall time. If this
        // test takes anywhere near 60s the cancel path is broken.
        runBlocking {
            McpStdioServer(server, ByteArrayInputStream(inputBytes), outputBuffer).run()
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue(elapsed < 5_000,
            "run() must return within seconds of EOF even with a suspended sampling request, took ${elapsed}ms")
    }

    @Test
    fun `initialize completes before subsequent requests in the same buffer see session state`() = runTest {
        // Round-4 finding: a single read containing [initialize, sampling-using-tool]
        // could race because dispatches are launched concurrently. The server now
        // detects `initialize` at the wire level and dispatches it synchronously, so
        // markInitialized has run before the next dispatch starts.
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "init-race-test", version = "1.0"),
            capabilities = ServerCapabilities(tools = ToolsCapability())
        )
        server.toolRegistry.registerTool(object : McpTool {
            override val name = "supports_sampling"
            override val description = "reports whether session.supportsSampling() is true"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult =
                ToolCallResult(content = listOf(ContentItem.Text(
                    text = context.session.supportsSampling().toString()
                )))
        })

        val initFrame = """{"jsonrpc":"2.0","id":"init","method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{"sampling":{}},"clientInfo":{"name":"t","version":"1"}}}"""
        val callFrame = """{"jsonrpc":"2.0","id":"call","method":"tools/call","params":{"name":"supports_sampling","arguments":{}}}"""
        val combined = encodeFramedMessage(initFrame) + encodeFramedMessage(callFrame)
        val inputBytes = combined.toByteArray(Charsets.UTF_8)

        val outputBuffer = ByteArrayOutputStream()
        runBlocking {
            McpStdioServer(server, ByteArrayInputStream(inputBytes), outputBuffer).run()
        }

        val buffer = FramingBuffer()
        buffer.append(outputBuffer.toByteArray())
        val responses = mutableListOf<JsonObject>()
        while (true) {
            val frame = buffer.readNextFrame() ?: break
            responses += Json.parseToJsonElement(frame.payloadText) as JsonObject
        }
        val callResp = responses.first { it["id"]?.jsonPrimitive?.content == "call" }
        val text = ((callResp["result"] as JsonObject)["content"] as JsonArray)
            .let { (it[0] as JsonObject)["text"]?.jsonPrimitive?.content }
        assertEquals("true", text,
            "Tool dispatched after initialize MUST observe the client's sampling capability")
    }

    @Test
    fun `notification emitted before any inbound input uses NDJSON framing`() = runTest {
        // Round-4 finding: outgoing pumps started before the first inbound frame would
        // default to Content-Length, corrupting a spec-only NDJSON peer. Default is
        // now NDJSON to match the MCP 2025-11-25 stdio transport spec.
        //
        // Round-6 fix: hold stdin open via a piped stream so we don't EOF before the
        // pump has had a chance to write the notification. We close the pipe only AFTER
        // we've had time to write, so the assertion is deterministic — empty output
        // here would be a real bug, not a race.
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "early-notify", version = "1.0"),
            capabilities = ServerCapabilities()
        )

        val piped = PipedOutputStream()
        val pipedIn = PipedInputStream(piped, 1024)
        val outputBuffer = ByteArrayOutputStream()
        runBlocking<Unit> {
            coroutineScope {
                launch {
                    while (server.sessionManager.getSessionCount() == 0) {
                        delay(5)
                    }
                    val session = server.sessionManager.getAllSessions().single()
                    session.sendNotification(
                        JsonRpcNotification(
                            method = "notifications/early",
                            params = buildJsonObject { put("when", "before-input") }
                        )
                    )
                    // Give the pump time to drain before we EOF the read side.
                    delay(100)
                    piped.close()
                }
                McpStdioServer(server, pipedIn, outputBuffer).run()
            }
        }

        val raw = outputBuffer.toByteArray().toString(Charsets.UTF_8)
        assertTrue(raw.isNotEmpty(), "Pump must have written the notification before EOF")
        assertFalse(raw.contains("Content-Length:"),
            "Pre-input notifications must use NDJSON framing per MCP 2025-11-25, got: $raw")
        assertTrue(raw.contains("notifications/early"))
    }

    // ---- Round-5 review fixes: parse-tolerant initialize peek + spec gaps -

    @Test
    fun `request with object method does not crash isInitializeRequest peek`() = runTest {
        // Round-5 finding: the wire-level initialize-detection peek used jsonPrimitive
        // unguarded; method:{...} would throw IllegalArgumentException and abort the
        // read loop instead of returning a -32600 to the client.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":{"x":1}}""")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size, "Loop must keep running after malformed method")
        val first = responses.first { it["id"]?.jsonPrimitive?.content == "1" }
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST,
            (first["error"] as JsonObject)["code"]?.jsonPrimitive?.int)
    }

    @Test
    fun `request with array method does not crash isInitializeRequest peek`() = runTest {
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":["initialize"]}""")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        assertEquals(2, responses.size)
    }

    @Test
    fun `stray response with unknown id is silently consumed`() = runTest {
        // JSON-RPC §5: a response targeting an id we never sent has no remediation
        // path. Consuming it (no outbound error) is the standard choice; the
        // alternative would risk response loops between misbehaving peers.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"server-999","result":{"role":"assistant","content":{"type":"text","text":"ghost"}}}""")
        h.sendFramed(req("after", "ping"))
        val responses = h.runAndGetObjects()
        // Only the trailing ping should produce a response.
        assertEquals(1, responses.size)
        assertEquals("after", responses[0]["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize notification without id does not mark session initialized`() = runTest {
        // Per JSON-RPC §4.1, a notification (no id) carries no request semantics.
        // Tools that read `session.initialized` MUST still see it as false even after
        // an `initialize`-method notification arrives.
        val server = McpServerCore(
            serverInfo = ServerInfo(name = "init-notification-test", version = "1.0"),
            capabilities = ServerCapabilities(tools = ToolsCapability())
        )
        server.toolRegistry.registerTool(object : McpTool {
            override val name = "is_initialized"
            override val description = "reports session.initialized"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult =
                ToolCallResult(content = listOf(ContentItem.Text(
                    text = context.session.initialized.toString()
                )))
        })

        val notif = """{"jsonrpc":"2.0","method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}"""
        val callFrame = """{"jsonrpc":"2.0","id":"call","method":"tools/call","params":{"name":"is_initialized","arguments":{}}}"""
        val combined = encodeFramedMessage(notif) + encodeFramedMessage(callFrame)
        val outputBuffer = ByteArrayOutputStream()
        runBlocking<Unit> {
            McpStdioServer(server, ByteArrayInputStream(combined.toByteArray(Charsets.UTF_8)), outputBuffer).run()
        }

        val buffer = FramingBuffer()
        buffer.append(outputBuffer.toByteArray())
        val responses = mutableListOf<JsonObject>()
        while (true) {
            val frame = buffer.readNextFrame() ?: break
            responses += Json.parseToJsonElement(frame.payloadText) as JsonObject
        }
        val callResp = responses.first { it["id"]?.jsonPrimitive?.content == "call" }
        val text = ((callResp["result"] as JsonObject)["content"] as JsonArray)
            .let { (it[0] as JsonObject)["text"]?.jsonPrimitive?.content }
        assertEquals("false", text,
            "An initialize notification (no id) MUST NOT mark the session initialized")
    }

    // ---- _meta passthrough on tool args -----------------------------------

    @Test
    fun `tools call with _meta progressToken is accepted`() = runTest {
        // _meta is reserved for transport metadata; must not break the schema.
        val h = StdioHarness()
        h.sendFramed("""{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"echo","arguments":{"text":"hi","_meta":{"progressToken":"t1"}}}}""")
        val resp = h.runAndGetObjects()[0]
        assertNull(resp["error"])
        val text = ((resp["result"] as JsonObject)["content"] as JsonArray)
            .let { (it[0] as JsonObject)["text"]?.jsonPrimitive?.content }
        // Echo tool reads "text" arg; _meta should not interfere.
        assertEquals("hi", text)
    }
}
