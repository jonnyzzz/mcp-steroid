/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.setServerPortProperties
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for #214: one hung read action plus one queued write action must NOT
 * wedge the trivial steroid tools. Project-name resolution ([ProjectScopedToolHandler.resolveProject],
 * [describeSelfBackend]) is lock-free by design — a suspend `readAction {}` there would park behind
 * the pending write action for as long as the hung read action lives, turning `steroid_fetch_resource`
 * and `steroid_list_projects` into dead 60 s client timeouts.
 *
 * The wedge is simulated exactly as observed in the field: a background thread holds a read action
 * (the stuck inspection script), then a write action is queued on the EDT and parks behind it
 * (`isWriteActionPending == true`). While wedged, the resolution paths and both HTTP tool calls
 * must complete within a short timeout.
 */
class ProjectResolutionLockFreeTest : BasePlatformTestCase() {

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

    fun testToolsRespondWhileReadActionHeldAndWriteActionQueued(): Unit = timeoutRunBlocking(120.seconds) {
        val server = SteroidsMcpServer.getInstance()
        server.startServerIfNeeded()
        val sessionId = initializeSession(server)
        // Resolve the app service BEFORE wedging the lock so service instantiation cost/locking
        // cannot leak into the wedged-window assertions below.
        val projectScoped = service<ProjectScopedToolHandler>()
        val application = ApplicationManager.getApplication()
        val applicationEx = ApplicationManagerEx.getApplicationEx()

        val readHeld = CompletableDeferred<Unit>()
        val releaseRead = AtomicBoolean(false)
        // A raw thread (not coroutine code) blocked inside runReadAction — this is the
        // "hung inspection script" from #214: the read permit stays held until released.
        val readHolder = thread(name = "mcp-steroid-test-read-action-holder") {
            application.runReadAction {
                readHeld.complete(Unit)
                while (!releaseRead.get()) {
                    Thread.sleep(10)
                }
            }
        }
        val writeCompleted = CompletableDeferred<Unit>()
        try {
            withTimeout(10.seconds) { readHeld.await() }

            // Queue a write action behind the held read action. The EDT runnable starts acquiring
            // the write lock, cannot get it while the read permit is held, and from that moment
            // every NEW suspend readAction {} parks (platform lock policy) — the wedge.
            application.invokeLater {
                application.runWriteAction { }
                writeCompleted.complete(Unit)
            }
            withTimeout(10.seconds) {
                while (!applicationEx.isWriteActionPending) {
                    delay(10)
                }
            }

            // 1. resolveProject completes while wedged (would park in a readAction before #214).
            val projectKey = projectNameFor(project)
            val resolved = projectScoped.resolveProject(projectKey)
            assertSame("resolveProject must return the open fixture project", project, resolved)

            // 2. describeSelfBackend (list_projects / list_windows backend) completes while wedged.
            val self = describeSelfBackend()
            assertTrue(
                "describeSelfBackend must list the fixture project",
                self.projects.any { it.projectName == projectKey },
            )

            // 3. steroid_fetch_resource responds normally through the full HTTP tool path.
            val skillUri = com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle().uri
            val fetchResult = withTimeout(15.seconds) {
                callTool(server, sessionId, "steroid_fetch_resource", buildJsonObject {
                    put("uri", skillUri)
                    put("project_name", projectKey)
                })
            }
            assertFalse("steroid_fetch_resource must succeed while wedged", fetchResult.isError)
            val fetchText = fetchResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
            assertTrue("steroid_fetch_resource must return the skill guide", fetchText.contains("MCP Steroid"))

            // 4. steroid_list_projects responds normally through the full HTTP tool path.
            val listResult = withTimeout(15.seconds) {
                callTool(server, sessionId, "steroid_list_projects", buildJsonObject { })
            }
            assertFalse("steroid_list_projects must succeed while wedged", listResult.isError)
            val listText = listResult.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
            assertTrue("steroid_list_projects must list the fixture project", listText.contains(projectKey))

            // The write action must STILL be pending — proves every call above completed during
            // the starvation window, not after it accidentally resolved.
            assertFalse("write action must not have run while the read action is held", writeCompleted.isCompleted)
            assertTrue("write action must still be pending", applicationEx.isWriteActionPending)
        } finally {
            releaseRead.set(true)
            while (readHolder.isAlive) {
                delay(10)
            }
            // Let the queued write action drain so tearDown gets a healthy EDT.
            withTimeout(30.seconds) { writeCompleted.await() }
        }
    }

    fun testResolveProjectNotFoundListsAvailableProjectNames() {
        try {
            service<ProjectScopedToolHandler>().resolveProject("no-such-project-214")
            fail("resolveProject must throw ToolCallErrorException for an unknown project name")
        } catch (e: ToolCallErrorException) {
            val message = e.message ?: ""
            assertTrue(
                "not-found message must keep the pre-#214 shape, got: $message",
                message.startsWith("Project not found: \"no-such-project-214\". Available project_name values: "),
            )
            assertTrue(
                "not-found message must list the open project_name keys, got: $message",
                message.contains(projectNameFor(project)),
            )
        }
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
                        put("name", "lock-free-resolution-test-client")
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

    private suspend fun callTool(
        server: SteroidsMcpServer,
        sessionId: String,
        toolName: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val response = client.post(server.mcpUrl) {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            header(McpHttpTransport.SESSION_HEADER, sessionId)
            setBody(buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "call-$toolName")
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", toolName)
                    put("arguments", arguments)
                }
            }.toString())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val rpc = McpJson.decodeFromString<JsonRpcResponse>(response.bodyAsText())
        assertNull("$toolName should not return JSON-RPC error", rpc.error)
        return McpJson.decodeFromJsonElement(rpc.result!!)
    }
}
