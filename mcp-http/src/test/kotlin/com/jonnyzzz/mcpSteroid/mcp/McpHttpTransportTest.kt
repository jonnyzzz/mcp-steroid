/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Integration tests for McpHttpTransport.
 * Tests the full HTTP transport layer with a Ktor server.
 */
class McpHttpTransportTest {
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var mcpServer: McpServerCore
    private lateinit var client: HttpClient
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }

        mcpServer = McpServerCore(
            serverInfo = ServerInfo(
                name = "test-server",
                version = "1.0.0"
            ),
            capabilities = ServerCapabilities(
                tools = ToolsCapability(listChanged = false)
            )
        )

        // Register a test tool
        mcpServer.toolRegistry.registerTool(object : McpTool {
            override val name = "test_echo"
            override val description = "Echo tool for testing"
            override val inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("message") { put("type", "string") }
                }
            }

            override suspend fun call(context: ToolCallContext): ToolCallResult {
                val message = context.params.arguments["message"]?.jsonPrimitive?.content ?: ""
                return ToolCallResult(content = listOf(ContentItem.Text(text = "Echo: $message")))
            }
        })

        // Register a test resource
        mcpServer.resourceRegistry.registerResource(
            uri = "test://resource/test",
            name = "Test Resource",
            description = "A test resource for unit tests",
            mimeType = "text/plain",
            contentProvider = { "Test resource content" }
        )

        // Register a test prompt
        mcpServer.promptRegistry.registerPrompt(
            prompt = Prompt(
                name = "test_prompt",
                title = "Test Prompt",
                description = "Test prompt description",
            ),
        ) {
            PromptGetResult(
                description = "Test prompt description",
                messages = listOf(
                    PromptMessage(
                        role = "user",
                        content = PromptContent.Text("Test prompt body")
                    )
                )
            )
        }

        server = embeddedServer(CIO, port = port) {
            install(SSE)
            routing {
                with(McpHttpTransport) {
                    installMcp("/mcp", mcpServer)
                }
            }
        }
        server.start(wait = false)

        client = HttpClient(io.ktor.client.engine.cio.CIO)
    }

    @AfterEach
    fun tearDown() {
        client.close()
        server.stop(100, 100)
    }

    @Test
    fun `test POST initialize creates session and returns response`() = runBlocking {
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Should include the session header.
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionId, "Should return a session ID")

        // Parse response
        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)
        assertNotNull(jsonResponse.result)

        val result = McpJson.decodeFromJsonElement<InitializeResult>(jsonResponse.result!!)
        assertEquals("test-server", result.serverInfo.name)
    }

    @Test
    fun `test POST with existing session ID reuses session`() = runBlocking {
        // First request creates a session.
        val initRequest = buildJsonObject {
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

        val firstResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }

        val sessionId = firstResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(sessionId)

        // Second request uses the session ID.
        val pingRequest = """{"jsonrpc":"2.0","id":2,"method":"ping"}"""

        val secondResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(pingRequest)
        }

        assertEquals(HttpStatusCode.OK, secondResponse.status)
    }

    @Test
    fun `test POST tools list returns tools`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // List tools next.
        val listRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)

        val result = McpJson.decodeFromJsonElement<ToolsListResult>(jsonResponse.result!!)
        assertTrue(result.tools.isNotEmpty(), "Should have at least one tool available")
        assertEquals("test_echo", result.tools[0].name)
    }

    @Test
    fun `test POST tools call invokes tool`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call the tool next.
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "test_echo")
                putJsonObject("arguments") {
                    put("message", "Hello World")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(callRequest.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull(jsonResponse.error)

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(jsonResponse.result!!)
        assertEquals("Echo: Hello World", (result.content[0] as ContentItem.Text).text)
    }

    @Test
    fun `test POST notification returns Accepted`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Send a notification (no id).
        val notification = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(notification)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `test POST empty body returns BadRequest`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test POST with unknown session creates new session`() = runBlocking {
        // Server should create a new session for unknown session IDs (supports IDE restarts).
        val request = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "unknown-session-id")
            setBody(request)
        }

        // Server should accept the request and create a new session
        assertEquals(HttpStatusCode.OK, response.status)

        // Server should return a new session ID
        val newSessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(newSessionId, "Server should return a new session ID")
        assertNotEquals("unknown-session-id", newSessionId)
        val notice = response.headers[McpHttpTransport.SESSION_NOTICE_HEADER]
        assertNotNull(notice, "Server should return a session notice for an unknown session")
        assertTrue(notice!!.contains("Unknown session"), "Session notice should mention the unknown session")
    }

    @Test
    fun `test DELETE terminates session`() = runBlocking {
        // First, create a session by sending an initialization request.
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Delete session
        val deleteResponse = client.delete("http://localhost:$port/mcp") {
            header(McpHttpTransport.SESSION_HEADER, sessionId)
        }

        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // After deletion, requests with the old session ID should create a new session
        // (same as unknown session handling for IDE restart support)
        val listRequest = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        val afterDeleteResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, afterDeleteResponse.status)

        // Server should return a new session ID
        val newSessionId = afterDeleteResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull(newSessionId, "Server should return a new session ID after a deleted session")
        assertNotEquals(sessionId, newSessionId, "The new session ID should be different")
        val notice = afterDeleteResponse.headers[McpHttpTransport.SESSION_NOTICE_HEADER]
        assertNotNull(notice, "Server should return a session notice after a deleted session")
        assertTrue(notice!!.contains("Unknown session"), "Session notice should mention the unknown session")
    }

    @Test
    fun `test DELETE without session ID returns BadRequest`() = runBlocking {
        val response = client.delete("http://localhost:$port/mcp")

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `test batch request processing`() = runBlocking {
        // First initialize
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Send a batch request.
        val batchRequest = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","id":2,"method":"tools/list"}
        ]"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(batchRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val responses = McpJson.decodeFromString<JsonArray>(body)
        assertEquals(2, responses.size)
    }

    // ==================== GET Request Tests ====================

    @Test
    fun `test GET with Accept text event-stream returns 405 Method Not Allowed`() = runBlocking {
        // Per the MCP spec, the client MUST include the Accept: text/event-stream header.
        // The server returns 405 if it doesn't support SSE.
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "text/event-stream")
        }

        // The response should complete immediately with 405 (SSE not supported).
        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
    }

    @Test
    fun `test GET without Accept header returns server info`() = runBlocking {
        // When no Accept header is provided, the HTTP default is */* (wildcard).
        // The Ktor client adds Accept: */* by default.
        // The server returns server info for availability checks.
        val response = client.get("http://localhost:$port/mcp")

        assertEquals(HttpStatusCode.OK, response.status)
        assertServerInfo(response)
    }

    @Test
    fun `test GET with application json Accept header returns server info`() = runBlocking {
        // GET with Accept: application/json returns server info for availability checks.
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "application/json")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertServerInfo(response)
    }

    @Test
    fun `test GET with wildcard Accept returns server info`() = runBlocking {
        // Wildcard Accept (*/*) returns server info for availability checks.
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "*/*")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertServerInfo(response)
    }

    @Test
    fun `test GET with combined Accept header returns server info`() = runBlocking {
        // MCP clients (like Claude CLI) send the header: Accept: application/json, text/event-stream.
        // When JSON is acceptable, return server info for availability checks.
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "application/json, text/event-stream")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertServerInfo(response)
    }

    private suspend fun assertServerInfo(response: HttpResponse) {
        val json = McpJson.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(mcpServer.serverInfo.name, json["name"]?.jsonPrimitive?.content)
        assertEquals(mcpServer.serverInfo.version, json["version"]?.jsonPrimitive?.content)
        assertEquals("available", json["status"]?.jsonPrimitive?.content)
    }

    // ==================== Unknown Method Tests ====================

    @Test
    fun `test POST unknown method returns METHOD_NOT_FOUND error`() = runBlocking {
        // First initialize to get session
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call unknown method
        val unknownRequest = """{"jsonrpc":"2.0","id":2,"method":"unknown/method"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(unknownRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull(jsonResponse.error, "Should have error")
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, jsonResponse.error?.code)
        assertTrue(
            jsonResponse.error?.message?.contains("not found") == true,
            "Error message should mention method",
        )
    }

    @Test
    fun `test POST prompts list returns prompts`() = runBlocking {
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        val listRequest = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\"}"
        val listResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(listRequest)
        }

        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listRpc = McpJson.decodeFromString<JsonRpcResponse>(listResponse.bodyAsText())
        assertNull(listRpc.error, "prompts/list should succeed")

        val promptsList = McpJson.decodeFromJsonElement<PromptsListResult>(listRpc.result!!)
        assertTrue(promptsList.prompts.any { it.name == "test_prompt" })

        val getRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "prompts/get")
            putJsonObject("params") {
                put("name", "test_prompt")
            }
        }

        val getResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(getRequest.toString())
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        val getRpc = McpJson.decodeFromString<JsonRpcResponse>(getResponse.bodyAsText())
        assertNull(getRpc.error, "prompts/get should succeed")
        val getResult = McpJson.decodeFromJsonElement<PromptGetResult>(getRpc.result!!)
        assertEquals(1, getResult.messages.size)
    }

    @Test
    fun `test POST resources list returns resources`() = runBlocking {
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call resources/list - now implemented
        val resourcesRequest = """{"jsonrpc":"2.0","id":2,"method":"resources/list"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(resourcesRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNull(jsonResponse.error, "Should not have error")
        assertNotNull(jsonResponse.result, "Should have result")

        val resourcesList = McpJson.decodeFromJsonElement<ResourcesListResult>(jsonResponse.result!!)
        assertTrue(resourcesList.resources.isNotEmpty(), "Should have at least one resource")
    }

    @Test
    fun `test POST tools call with unknown tool returns error in result`() = runBlocking {
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Call unknown tool
        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "unknown_tool_that_does_not_exist")
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(callRequest.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        // tools/call returns result with isError=true, not a JSON-RPC error
        assertNull(jsonResponse.error, "Should not have JSON-RPC error")
        assertNotNull(jsonResponse.result, "Should have result")

        val result = McpJson.decodeFromJsonElement<ToolCallResult>(jsonResponse.result!!)
        assertTrue(result.isError, "Should be marked as error")
        assertTrue(
            (result.content[0] as ContentItem.Text).text.contains("not found"),
            "Error content should mention tool not found",
        )
    }

    @Test
    fun `test POST malformed JSON returns PARSE_ERROR`() = runBlocking {
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody("{ invalid json }")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull(jsonResponse.error, "Should have error")
        assertEquals(JsonRpcErrorCodes.PARSE_ERROR, jsonResponse.error?.code)
    }

    @Test
    fun `test POST missing method returns INVALID_REQUEST`() = runBlocking {
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Request without method field
        val invalidRequest = """{"jsonrpc":"2.0","id":2}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(invalidRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val jsonResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNotNull(jsonResponse.error, "Should have error")
        assertEquals(JsonRpcErrorCodes.INVALID_REQUEST, jsonResponse.error?.code)
    }

    @Test
    fun `test POST batch with unknown methods returns errors for each`() = runBlocking {
        val initRequest = buildJsonObject {
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

        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Batch with known and unknown methods
        val batchRequest = """[
            {"jsonrpc":"2.0","id":1,"method":"ping"},
            {"jsonrpc":"2.0","id":2,"method":"unknown/method1"},
            {"jsonrpc":"2.0","id":3,"method":"tools/list"},
            {"jsonrpc":"2.0","id":4,"method":"unknown/method2"}
        ]"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(batchRequest)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        val responses = McpJson.decodeFromString<JsonArray>(body)
        assertEquals(4, responses.size)

        // Check that unknown methods return errors
        val response1 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[0])
        val response2 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[1])
        val response3 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[2])
        val response4 = McpJson.decodeFromJsonElement<JsonRpcResponse>(responses[3])

        assertNull(response1.error, "ping should succeed")
        assertNotNull(response2.error, "unknown/method1 should fail")
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response2.error?.code)
        assertNull(response3.error, "tools/list should succeed")
        assertNotNull(response4.error, "unknown/method2 should fail")
        assertEquals(JsonRpcErrorCodes.METHOD_NOT_FOUND, response4.error?.code)
    }

    // ==================== Header Validation Tests ====================

    @Test
    fun `test POST without Content-Type returns UnsupportedMediaType`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            accept(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `test POST with wrong Content-Type returns UnsupportedMediaType`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Text.Plain)
            accept(ContentType.Application.Json)
            setBody(request)
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    }

    @Test
    fun `test POST with Accept header not including json returns NotAcceptable`() = runBlocking {
        val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "text/html")
            setBody(request)
        }

        assertEquals(HttpStatusCode.NotAcceptable, response.status)
    }

    @Test
    fun `test POST with Accept wildcard succeeds`() = runBlocking {
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "*/*")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST with Accept application wildcard succeeds`() = runBlocking {
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/*")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST with combined Accept header succeeds`() = runBlocking {
        // MCP clients typically send: Accept: application/json, text/event-stream
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `test POST without Accept header succeeds`() = runBlocking {
        // Accept header is optional - if not provided, we assume the client accepts any response
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            // No Accept header
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ==================== MCP-Protocol-Version Header Tests ====================

    @Test
    fun `test response includes MCP-Protocol-Version header`() = runBlocking {
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

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Per MCP 2025-11-25 spec: Server MUST include MCP-Protocol-Version header in responses
        val protocolVersionHeader = response.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        assertNotNull(protocolVersionHeader, "Response should include MCP-Protocol-Version header")
        assertEquals(MCP_PROTOCOL_VERSION, protocolVersionHeader)
    }

    @Test
    fun `test POST initialize with a supported older version negotiates it in body and header`() = runBlocking {
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-06-18")
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client")
                    put("version", "1.0.0")
                }
            }
        }

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(request.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // The MCP-Protocol-Version header MUST match the negotiated version in the body,
        // and both must be the client's requested version since we support it.
        assertEquals("2025-06-18", response.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER])

        val result = McpJson.decodeFromJsonElement<InitializeResult>(
            McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText()).result!!
        )
        assertEquals("2025-06-18", result.protocolVersion)
    }

    @Test
    fun `test GET response includes MCP-Protocol-Version header`() = runBlocking {
        val response = client.get("http://localhost:$port/mcp") {
            header(HttpHeaders.Accept, "application/json")
        }

        assertEquals(HttpStatusCode.OK, response.status)

        // Per MCP 2025-11-25 spec: Server MUST include MCP-Protocol-Version header in responses
        val protocolVersionHeader = response.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        assertNotNull(protocolVersionHeader, "GET response should include MCP-Protocol-Version header")
        assertEquals(MCP_PROTOCOL_VERSION, protocolVersionHeader)
    }

    @Test
    fun `test CORS headers include MCP-Protocol-Version`() = runBlocking {
        val response = client.options("http://localhost:$port/mcp")

        assertEquals(HttpStatusCode.NoContent, response.status)

        // Check CORS headers expose MCP-Protocol-Version
        @Suppress("UastIncorrectHttpHeaderInspection")
        val exposeHeaders = response.headers["Access-Control-Expose-Headers"]
        assertNotNull(exposeHeaders, "Should have Access-Control-Expose-Headers")
        assertTrue(
            exposeHeaders!!.contains(McpHttpTransport.PROTOCOL_VERSION_HEADER),
            "CORS should expose MCP-Protocol-Version header",
        )

        val allowHeaders = response.headers["Access-Control-Allow-Headers"]
        assertNotNull(allowHeaders, "Should have Access-Control-Allow-Headers")
        assertTrue(
            allowHeaders!!.contains(McpHttpTransport.PROTOCOL_VERSION_HEADER),
            "CORS should allow MCP-Protocol-Version header",
        )
    }

    // ── Boundary catch-all (issue #46 A0) ─────────────────────────────────
    //
    // These tests pin the contract that an internal error inside
    // `handleMessage` (or anywhere on the response phase) lands as a
    // well-formed JSON-RPC error envelope at HTTP 200, not as a bare
    // HTTP 500 with a raw text body — JSON-RPC clients can decode the
    // envelope and retry, whereas a 500 looks like a transport failure
    // and stalls the session. The dedicated runtime tool that registers
    // the throwing tool is scoped to the test using a sub-server because
    // the shared `mcpServer` in `setUp` should not be polluted with
    // failure-mode handlers.

    // Register a throwing tool whose exception escapes the registry's
    // `catch (Exception)` (McpToolRegistry.kt:90) — `AssertionError` is an
    // `Error`, not an `Exception`, so it propagates all the way to the
    // transport boundary where A0 must convert it into a JSON-RPC error
    // envelope. A plain `IllegalStateException` would be intercepted by
    // the registry and surface as `result.isError=true`, never exercising
    // the boundary code path.
    private fun registerBoundaryThrowingTool(toolName: String, message: String = "boundary regression") {
        mcpServer.toolRegistry.registerTool(object : McpTool {
            override val name = toolName
            override val description = "Throws Error so the boundary path fires"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override suspend fun call(context: ToolCallContext): ToolCallResult {
                throw AssertionError(message)
            }
        })
    }

    private suspend fun initializeAndGetSessionId(): String {
        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", MCP_PROTOCOL_VERSION)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") {
                    put("name", "test-client"); put("version", "1.0.0")
                }
            }
        }
        val initResponse = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(initRequest.toString())
        }
        assertEquals(HttpStatusCode.OK, initResponse.status)
        return initResponse.headers[McpHttpTransport.SESSION_HEADER]!!
    }

    @Test
    fun `handlePost wraps uncaught Throwable as JSON-RPC error at HTTP 200`() = runBlocking {
        registerBoundaryThrowingTool("throwing_tool", "boom from throwing_tool")
        val sessionId = initializeAndGetSessionId()

        val callRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 42)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "throwing_tool")
                putJsonObject("arguments") {}
            }
        }
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(callRequest.toString())
        }

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "Internal Throwable must NOT escape as HTTP 500 — JSON-RPC errors go in the body",
        )
        val body = response.bodyAsText()
        val envelope = Json.parseToJsonElement(body).jsonObject

        assertEquals(JSONRPC_VERSION, envelope["jsonrpc"]?.jsonPrimitive?.content)
        // The request `id` must be echoed back so the client can correlate.
        assertEquals(42, envelope["id"]?.jsonPrimitive?.int)
        // The boundary path must produce a JSON-RPC `error` envelope, not
        // `result.isError=true` (the latter would mean the registry caught
        // the throwable first, never reaching the boundary).
        val error = envelope["error"]?.jsonObject
        assertNotNull(error, "Boundary must surface failure via JSON-RPC `error`, got: $body")
        assertEquals(
            JsonRpcErrorCodes.INTERNAL_ERROR,
            error!!["code"]?.jsonPrimitive?.int,
            "Boundary errors use INTERNAL_ERROR (-32603), got: $body",
        )
        assertNull(envelope["result"], "JSON-RPC 2.0 forbids `result` + `error` together, got: $body")
    }

    @Test
    fun `handlePost preserves request id in error envelope even when handler throws`() = runBlocking {
        registerBoundaryThrowingTool("id_echo_throws")
        val sessionId = initializeAndGetSessionId()

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", "req-abc-123")
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "id_echo_throws")
                        putJsonObject("arguments") {}
                    }
                }.toString()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(
            "req-abc-123",
            envelope["id"]?.jsonPrimitive?.content,
            "JSON-RPC id must be echoed so the client can correlate the error to the call",
        )
        assertNotNull(envelope["error"], "Boundary path must produce JSON-RPC error envelope")
    }

    @Test
    fun `handlePost preserves session header on the error response so client keeps the new id`() = runBlocking {
        // Brand-new session (no header sent): the server creates one. If the
        // handler then throws, the client must still see the Mcp-Session-Id
        // response header — otherwise the next request triggers a SECOND
        // session creation for the same logical client. The fix sets the
        // header BEFORE entering the boundary catch.
        registerBoundaryThrowingTool("throws_on_new_session")

        // initialize succeeds — gets a fresh session id.
        val sessionId = initializeAndGetSessionId()

        // Same session sends a tools/call that throws Error. Because the
        // session is no longer new, this particular call doesn't re-emit the
        // header — but the regression we care about is: the FIRST request
        // that throws and creates a session still surfaces the id. Simulate
        // that by deleting the session and reusing the same client, then
        // firing a tools/call without a session header.
        val deleteResponse = client.delete("http://localhost:$port/mcp") {
            header(McpHttpTransport.SESSION_HEADER, sessionId)
        }
        assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

        // Now: no session header → server creates a new session → tool throws
        // → catch fires. The new session id MUST appear in the response.
        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 7)
                    put("method", "tools/call")
                    putJsonObject("params") {
                        put("name", "throws_on_new_session")
                        putJsonObject("arguments") {}
                    }
                }.toString()
            )
        }

        val newSessionHeader = response.headers[McpHttpTransport.SESSION_HEADER]
        // The server emits the header in the new-session branch even if the
        // request later threw. If this is null, future requests will reconnect
        // with no id and the server will create yet another session — leak.
        assertNotNull(
            newSessionHeader,
            "New-session header must be set even when handleMessage throws, body: ${response.bodyAsText()}",
        )
    }

    @Test
    fun `handlePost emits well-formed JSON-RPC error when body is unparseable JSON`() = runBlocking {
        // This tests `extractRequestId`'s fallback path: when the body isn't
        // valid JSON the boundary's `id` is `JsonNull`. The `McpServerCore`
        // parse-error path returns its own error envelope before the
        // boundary fires, so this test really pins both paths produce a
        // well-formed envelope.
        val sessionId = initializeAndGetSessionId()

        val response = client.post("http://localhost:$port/mcp") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("not-json{}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val envelope = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(JSONRPC_VERSION, envelope["jsonrpc"]?.jsonPrimitive?.content)
        assertNotNull(envelope["error"], "Parse-error must yield JSON-RPC error envelope")
        // Per JSON-RPC 2.0: id is null when the server couldn't detect it.
        assertTrue(
            envelope["id"] == JsonNull,
            "Unparseable body must produce id=null, got: ${envelope["id"]}",
        )
    }
}
