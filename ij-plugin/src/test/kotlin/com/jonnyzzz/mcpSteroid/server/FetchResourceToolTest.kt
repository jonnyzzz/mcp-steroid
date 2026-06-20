/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.setServerPortProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the steroid_fetch_resource MCP tool.
 * Verifies that agents can fetch MCP Steroid resources by URI via the tool protocol.
 */
class FetchResourceToolTest : BasePlatformTestCase() {

    private lateinit var client: HttpClient

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
        client = HttpClient(CIO) {
            expectSuccess = false
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 60_000
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

    fun testFetchResourceReturnsSkillGuide(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)
        val skillUri = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
        val result = callFetchResource(server, sessionId, skillUri)

        assertFalse("steroid_fetch_resource should succeed", result.isError)
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("Response should be non-empty", text.isNotBlank())
        assertTrue("Response should contain skill guide content", text.contains("MCP Steroid"))
    }

    fun testFetchResourceToolIsAdvertised(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        val toolsResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"tools-list","method":"tools/list"}""")
        }

        val toolsRpc = McpJson.decodeFromString<JsonRpcResponse>(toolsResponse.bodyAsText())
        assertNull("tools/list should succeed", toolsRpc.error)
        val toolsList = McpJson.decodeFromJsonElement<ToolsListResult>(toolsRpc.result!!)
        val toolNames = toolsList.tools.map { it.name }.toSet()
        assertTrue(
            "steroid_fetch_resource should be in the tool list",
            toolNames.contains("steroid_fetch_resource")
        )
    }

    fun testFetchResourceWithNonExistentUri(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)
        // Use a URI that follows the protocol but doesn't match any registered resource
        val result = callFetchResource(server, sessionId, "mcp-steroid://nonexistent/resource-that-does-not-exist")

        assertTrue("Should return error for non-existent resource", result.isError)
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        assertTrue("Error message should mention resource not found", text.contains("not found"))
    }

    fun testFetchResourceWithMissingUri(): Unit = timeoutRunBlocking(30.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()

        val sessionId = initializeSession(server)

        // Call with empty arguments (no uri parameter)
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "fetch-missing-uri")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_fetch_resource")
                    putJsonObject("arguments") {}
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(
            McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText()).result!!
        )

        assertTrue("Should return error for missing uri", result.isError)
        val text = result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
        // Pin the specific branch — the schema DSL throws
        // 'Parameter <name> of type <type> is required' for missing required
        // params, so naming both 'uri' and the type pins the specific branch
        // (project_name-missing would say 'Parameter project_name ...').
        assertTrue(
            "Error message should specifically mention the missing uri parameter, got: $text",
            text.contains("Parameter uri of type string is required"),
        )
    }

    private suspend fun initializeSession(server: SteroidsMcpServer): String {
        val initResponse = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "init-1")
                put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", MCP_PROTOCOL_VERSION)
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") {
                        put("name", "fetch-resource-test-client")
                        put("version", "1.0.0")
                    }
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, initResponse.status)
        val sessionId = initResponse.headers[McpHttpTransport.SESSION_HEADER]
        assertNotNull("Server must issue an MCP session ID", sessionId)
        return sessionId!!
    }

    /**
     * #92: the routing key FetchResourceToolHandler requires is the within-IDE-unique `project_name`
     * from steroid_list_projects (`<rawName>-<hash>`), NOT the raw `Project.name`. PromptsContextHandlerIJ
     * resolves it via OpenProjectsService.resolveProject, which matches ONLY the unique name (no raw
     * fallback). Obtain it the way a real agent would — call steroid_list_projects and read `project_name`.
     */
    private suspend fun resolveUniqueProjectName(server: SteroidsMcpServer, sessionId: String): String {
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody("""{"jsonrpc":"2.0","id":"resolve-project-name","method":"tools/call","params":{"name":"steroid_list_projects"}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("steroid_list_projects should not return JSON-RPC error", rpc.error)
        val result = McpJson.decodeFromJsonElement<ToolCallResult>(rpc.result!!)
        assertFalse("steroid_list_projects should succeed", result.isError)
        val payload = (result.content.single() as ContentItem.Text).text
        val projects = McpJson.decodeFromString<ListProjectsResponse>(payload)
        val entry = projects.projects.firstOrNull { it.name == project.name }
            ?: error("Fixture project '${project.name}' not found in steroid_list_projects: ${projects.projects.map { it.name }}")
        return entry.projectName
    }

    private suspend fun callFetchResource(
        server: SteroidsMcpServer,
        sessionId: String,
        uri: String,
    ): ToolCallResult {
        // `project_name` is required by FetchResourceToolHandler and resolved via
        // OpenProjectsService.resolveProject — pass the unique routing key (#92), exactly
        // like a real agent that read it from steroid_list_projects.
        val uniqueProjectName = resolveUniqueProjectName(server, sessionId)
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "fetch-resource-1")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", "steroid_fetch_resource")
                    putJsonObject("arguments") {
                        put("uri", uri)
                        put("project_name", uniqueProjectName)
                    }
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("steroid_fetch_resource should not return JSON-RPC error", rpc.error)
        return McpJson.decodeFromJsonElement(rpc.result!!)
    }
}
