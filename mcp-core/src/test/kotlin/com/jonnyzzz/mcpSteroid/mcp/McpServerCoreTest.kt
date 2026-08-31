/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for McpServerCore message handling.
 */
class McpServerCoreTest {
    private lateinit var server: McpServerCore
    private lateinit var session: McpSession

    @BeforeEach
    fun setUp() {
        server = McpServerCore(
            serverInfo = ServerInfo(
                name = "test-server",
                version = "1.0.0"
            ),
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false)
            ),
            instructions = "Test server instructions"
        )
        session = server.sessionManager.createSession()
    }

    @Test
    fun `test initialize request returns correct response`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val responseJson = server.handleMessage(request.toString(), session)
        assertNotNull(responseJson, "Should return response")

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertEquals(1, response.id.jsonPrimitive.int)
        assertNull(response.error)
        assertNotNull(response.result)

        val result = McpJson.decodeFromJsonElement<InitializeResult>(response.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, result.protocolVersion)
        assertEquals("test-server", result.serverInfo.name)
        assertEquals("Test server instructions", result.instructions)
    }

    @Test
    fun `test initialize echoes a supported older protocol version back`() = runBlocking {
        val request = initializeRequest(protocolVersion = "2025-06-18")

        val responseJson = server.handleMessage(request, session)
        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        val result = McpJson.decodeFromJsonElement<InitializeResult>(response.result!!)

        assertEquals("2025-06-18", result.protocolVersion,
            "Server must echo the client's requested version when it is supported")
    }

    @Test
    fun `test initialize falls back to latest for an unsupported protocol version`() = runBlocking {
        val request = initializeRequest(protocolVersion = "1999-01-01")

        val responseJson = server.handleMessage(request, session)
        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        val result = McpJson.decodeFromJsonElement<InitializeResult>(response.result!!)

        assertEquals(MCP_PROTOCOL_VERSION, result.protocolVersion,
            "Server must reply with its own latest version when the client's is not supported")
    }

    @Test
    fun `test initialize stores the negotiated protocol version on the session`() = runBlocking {
        server.handleMessage(initializeRequest(protocolVersion = "2025-06-18"), session)

        assertEquals("2025-06-18", session.negotiatedProtocolVersion,
            "Session must carry the negotiated version so the transport can report it")
    }

    private fun initializeRequest(protocolVersion: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", protocolVersion)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "test-client")
                put("version", "1.0.0")
            }
        }
    }.toString()

    @Test
    fun `test ping request returns empty object`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        val responseJson = server.handleMessage(request, session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertEquals(1, response.id.jsonPrimitive.int)
        assertNull(response.error)
        assertNotNull(response.result)
        assertTrue(response.result is JsonObject)
    }

    @Test
    fun `test tools list returns registered tools`() = runBlocking {
        // Register a test tool
        server.toolRegistry.registerTool(object : McpTool {
            override val name = "test_tool"
            override val description = "A test tool"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext) =
                ToolCallResult(content = listOf(ContentItem.Text(text = "OK")))
        })

        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

        val responseJson = server.handleMessage(request, session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull(response.error)

        val result = McpJson.decodeFromJsonElement<ToolsListResult>(response.result!!)
        assertEquals(1, result.tools.size)
        assertEquals("test_tool", result.tools[0].name)
        assertEquals("A test tool", result.tools[0].description)
    }

    @Test
    fun `test tools call executes handler`() = runBlocking {
        var called = false

        server.toolRegistry.registerTool(object : McpTool {
            override val name = "echo_tool"
            override val description = "Echoes input"
            override val inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                }
            }

            override suspend fun call(context: ToolCallContext): ToolCallResult {
                called = true
                val message = context.params.arguments["message"]?.jsonPrimitive?.content ?: "no message"
                return ToolCallResult(content = listOf(ContentItem.Text(text = "Echo: $message")))
            }
        })

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "echo_tool")
                putJsonObject("arguments") {
                    put("message", "Hello")
                }
            }
        }

        val responseJson = server.handleMessage(request.toString(), session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull(response.error)

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(response.result!!)
        assertEquals(1, result.content.size)
        assertEquals("Echo: Hello", (result.content[0] as ContentItem.Text).text)
        assertTrue(called, "Handler should have been called")
    }

    @Test
    fun `test tools call with unknown tool returns error`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "unknown_tool")
            }
        }

        val responseJson = server.handleMessage(request.toString(), session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNull(response.error)

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(response.result!!)
        assertTrue(result.isError)
        assertTrue((result.content[0] as ContentItem.Text).text.contains("not found"))
    }

    @Test
    fun `test unknown method returns error`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"unknown/method"}"""

        val responseJson = server.handleMessage(request, session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response.error?.code)
    }

    @Test
    fun `test notification returns null`() = runBlocking {
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val response = server.handleMessage(notification, session)
        assertNull(response, "Notifications should not return a response")
    }

    @Test
    fun `test malformed JSON returns parse error`() = runBlocking {
        val malformedJson = "not valid json"

        val responseJson = server.handleMessage(malformedJson, session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertNotNull(response.error)
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, response.error?.code)
    }

    @Test
    fun `test batch request returns batch response`() = runBlocking {
        val batch = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","id":2,"method":"ping"}
        ]"""

        val responseJson = server.handleMessage(batch, session)
        assertNotNull(responseJson)

        val responses = McpJson.decodeFromString<JsonArray>(responseJson!!)
        assertEquals(2, responses.size)
    }

    @Test
    fun `test session is marked initialized after initialize`() = runBlocking {
        assertFalse(session.initialized, "Session should not be initialized yet")

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        server.handleMessage(request.toString(), session)

        assertTrue(session.initialized, "Session should be initialized")
        assertEquals("test-client", session.clientInfo?.name)
    }

    @Test
    fun `test session manager creates unique sessions`() {
        val session1 = server.sessionManager.createSession()
        val session2 = server.sessionManager.createSession()

        assertNotEquals(session1.id, session2.id)
    }

    @Test
    fun `test session manager retrieves session by id`() {
        val session1 = server.sessionManager.createSession()
        val retrieved = server.sessionManager.getSession(session1.id)

        assertNotNull(retrieved)
        assertEquals(session1.id, retrieved?.id)
    }

    @Test
    fun `test session manager removes session`() {
        val session1 = server.sessionManager.createSession()
        server.sessionManager.removeSession(session1.id)

        val retrieved = server.sessionManager.getSession(session1.id)
        assertNull(retrieved)
    }

    @Test
    fun `test request with string id`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":"request-abc","method":"ping"}"""

        val responseJson = server.handleMessage(request, session)
        assertNotNull(responseJson)

        val response = McpJson.decodeFromString<JsonRpcResponse>(responseJson!!)
        assertEquals("request-abc", response.id.jsonPrimitive.content)
    }

    @Test
    fun `test prompts list and get`() = runBlocking {
        server.promptRegistry.registerPrompt(
            prompt = Prompt(
                name = "mcp-steroid",
                title = "IntelliJ API Power User Guide",
                description = "Test prompt description",
            ),
        ) {
            PromptGetResult(
                description = "Test prompt description",
                messages = listOf(
                    PromptMessage(
                        role = "user",
                        content = PromptContent.Text("Prompt body text")
                    )
                )
            )
        }

        val listRequest = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"prompts/list\"}"
        val listResponseJson = server.handleMessage(listRequest, session)
        assertNotNull(listResponseJson)

        val listResponse = McpJson.decodeFromString<JsonRpcResponse>(listResponseJson!!)
        assertNull(listResponse.error)
        val listResult = McpJson.decodeFromJsonElement<PromptsListResult>(listResponse.result!!)
        assertTrue(listResult.prompts.any { it.name == "mcp-steroid" })

        val getRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "prompts/get")
            putJsonObject("params") {
                put("name", "mcp-steroid")
            }
        }

        val getResponseJson = server.handleMessage(getRequest.toString(), session)
        assertNotNull(getResponseJson)

        val getResponse = McpJson.decodeFromString<JsonRpcResponse>(getResponseJson!!)
        assertNull(getResponse.error)
        val getResult = McpJson.decodeFromJsonElement<PromptGetResult>(getResponse.result!!)
        assertEquals(1, getResult.messages.size)
    }
}
