/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.NpxBridgeService
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the MCP server.
 * Verifies the MCP HTTP handshake and tool flows against the real SteroidsMcpServer.
 */
@Suppress("GrazieInspection", "GrazieInspectionRunner")
class McpServerIntegrationTest : BasePlatformTestCase() {
    private companion object {
        val DebugJson = Json(McpJson) {
            prettyPrint = true
        }
    }

    private lateinit var client: HttpClient

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
        client = HttpClient(CIO) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60_000 // 60s — action discovery needs time on slow CI agents
                connectTimeoutMillis = 10_000
            }
        }
    }

    override fun tearDown() {
        try {
            client.close()
        } finally {
            super.tearDown()
        }
    }

    fun testMcpAgentHappyPath(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue an MCP session ID", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)

        val toolsListResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsListResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsListResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        val toolNames = toolsList.tools.map { it.name }.toSet()
        assertTrue(
            "Steroid tools should be advertised",
            toolNames.containsAll(
                setOf(
                    "steroid_list_projects",
                    "steroid_list_windows",
                    "steroid_execute_code"
                )
            )
        )

        val listProjectsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-projects","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }

        assertEquals(HttpStatusCode.OK, listProjectsResponse.status)
        val listProjectsRpc = McpJson.decodeFromString<JsonRpcResponse>(listProjectsResponse.bodyAsText())
        assertNull("steroid_list_projects should not return JSON-RPC error", listProjectsRpc.error)
        val listProjectsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listProjectsRpc.result!!)
        assertFalse("steroid_list_projects should succeed", listProjectsResult.isError)
        val projectsPayload = (listProjectsResult.content.single() as ContentItem.Text).text
        val projects = McpJson.decodeFromString<ListProjectsResponse>(projectsPayload)
        val projectsSelfBackend = projects.backends.single()
        assertTrue("IDE display name should be reported on the self backend", projectsSelfBackend.displayName.isNotBlank())
        assertTrue("IDE build should be reported on the self backend", projectsSelfBackend.build.orEmpty().isNotBlank())
        assertTrue(
            "Current project should be discoverable via the MCP tool",
            projects.projects.any { it.name == project.name }
        )

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildExecuteCodeRequest(project.name))
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("steroid_execute_code should return result payload", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("steroid_execute_code should return content for the agent", execOutput.isNotBlank())
        assertFalse("Execution should succeed, got error payload: $execOutput", execResult.isError)
        assertTrue(
            "Execution output should include marker text, got: $execOutput",
            execOutput.contains("Integration test execution from MCP")
        )
    }

    fun testListWindowsTool(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val listWindowsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-windows","method":"tools/call","params":{"name":"steroid_list_windows"}}""")
        }

        assertEquals(HttpStatusCode.OK, listWindowsResponse.status)
        val listWindowsRpc = McpJson.decodeFromString<JsonRpcResponse>(listWindowsResponse.bodyAsText())
        assertNull("steroid_list_windows should not return JSON-RPC error", listWindowsRpc.error)
        val listWindowsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listWindowsRpc.result!!)
        assertFalse("steroid_list_windows should succeed", listWindowsResult.isError)

        val payload = listWindowsResult.content.filterIsInstance<ContentItem.Text>().firstOrNull()?.text ?: ""

        // #89: no top-level ide/plugin/pid header — identity lives in backends[] only.
        val rawWindowsJson = McpJson.parseToJsonElement(payload).jsonObject
        for (droppedHeaderKey in listOf("ide", "plugin", "pid")) {
            assertFalse(
                "steroid_list_windows must not carry the dropped top-level '$droppedHeaderKey' header",
                rawWindowsJson.containsKey(droppedHeaderKey)
            )
        }

        val windows = McpJson.decodeFromString(ListWindowsResponse.serializer(), payload)
        assertNotNull("Should return windows payload", windows)
        assertEquals(
            "Direct-IDE list_windows must self-describe with exactly one backend",
            1,
            windows.backends.size
        )
        val selfBackend = windows.backends.single()
        assertTrue("The self backend must carry a backend_name", selfBackend.backendName.isNotBlank())
        windows.windows.forEach { window ->
            assertEquals(
                "Every window must be bound to the single self backend",
                selfBackend.backendName,
                window.backendName
            )
        }
        windows.backgroundTasks.forEach { task ->
            assertEquals(
                "Every background task must be bound to the single self backend",
                selfBackend.backendName,
                task.backendName
            )
        }
        if (windows.windows.isNotEmpty()) {
            assertTrue(
                "windows should include windowId values",
                windows.windows.any { it.windowId.isNotBlank() }
            )
        }
    }

    // The /products and /server-metadata bridge endpoints were removed (unused by devrig). Auth-rejection
    // coverage for the bridge is kept here against a live endpoint (/windows).
    fun testNpxBridgeRejectsMissingOrWrongToken(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val windowsUrl = "http://localhost:${server.port}/api/jonnyzzz/mcp-steroid/v1/windows"

        val missingTokenResponse = client.get(windowsUrl)
        assertEquals(HttpStatusCode.Unauthorized, missingTokenResponse.status)

        val wrongTokenResponse = client.get(windowsUrl) {
            header(HttpHeaders.Authorization, "Bearer wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrongTokenResponse.status)
    }

    /**
     * Pins the devrig bridge `GET /projects` response shape — the endpoint the devrig monitor polls to
     * build its routing snapshot. Each project object must carry exactly the keys
     * `IdeProjectMonitorService.collectProjectsImpl` parses by name (`name`, `path`, `project_name`,
     * `backend_name`); a missing/renamed key silently drops the project from devrig's view. Asserts the
     * current open project is present and every field is a non-blank string — locking the fields the
     * endpoint returns today so an accidental rename (e.g. the `backed_name` typo) fails the build.
     */
    fun testProjectsBridgeEndpointReturnsExpectedFields(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val projectsUrl = "http://localhost:${server.port}/api/jonnyzzz/mcp-steroid/v1/projects"

        val unauthorized = client.get(projectsUrl)
        assertEquals(
            "GET /projects must require the bridge token",
            HttpStatusCode.Unauthorized,
            unauthorized.status,
        )

        val response = client.get(projectsUrl) { npxBridgeAuthorization() }
        assertEquals(HttpStatusCode.OK, response.status)

        val root = McpJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val projects = root["projects"]?.jsonArray ?: error("GET /projects must return a 'projects' array: $root")
        assertTrue("the open project must be discoverable via /projects, got: $root", projects.isNotEmpty())

        val expectedFields = setOf("name", "path", "project_name", "backend_name")
        val projectObjects = projects.map { it.jsonObject }
        for (p in projectObjects) {
            assertEquals(
                "each /projects entry exposes exactly the fields the devrig monitor parses",
                expectedFields,
                p.keys,
            )
            for (key in expectedFields) {
                assertTrue(
                    "field '$key' must be a non-blank string in /projects entry: $p",
                    p[key]!!.jsonPrimitive.content.isNotBlank(),
                )
            }
        }
        assertTrue(
            "the current project must appear in /projects, got: $projectObjects",
            projectObjects.any { it["name"]!!.jsonPrimitive.content == project.name },
        )
    }

    /**
     * The direct in-IDE surface serves exactly one backend, so steroid_open_project must NOT advertise
     * the devrig-only `backend_name` parameter (Option B). Guards the in-IDE
     * `OpenProjectToolSpec(includeBackendName = false)` registration in SteroidsMcpServer.
     */
    fun testOpenProjectSchemaOmitsBackendNameOnDirectSurface(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        val toolsListResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, toolsListResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsListResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)

        val openProject = toolsList.tools.single { it.name == "steroid_open_project" }
        val properties = openProject.inputSchema["properties"]!!.jsonObject
        assertFalse(
            "Direct-IDE steroid_open_project must not advertise backend_name (devrig-only param)",
            properties.containsKey("backend_name")
        )
    }

    /**
     * R3.6 — the direct in-IDE surface self-describes with the SAME shape devrig emits: exactly one
     * routable backend (this IDE), and every project's `project_name == name` and `backend_name` points
     * at that single backend. Replaces the round-2 "direct surface stays empty" guard.
     */
    fun testDirectIdeListProjectsSelfDescribes(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        val listProjectsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"call-list-projects","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }
        assertEquals(HttpStatusCode.OK, listProjectsResponse.status)
        val listProjectsRpc = McpJson.decodeFromString<JsonRpcResponse>(listProjectsResponse.bodyAsText())
        assertNull("steroid_list_projects should not return JSON-RPC error", listProjectsRpc.error)
        val listProjectsResult = McpJson.decodeFromJsonElement<ToolCallResult>(listProjectsRpc.result!!)
        assertFalse("steroid_list_projects should succeed", listProjectsResult.isError)
        val projectsPayload = (listProjectsResult.content.single() as ContentItem.Text).text

        // #89: no top-level ide/plugin/pid header — identity lives in backends[] only.
        val rawProjectsJson = McpJson.parseToJsonElement(projectsPayload).jsonObject
        for (droppedHeaderKey in listOf("ide", "plugin", "pid")) {
            assertFalse(
                "steroid_list_projects must not carry the dropped top-level '$droppedHeaderKey' header",
                rawProjectsJson.containsKey(droppedHeaderKey)
            )
        }

        val response = McpJson.decodeFromString<ListProjectsResponse>(projectsPayload)

        assertEquals(
            "Direct-IDE list_projects must self-describe with exactly one backend",
            1,
            response.backends.size
        )
        val selfBackend = response.backends.single()
        assertTrue(
            "The self backend must carry a backend_name and display name",
            selfBackend.backendName.isNotBlank() && selfBackend.displayName.isNotBlank()
        )
        response.projects.forEach { project ->
            assertEquals(
                "Direct-IDE project_name must equal the real name",
                project.name,
                project.projectName
            )
            assertEquals(
                "Direct-IDE project must point at the single self backend",
                selfBackend.backendName,
                project.backendName
            )
        }
    }

    private suspend fun initializeSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue an MCP session ID", sessionId)
        return sessionId!!
    }

    private fun buildInitializeRequest() = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "init-1")
        put("method", "initialize")
        putJsonObject("params") {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") {
                put("name", "integration-test-client")
                put("version", "1.0.0")
            }
        }
    }
        .toString()

    private fun HttpRequestBuilder.npxBridgeAuthorization() {
        header(HttpHeaders.Authorization, "Bearer ${NpxBridgeService.getInstance().token}")
    }

    private fun buildExecuteCodeRequest(projectName: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", "execute-1")
        put("method", "tools/call")
        putJsonObject("params") {
            put("name", "steroid_execute_code")
            putJsonObject("arguments") {
                put("project_name", projectName)
                put(
                    "code",
                    """
                        println("Integration test execution from MCP")
                    """.trimIndent()
                )
                put("reason", "Verify MCP agent can execute code inside IntelliJ")
                put("task_id", "integration-test-task-1")
            }
        }
    }.toString()

    private suspend fun startSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue MCP session id", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not return error", initRpc.error)

        return sessionId!!
    }

    /**
     * Tests that the server responds correctly to GET requests with Claude CLI's Accept header.
     * Claude CLI sends "Accept: application/json, text/event-stream" for health checks.
     *
     * This was causing "Failed to connect" in Claude CLI because the server was returning
     * 405 Method Not Allowed when text/event-stream was in the Accept header.
     */
    fun testGetRequestWithClaudeCliAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends this Accept header format for health checks
        val response = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
        }

        assertEquals(
            "GET with Claude CLI Accept header should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )
        assertEquals(
            "Response should be JSON",
            ContentType.Application.Json.withoutParameters(),
            response.contentType()?.withoutParameters()
        )

        val body = response.bodyAsText()
        assertTrue(
            "Response should contain server name",
            body.contains("mcp-steroid")
        )
        assertTrue(
            "Response should indicate server is available",
            body.contains("available")
        )
    }

    /**
     * Tests that the server responds correctly to POST InitializeRequest with Claude CLI's format.
     * This verifies the full MCP handshake that Claude CLI expects.
     */
    fun testPostInitializeWithClaudeCliFormat(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Claude CLI sends InitializeRequest with specific capabilities
        val initRequest = """
        {
            "jsonrpc": "2.0",
            "id": "1",
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "clientInfo": {
                    "name": "claude-code",
                    "version": "2.0.67"
                },
                "capabilities": {
                    "roots": {
                        "listChanged": true
                    },
                    "sampling": {}
                }
            }
        }
        """.trimIndent()

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(initRequest)
        }

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse the response
        val body = response.bodyAsText()
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)

        assertNull("Response should not have error", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)
        assertNotNull("Server should have tools capability", initResult.capabilities.tools)
    }

    /**
     * Tests with the EXACT request format Claude CLI sends (from debug logs).
     *
     * Log entry example (JSON payload omitted; see debug logs for the exact request)
     *
     * Key differences from our test:
     * - "id" is numeric 0, not string "1"
     * - "capabilities" includes an empty "roots" object
     * - Field order: method, params, jsonrpc, id (not jsonrpc first)
     */
    fun testExactClaudeCliInitializeRequest(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // EXACT request from Claude CLI debug logs
        val exactClaudeRequest = """{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}"""

        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody(exactClaudeRequest)
        }

        println("[TEST] Response status: ${response.status}")
        println("[TEST] Response headers:")
        response.headers.forEach { name, values ->
            println("[TEST]   $name: ${values.joinToString(", ")}")
        }
        val body = response.bodyAsText()
        println("[TEST] Response body: $body")

        assertEquals(
            "InitializeRequest should return 200 OK",
            HttpStatusCode.OK,
            response.status
        )

        // Check for session ID header
        val sessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return Mcp-Session-Id header", sessionId)

        // Parse and verify the response
        val rpcResponse = McpJson.decodeFromString<JsonRpcResponse>(body)
        assertNull("Response should not have error: ${rpcResponse.error}", rpcResponse.error)
        assertNotNull("Response should have result", rpcResponse.result)

        // Verify response ID matches request ID (numeric 0)
        // Note: id is JsonElement, so we check the content
        assertTrue(
            "Response ID should be 0, got: ${rpcResponse.id}",
            rpcResponse.id.toString() == "0"
        )

        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(rpcResponse.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)
        assertEquals("mcp-steroid", initResult.serverInfo.name)
    }

    /**
     * Tests that SSE-only GET requests receive 405 Method Not Allowed.
     * This is per MCP spec - if the server doesn't support SSE, it should return 405.
     */
    fun testGetRequestWithSseOnlyAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Request SSE only (not JSON) - should get 405
        val response = client.get(server.mcpUrl) {
            header("Accept", "text/event-stream")
        }

        assertEquals(
            "GET with SSE-only Accept header should return 405",
            HttpStatusCode.MethodNotAllowed,
            response.status
        )
    }

    /**
     * Tests that later requests with a session ID work correctly.
     * This verifies the full session management flow.
     */
    fun testSessionManagementFlow(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Step 1: Initialize and get session ID
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Session ID should be provided", sessionId)

        // Step 2: Make a request with the session ID
        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"2","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed with valid session", toolsRpc.error)

        // Step 3: Make a request with an unknown session ID
        // Server should create a new session for unknown session IDs (supports IDE restart)
        val unknownSessionResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, "unknown-session-id-12345")
            setBody("""{"jsonrpc":"2.0","id":"3","method":"tools/list"}""")
        }

        assertEquals(
            "Request with unknown session should succeed (server creates new session)",
            HttpStatusCode.OK,
            unknownSessionResponse.status
        )

        // Server should return a new session ID
        val newSessionId = unknownSessionResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID for unknown session", newSessionId)
        assertFalse(
            "New session ID should be different from the unknown one",
            newSessionId == "unknown-session-id-12345"
        )

        // The response should be valid
        val unknownSessionRpc = McpJson.decodeFromString<JsonRpcResponse>(unknownSessionResponse.bodyAsText())
        assertNull("tools/list should succeed with auto-created session", unknownSessionRpc.error)
    }

    /**
     * This test verifies server behavior after IntelliJ IDEA restarts.
     * When a client sends an unknown session ID (for example, after an IDE restart),
     * the server should create a new session instead of rejecting the request.
     */
    fun testServerRestartHandling(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Simulate a client that has a stale session ID from before IDE restart
        val staleSessionId = "stale-session-from-previous-ide-instance"

        // Step 1: Send a tools/call request with the stale session ID
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, staleSessionId)
            setBody("""{"jsonrpc":"2.0","id":"1","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }

        // Server should accept the request and create a new session
        assertEquals(
            "Request with stale session should succeed",
            HttpStatusCode.OK,
            response.status
        )

        // Server should return a new session ID
        val newSessionId = response.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should return new session ID", newSessionId)
        assertFalse(
            "New session ID should be different from stale one",
            newSessionId == staleSessionId
        )

        // Step 2: Verify the new session works for later requests
        val followUpResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, newSessionId)
            setBody("""{"jsonrpc":"2.0","id":"2","method":"tools/list"}""")
        }

        assertEquals(HttpStatusCode.OK, followUpResponse.status)

        // No new session ID should be returned (we're using a valid one now)
        val followUpSessionId = followUpResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNull("No new session ID should be returned for valid session", followUpSessionId)
    }

    /**
     * Tests that successful execution returns clean output without error-like formatting.
     * The response should contain LOG: entries with the output,
     * not aggressive banners that look like errors.
     */
    fun testSuccessfulExecutionReturnsCleanOutput(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code
        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildExecuteCodeRequest(project.name))
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Verify execution is not marked as an error
        assertFalse("Execution should succeed, got: $execOutput", execResult.isError)

        // Verify the output contains our marker text
        assertTrue(
            "Output should contain marker text, got: $execOutput",
            execOutput.contains("Integration test execution from MCP")
        )

        // Verify the output format is clean (not error-like)
        assertFalse(
            "Output should NOT contain aggressive ACTION REQUIRED banner",
            execOutput.contains("ACTION REQUIRED")
        )
        assertFalse(
            "Output should NOT contain box drawing characters at start",
            execOutput.startsWith("╔")
        )
        assertFalse(
            "Output should NOT contain FAILED prefix (unless actually failed)",
            execOutput.contains("FAILED:")
        )
    }

    /**
     * Tests that when the configured port is busy, the server starts on the next available port.
     * This reproduces the issue where opening multiple IDE instances or projects causes
     * "Address already in use" errors.
     */
    fun testServerStartsOnNextPortWhenConfiguredPortIsBusy(): Unit = timeoutRunBlocking(30.seconds) {
        // This test uses port 0 (dynamic allocation), so it inherently tests
        // that the server can find a free port. The real scenario (configured port busy)
        // is tested implicitly by the fact that the server starts successfully
        // even when other tests may have started servers on various ports.
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Verify the server is running
        assertTrue("Server should be running on a valid port", server.port > 0)

        // Verify the server is accessible
        val response = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Tests that compilation errors are properly reported in the API response.
     * This test demonstrates what the agent sees when code fails to compile.
     */
    fun testCompilationErrorReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with syntax error - missing closing brace
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-compile-error")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            val x = 42
                            // Missing closing brace - syntax error!
                            println("This won't compile"
                    """.trimIndent())
                    put("reason", "Test compilation error handling")
                    put("task_id", "compile-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        // The JSON-RPC layer should succeed (no protocol error)
        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        assertNotNull("Should have result", execRpc.result)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== COMPILATION ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for compilation failure", execResult.isError)

        // Output should contain compilation error information
        assertTrue(
            "Output should mention compilation/script error, got: $execOutput",
            execOutput.contains("error", ignoreCase = true) ||
                    execOutput.contains("compile", ignoreCase = true) ||
                    execOutput.contains("script", ignoreCase = true)
        )
    }

    /**
     * Tests that type errors in code are properly reported.
     * This is a common error agents make - using wrong types.
     */
    fun testTypeErrorReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with type error - assigning String to Int
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-type-error")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            val number: Int = "this is not a number"
                            println(number)
                    """.trimIndent())
                    put("reason", "Test type error handling")
                    put("task_id", "type-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== TYPE ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for type mismatch", execResult.isError)

        // Output should mention a type-related error
        assertTrue(
            "Output should mention type error, got: $execOutput",
            execOutput.contains("type", ignoreCase = true) ||
                    execOutput.contains("mismatch", ignoreCase = true) ||
                    execOutput.contains("String", ignoreCase = true) ||
                    execOutput.contains("Int", ignoreCase = true)
        )
    }

    /**
     * Regression test for issue #46: a stuck `steroid_execute_code` script that
     * exceeds its own `timeout` parameter must come back as a JSON-RPC result
     * envelope with `isError=true` and a clear "timed out" message — never as
     * an HTTP 500 or a top-level JSON-RPC `error` object. A 500 looks like a
     * transport failure and stalls the agent session; a clean tool-error lets
     * the agent decide to retry or change strategy.
     *
     * The synthetic script blocks via `kotlinx.coroutines.delay` (cancellation-
     * cooperative) for far longer than the requested timeout, so `withTimeout`
     * inside `ScriptExecutor` fires its `TimeoutCancellationException` path
     * and `resultBuilder.reportFailed("Execution timed out after $timeout seconds")`
     * is the user-visible signal.
     */
    fun testExecuteCodeTimeoutReturnsCleanErrorNotHttp500(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        // Smallest practical script timeout (2 seconds). Script body delays for
        // 5 minutes — well beyond the timeout — and would otherwise pin the
        // executor coroutine until the test's own 60 s budget expired.
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-timeout")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("timeout", 2)
                    put("reason", "Timeout regression test (#46)")
                    put("task_id", "timeout-test")
                    put(
                        "code",
                        """
                            import kotlinx.coroutines.delay
                            import kotlin.time.Duration.Companion.minutes
                            println("ABOUT_TO_BLOCK")
                            // delay() is cancellation-cooperative — when ScriptExecutor's
                            // withTimeout(timeout.seconds) fires, this throws
                            // TimeoutCancellationException and the executor catches it.
                            delay(5.minutes)
                            println("UNREACHABLE")
                        """.trimIndent()
                    )
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(
            "Timeout must come back as HTTP 200 — agents treat 500 as a transport failure",
            HttpStatusCode.OK,
            execResponse.status,
        )

        val rpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull(
            "Timeout must NOT be surfaced as a top-level JSON-RPC `error` — it's a tool error, not a protocol error",
            rpc.error,
        )
        assertNotNull("Tool result envelope must be present even on timeout", rpc.result)

        val toolResult = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertTrue("Timeout must mark the tool result as isError=true", toolResult.isError)

        val output = toolResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue(
            "Error content must name the failure mode (got: $output)",
            output.contains("timed out", ignoreCase = true) || output.contains("timeout", ignoreCase = true),
        )
        // Pin the exact substring `reportFailed` writes — `output.contains("2")`
        // alone would match any incidental digit (execution id, stack-trace
        // frame, timestamp), so a future change that drops the configured
        // timeout from the message ("timed out after 600 seconds" as a stale
        // fallback) would still pass that loose assertion.
        assertTrue(
            "Error content must name the configured timeout (got: $output)",
            output.contains("after 2 second"),
        )
        // Defensive: prove the script body actually ran up to the suspension
        // point before being cancelled, and that the line *after* the
        // cancellation point did NOT run. If the timeout fired during
        // waitForSmartMode() instead of during the user delay() — possible
        // on a cold sandbox — these markers also catch that regression.
        assertTrue(
            "Script must have reached the user delay before cancellation (got: $output)",
            output.contains("ABOUT_TO_BLOCK"),
        )
        assertFalse(
            "Script must NOT have executed past the cancellation point (got: $output)",
            output.contains("UNREACHABLE"),
        )

        // Sanity check: the server stays alive after a timeout. The next
        // request on the same session must succeed end-to-end — decode the
        // result and prove `!isError`, otherwise a server stuck in a
        // degraded `isError=true on every call` state would pass an
        // HTTP-200-only check.
        val nextRequest = buildExecuteCodeRequest(project.name)
        val nextResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(nextRequest)
        }
        assertEquals(
            "MCP server must keep accepting requests after a timeout",
            HttpStatusCode.OK,
            nextResponse.status,
        )
        val nextRpc = McpJson.decodeFromString<JsonRpcResponse>(nextResponse.bodyAsText())
        assertNull("Follow-up call must succeed cleanly", nextRpc.error)
        assertNotNull("Follow-up must return a result envelope", nextRpc.result)
        val nextResult = McpJson.decodeFromJsonElement<ToolCallResult>(nextRpc.result!!)
        assertFalse(
            "Server must process the follow-up cleanly, not in a degraded isError state",
            nextResult.isError,
        )
        val nextOutput = nextResult.content
            .filterIsInstance<ContentItem.Text>()
            .joinToString("\n") { it.text }
        assertTrue(
            "Follow-up output must contain the echoed script marker (got: $nextOutput)",
            nextOutput.contains("Integration test execution from MCP"),
        )
    }

    /**
     * Tests that progress reporting works correctly over the MCP protocol.
     * When code calls progress(), the messages should be included in the response.
     *
     * Per MCP 2025-11-25 spec:
     * - Client can pass _meta.progressToken in tool call arguments
     * - Server sends notifications/progress with that token
     * - Progress messages are also accumulated in the final response
     */
    fun testProgressReportingInResponse(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code that reports progress multiple times
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            progress("Step 1: Initializing...")
                            progress("Step 2: Processing data...")
                            progress("Step 3: Completing task...")
                            println("DONE: All steps completed")
                    """.trimIndent())
                    put("reason", "Test progress reporting")
                    put("task_id", "progress-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== PROGRESS REPORTING RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should succeed
        assertFalse("Execution should succeed", execResult.isError)

        // Output should contain our completion message
        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("DONE: All steps completed")
        )

        // Output should contain progress messages (they may be throttled, so check for at least one)
        assertTrue(
            "Output should contain at least one progress indicator, got: $execOutput",
            execOutput.contains("Step") || execOutput.contains("PROGRESS:")
        )
    }

    /**
     * Tests that progress reporting works with _meta.progressToken in the request.
     * The MCP 2025-11-25 spec allows clients to provide a progressToken to receive
     * notifications/progress messages during execution.
     */
    fun testProgressReportingWithProgressToken(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Create a unique progress token
        val progressToken = "progress-token-${UUID.randomUUID()}"

        // Execute code with _meta.progressToken in arguments
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-progress-token")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            progress("Starting with progress token...")
                            progress("Middle step...")
                            progress("Final step...")
                            println("COMPLETED: Task with progress token")
                    """.trimIndent())
                    put("reason", "Test progress with token")
                    put("task_id", "progress-token-test")
                    // Include _meta.progressToken per MCP spec
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== PROGRESS WITH TOKEN RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertFalse("Execution should succeed", execResult.isError)

        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("COMPLETED: Task with progress token")
        )

        // Progress messages from progress() calls are added to the tool result via logProgress().
        // This is the delivery mechanism for HTTP clients (streaming clients get separate notifications).
        assertTrue(
            "Output should contain progress messages from script, got: $execOutput",
            execOutput.contains("Starting with progress token")
                    || execOutput.contains("Middle step")
                    || execOutput.contains("Final step")
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId!!)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

    /**
     * Tests that long-running operations with multiple progress updates work correctly.
     * This simulates a real-world scenario where code reports progress over time.
     */
    fun testLongRunningProgressReporting(): Unit = timeoutRunBlocking(90.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code that simulates a longer operation with multiple progress updates
        // Note: Using Thread.sleep for simulation since delay() may not be in classpath
        val code = """
                val items = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")

                for (i in items.indices) {
                    val item = items[i]
                    progress("Processing item " + (i + 1) + "/" + items.size + ": " + item)
                    Thread.sleep(100)
                }

                println("FINISHED: Processed " + items.size + " items")
        """.trim()

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-long-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", code)
                    put("reason", "Test long-running progress")
                    put("task_id", "long-progress-test")
                    put("timeout", 60) // Give it enough time
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== LONG-RUNNING PROGRESS RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should succeed
        assertFalse("Execution should succeed", execResult.isError)

        // Output should contain our completion message
        assertTrue(
            "Output should contain completion message, got: $execOutput",
            execOutput.contains("FINISHED: Processed 5 items")
        )

        // Should contain at least some progress messages
        // Note: progress is throttled to 1 message per second, so not all may appear
        assertTrue(
            "Output should contain progress messages, got: $execOutput",
            execOutput.contains("Processing item") || execOutput.contains("PROGRESS:")
        )
    }

    /**
     * Tests that unresolved reference errors are properly reported.
     * This happens when the agent uses an API that doesn't exist.
     */
    fun testUnresolvedReferenceReturnsErrorResponse(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // Execute code with an unresolved reference
        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-unresolved")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                            // This class doesn't exist
                            val x = NonExistentClass.doSomething()
                            println(x)
                    """.trimIndent())
                    put("reason", "Test unresolved reference handling")
                    put("task_id", "unresolved-ref-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())

        assertNull("JSON-RPC should not have protocol error", execRpc.error)
        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        // Print the actual response for visibility
        println("=== UNRESOLVED REFERENCE RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Execution should be marked as an error
        assertTrue("Execution should be marked as error for unresolved reference", execResult.isError)

        // Output should mention an unresolved reference
        assertTrue(
            "Output should mention unresolved reference, got: $execOutput",
            execOutput.contains("unresolved", ignoreCase = true) ||
                    execOutput.contains("NonExistentClass", ignoreCase = true) ||
                    execOutput.contains("reference", ignoreCase = true)
        )
    }

    /**
     * This test verifies that MCP execute_code can read a system property in the test JVM.
     * This verifies the MCP server runs in the same JVM and can access system properties.
     */
    fun testSystemPropertyCanBeReadViaMcp(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Set a system property with a random value
        val propertyKey = "mcp.test.random.value"
        val randomValue = "test-${UUID.randomUUID()}"
        System.setProperty(propertyKey, randomValue)

        try {
            // Initialize session
            val initResponse = client.post(server.mcpUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                setBody(buildInitializeRequest())
            }
            val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

            // Execute code that reads the system property
            val execRequest = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "exec-sysprop")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_execute_code")
                    putJsonObject("arguments") {
                        put("project_name", project.name)
                        put("code", $$"""
                                val value = System.getProperty("$$propertyKey")
                                println("SYSPROP_VALUE: $value")
                        """.trimIndent())
                        put("reason", "Test reading system property via MCP")
                        put("task_id", "sysprop-test")
                    }
                }
            }.toString()

            val execResponse = client.post(server.mcpUrl) {
                contentType(ContentType.Application.Json)
                accept(ContentType.Application.Json)
                header(McpHttpTransport.SESSION_HEADER, sessionId)
                setBody(execRequest)
            }

            assertEquals(HttpStatusCode.OK, execResponse.status)
            val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
            assertNull("Execute should not return error", execRpc.error)

            val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
            val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

            assertFalse("Execution should succeed, got: $execOutput", execResult.isError)
            assertTrue(
                "Output should contain the system property value '$randomValue', got: $execOutput",
                execOutput.contains("SYSPROP_VALUE: $randomValue")
            )
        } finally {
            // Clean up the system property
            System.clearProperty(propertyKey)
        }
    }

    /**
     * Tests that prompt articles are intentionally NOT exposed via MCP resources/list.
     * The dedicated `steroid_fetch_resource` tool is the only discovery path because it
     * requires `project_name` and so can render IDE-conditional content correctly.
     */
    fun testResourcesListIsEmptyAfterFetchResourcePromotion(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        // List resources
        val listResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"resources-list","method":"resources/list"}""")
        }

        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listRpc = McpJson.decodeFromString<JsonRpcResponse>(listResponse.bodyAsText())
        assertNull("resources/list should succeed", listRpc.error)

        val resourcesList = McpJson.decodeFromJsonElement<ResourcesListResult>(listRpc.result!!)
        val steroidEntries = resourcesList.resources.filter { it.uri.startsWith("mcp-steroid://") }
        assertTrue(
            "mcp-steroid:// articles must not appear in resources/list — they are reached via steroid_fetch_resource. Got: ${steroidEntries.map { it.uri }}",
            steroidEntries.isEmpty(),
        )
    }

    /**
     * After S6 dropped MCP prompt registration, prompts/list returns an
     * empty list — the steroid_fetch_resource tool is the only discovery
     * surface (it requires project_name for correct IDE-conditional
     * rendering, which prompts/get cannot supply).
     */
    fun testPromptsListIsEmptyAfterFetchResourcePromotion(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Initialize session
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]

        val listResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"prompts-list","method":"prompts/list"}""")
        }

        assertEquals(HttpStatusCode.OK, listResponse.status)
        val listRpc = McpJson.decodeFromString<JsonRpcResponse>(listResponse.bodyAsText())
        assertNull("prompts/list should succeed", listRpc.error)

        val promptsList = McpJson.decodeFromJsonElement<PromptsListResult>(listRpc.result!!)
        val steroidEntries = promptsList.prompts.filter { it.name.startsWith("mcp-steroid://") }
        assertTrue(
            "mcp-steroid:// articles must not appear in prompts/list — they are reached via steroid_fetch_resource. Got: ${steroidEntries.map { it.name }}",
            steroidEntries.isEmpty(),
        )
    }

    /**
     * Tests the complete MCP handshake that real agents perform:
     * 1. GET health check (with Claude CLI Accept header)
     * 2. POST initialize (get session ID)
     * 3. POST notifications/initialized (with session ID, no response expected)
     * 4. POST tools/list (with session ID)
     * 5. POST tools/call (with session ID)
     *
     * This test ensures the server handles the full lifecycle correctly,
     * including the notification step that creates no response body.
     */
    fun testFullAgentHandshakeWithNotification(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Step 1: GET health check (like Claude CLI does before connecting)
        val healthResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
        }
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        val healthBody = healthResponse.bodyAsText()
        assertTrue("Health check should return server name", healthBody.contains("mcp-steroid"))

        // Step 2: POST initialize
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header("User-Agent", "claude-code/2.0.67")
            setBody("""{"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{}},"clientInfo":{"name":"claude-code","version":"2.0.67"}},"jsonrpc":"2.0","id":0}""")
        }
        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must return session ID", sessionId)

        val initRpc = McpJson.decodeFromString<JsonRpcResponse>(initResponse.bodyAsText())
        assertNull("Initialize should not error", initRpc.error)
        val initResult = McpJson.decodeFromJsonElement<InitializeResult>(initRpc.result!!)
        assertEquals(MCP_PROTOCOL_VERSION, initResult.protocolVersion)

        // Step 3: POST notifications/initialized (no id = notification)
        val notifyResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"method":"notifications/initialized","jsonrpc":"2.0"}""")
        }
        assertEquals(
            "notifications/initialized should return 202 Accepted",
            HttpStatusCode.Accepted,
            notifyResponse.status
        )
        // No new session ID should be returned for notification with valid session
        assertNull(
            "No new session ID for notification with valid session",
            notifyResponse.headers[McpHttpTransport.SESSION_HEADER]
        )

        // Step 4: POST tools/list with session ID
        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        assertTrue("Should have steroid tools", toolsList.tools.any { it.name.startsWith("steroid_") })

        // Step 5: POST tools/call steroid_list_projects (with session ID)
        val callResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            header("Accept", "application/json, text/event-stream")
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }
        assertEquals(HttpStatusCode.OK, callResponse.status)
        val callRpc = McpJson.decodeFromString<JsonRpcResponse>(callResponse.bodyAsText())
        assertNull("tools/call should not return JSON-RPC error", callRpc.error)
        val callResult = McpJson.decodeFromJsonElement<ToolCallResult>(callRpc.result!!)
        assertFalse("steroid_list_projects should succeed", callResult.isError)
    }

    /**
     * Tests that the server correctly handles requests without Accept header.
     * While the MCP spec says clients MUST include Accept, the server should be
     * lenient for backwards compatibility (some tools/curl don't set it).
     */
    fun testPostWithoutAcceptHeader(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            // Deliberately NOT setting Accept header
            setBody(buildInitializeRequest())
        }

        assertEquals(
            "POST without Accept header should still succeed",
            HttpStatusCode.OK,
            initResponse.status
        )
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Should still create session", sessionId)
    }

    /**
     * Tests that forgetAllForTest() truly restarts the Ktor server, breaking
     * all existing HTTP connections and invalidating all sessions.
     *
     * After restart:
     * - Old session IDs are unknown (server creates new sessions)
     * - Server is reachable on the new port
     * - New sessions can be established and used
     */
    fun testServerRestartBreaksConnections(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val portBefore = server.port
        assertTrue("Server should be running", portBefore > 0)

        // Step 1: Establish a session and verify it works
        val sessionId = startSession(server)

        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"pre-restart","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, toolsResponse.status)
        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed before restart", toolsRpc.error)

        // Step 2: Restart the server
        server.forgetAllForTest()

        val portAfter = server.port
        assertTrue("Server should be running after restart", portAfter > 0)

        // Step 3: Verify old session ID is no longer known
        // The server creates a new session for unknown IDs (returns new Mcp-Session-Id)
        val oldSessionResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"post-restart","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, oldSessionResponse.status)
        val newSessionId = oldSessionResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server should assign a new session for the old (unknown) session ID", newSessionId)
        assertFalse(
            "New session ID should differ from the old one",
            newSessionId == sessionId
        )

        // Step 4: Verify the restarted server responds to health checks
        val healthResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        assertTrue(
            "Health check should return server info",
            healthResponse.bodyAsText().contains("mcp-steroid")
        )

        // Step 5: Verify a brand-new session can be established and used
        val freshSessionId = startSession(server)
        val freshToolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, freshSessionId)
            setBody("""{"jsonrpc":"2.0","id":"fresh","method":"tools/list"}""")
        }
        assertEquals(HttpStatusCode.OK, freshToolsResponse.status)
        val freshToolsRpc = McpJson.decodeFromString<JsonRpcResponse>(freshToolsResponse.bodyAsText())
        assertNull("tools/list should succeed with fresh session after restart", freshToolsRpc.error)
    }

    /**
     * Tests that the MCP-Protocol-Version header is included in responses.
     * Per MCP 2025-11-25 spec, the server should include this in all responses.
     */
    fun testProtocolVersionHeaderInResponses(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        // Check GET response
        val getResponse = client.get(server.mcpUrl) {
            header("Accept", "application/json")
        }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals(
            "GET response should include protocol version header",
            MCP_PROTOCOL_VERSION,
            getResponse.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        )

        // Check POST response
        val postResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildInitializeRequest())
        }
        assertEquals(HttpStatusCode.OK, postResponse.status)
        assertEquals(
            "POST response should include protocol version header",
            MCP_PROTOCOL_VERSION,
            postResponse.headers[McpHttpTransport.PROTOCOL_VERSION_HEADER]
        )
    }

    /**
     * Tests that a type mismatch compilation error produces an error response
     * with the actual compiler error message visible in the output.
     *
     * Uses the same code snippet as CliClaudeIntegrationTest.testCompilationErrorsDelivered
     * to verify the server-side behavior independently of any AI agent.
     */
    fun testTypeMismatchCompilationErrorContainsErrorMessage(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-type-mismatch")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val x: String = 123
                        println(x)
                    """.trimIndent())
                    put("reason", "Test type mismatch error message delivery")
                    put("task_id", "type-mismatch-error-test")
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val debug = DebugJson.encodeToString(execRpc)
        println("The whole output: $debug")

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== TYPE MISMATCH ERROR RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertTrue("Execution should be marked as error", execResult.isError)

        // The actual kotlinc error message must be present, not just generic "error"
        assertTrue(
            "Output should contain 'type mismatch' from the compiler, got: $execOutput",
            execOutput.contains("type mismatch", ignoreCase = true)
                    || execOutput.contains("Type mismatch", ignoreCase = true)
        )
        assertTrue(
            "Output should mention the expected type (String), got: $execOutput",
            execOutput.contains("String")
        )
        assertTrue(
            "Output should mention the actual type (Int), got: $execOutput",
            execOutput.contains("Int")
        )
    }

    /**
     * Tests that compilation errors are included in the tool result content items even
     * when a progress token is provided. The tool result is the delivery mechanism for
     * HTTP clients — all logMessage/logProgress/reportFailed content is added to it
     * synchronously before the HTTP response is sent.
     */
    fun testCompilationErrorWithProgressToken(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val progressToken = "progress-compile-error-${UUID.randomUUID()}"

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-compile-error-progress")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val x: String = 123
                        println(x)
                    """.trimIndent())
                    put("reason", "Test compilation error with progress token")
                    put("task_id", "compile-error-progress-test")
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== COMPILATION ERROR WITH PROGRESS TOKEN ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        assertTrue("Execution should be marked as error", execResult.isError)

        // Compilation error details must be in the tool result content
        assertTrue(
            "Output should contain 'type mismatch' from the compiler, got: $execOutput",
            execOutput.contains("type mismatch", ignoreCase = true)
        )
        assertTrue(
            "Output should mention the expected type (String), got: $execOutput",
            execOutput.contains("String")
        )
        assertTrue(
            "Output should mention the actual type (Int), got: $execOutput",
            execOutput.contains("Int")
        )

        // The FAILED marker should also be in the result (from reportFailed)
        assertTrue(
            "Output should contain FAILED marker, got: $execOutput",
            execOutput.contains("FAILED:")
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent for compilation error, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

    /**
     * Tests that compiler warnings are delivered in the tool result alongside the execution output.
     *
     * The code uses an unchecked cast (List<Any> to List<String>) which produces a compiler
     * warning on stderr. The test verifies that warnings appear in the tool result content items
     * together with the successful execution output.
     *
     * Uses the same code snippet as CliClaudeIntegrationTest.testCompilationWarningsDelivered.
     */
    fun testCompilationWarningsDeliveredOnSuccess(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val progressToken = "progress-warnings-${UUID.randomUUID()}"

        val execRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "exec-warnings")
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", project.name)
                    put("code", """
                        val items: List<Any> = listOf("hello", "world")
                        @Suppress("NOTHING_TO_SUPPRESS")
                        val strings: List<String> = items as List<String>
                        println("WARNING_TEST_VALUE: " + strings.joinToString(","))
                    """.trimIndent())
                    put("reason", "Test compiler warning delivery")
                    put("task_id", "warnings-test")
                    putJsonObject("_meta") {
                        put("progressToken", progressToken)
                    }
                }
            }
        }.toString()

        val execResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(execRequest)
        }

        assertEquals(HttpStatusCode.OK, execResponse.status)
        val execRpc = McpJson.decodeFromString<JsonRpcResponse>(execResponse.bodyAsText())
        assertNull("JSON-RPC should not have protocol error", execRpc.error)

        val execResult = McpJson.decodeFromJsonElement<ToolCallResult>(execRpc.result!!)
        val execOutput = execResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

        println("=== COMPILER WARNINGS RESPONSE ===")
        println("isError: ${execResult.isError}")
        println("Output:")
        println(execOutput)
        println("=== END RESPONSE ===")

        // Code should compile and execute successfully
        assertFalse("Execution should succeed (warnings don't block compilation), got: $execOutput", execResult.isError)

        // The println output should be present
        assertTrue(
            "Output should contain the execution result, got: $execOutput",
            execOutput.contains("WARNING_TEST_VALUE: hello,world")
        )

        // Compiler warnings should be present in the tool result
        assertTrue(
            "Output should contain compiler warnings (unchecked cast or @Suppress warning), got: $execOutput",
            execOutput.contains("warning", ignoreCase = true)
                    || execOutput.contains("Unchecked cast", ignoreCase = true)
                    || execOutput.contains("Compiler Errors/Warnings", ignoreCase = true)
        )

        // Verify progress notifications were sent to the session's notification channel
        val mcpSession = server.getServer().sessionManager.getSession(sessionId)
        assertNotNull("Session should still exist", mcpSession)
        val notifications = mcpSession!!.drainNotifications()
        val progressNotifications = notifications.filter { it.method == McpMethods.PROGRESS }

        println("--- Progress notifications (${progressNotifications.size}) ---")
        for (n in progressNotifications) {
            val p = McpJson.decodeFromJsonElement<ProgressParams>(n.params!!)
            println("  [${p.progress}] ${p.message}")
        }

        assertTrue(
            "At least one progress notification should have been sent for successful execution, got ${progressNotifications.size}",
            progressNotifications.isNotEmpty()
        )

        for (notification in progressNotifications) {
            val notifParams = McpJson.decodeFromJsonElement<ProgressParams>(notification.params!!)
            assertEquals(
                "Progress notification should use the provided token",
                JsonPrimitive(progressToken),
                notifParams.progressToken
            )
        }
    }

    /**
     * Pins the protocol contract under Claude's retry-storm pattern: when the agent's
     * 60s MCP-tool timeout fires while the server is still computing, the agent
     * re-issues the same tools/call (same session, same task_id, fresh JSON-RPC id).
     * The CliClaudeIntegrationTest hangs were traced to four parallel `steroid_execute_code`
     * requests piling up on a single ExecutionManager and contending on the VFS write-intent
     * lock inside ScriptExecutor's pre-flight awaitRefresh.
     *
     * This test fires N=4 concurrent tools/call requests on a single session and asserts:
     *  - every JSON-RPC id round-trips back unchanged (no cross-talk between requests),
     *  - every response carries `isError = false` and the marker text from its own task_id,
     *  - the whole burst completes well below Claude's per-tool 60s ceiling.
     *
     * It deliberately does NOT call kotlinc (the script is a one-line println) so the test
     * isolates HTTP/JSON-RPC fan-out + tool dispatch from kotlinc's per-call cold path.
     * A pure protocol regression — request id swap, session corruption, server-wide
     * serialization, head-of-line blocking — surfaces here as a test failure rather than
     * a 4-minute hang in the Cli suite.
     */
    fun testConcurrentToolCallsOnSingleSessionDoNotCrossTalk(): Unit = timeoutRunBlocking(60.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = startSession(server)

        val burstSize = 4
        val requests = (1..burstSize).map { idx ->
            val rpcId = "concurrent-exec-$idx"
            val taskId = "concurrent-task-$idx"
            val marker = "BURST_MARKER_$idx"
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", rpcId)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_execute_code")
                    putJsonObject("arguments") {
                        put("project_name", project.name)
                        put("code", """println("$marker")""")
                        put("reason", "Concurrent burst #$idx")
                        put("task_id", taskId)
                    }
                }
            }.toString()
            Triple(rpcId, marker, body)
        }

        val responses: List<Triple<String, String, String>> = coroutineScope {
            requests.map { req ->
                async {
                    val response = client.post(server.mcpUrl) {
                        contentType(ContentType.Application.Json)
                        accept(ContentType.Application.Json)
                        header(McpHttpTransport.SESSION_HEADER, sessionId)
                        setBody(req.third)
                    }
                    Triple(req.first, req.second, response.bodyAsText())
                }
            }.awaitAll()
        }

        for ((rpcId, marker, raw) in responses) {
            val rpc = McpJson.decodeFromString<JsonRpcResponse>(raw)
            assertEquals(
                "JSON-RPC id must round-trip without cross-talk for $marker",
                JsonPrimitive(rpcId),
                rpc.id,
            )
            assertNull("$marker: no protocol error expected, got ${rpc.error}", rpc.error)
            val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
            val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
            assertFalse("$marker: execution should succeed, got error payload: $text", result.isError)
            assertTrue(
                "$marker: response payload must contain its own marker (cross-talk would put another request's marker here): $text",
                text.contains(marker),
            )
        }

        // Cross-talk negative check: each marker must appear in EXACTLY ONE response payload.
        for ((_, marker, _) in responses) {
            val occurrences = responses.count { (_, _, raw) -> raw.contains(marker) }
            assertEquals("Marker $marker appeared in $occurrences responses; expected exactly 1", 1, occurrences)
        }
    }

}
