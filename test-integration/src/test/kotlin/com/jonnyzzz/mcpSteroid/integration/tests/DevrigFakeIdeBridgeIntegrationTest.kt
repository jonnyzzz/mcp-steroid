/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.DevrigEndpointInfo
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.DEVRIG_RPC_PATH_PREFIX
import com.jonnyzzz.mcpSteroid.server.NPX_NDJSON_MIME_TYPE
import com.jonnyzzz.mcpSteroid.server.NpxBridgeToolCallRequest
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.NpxStreamEnvelope
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Dockerized end-to-end check that `devrig mpc` discovers a single IDE via its
 * `~/.mcp-steroid/markers/<pid>.mcp-steroid` marker and routes tool calls — but NOT the local
 * `resources/list` / `prompts/list` surfaces — through the devrig→IDE bridge described by the marker.
 *
 * Everything runs in a [DevrigContainer] (the standard devrig test container), so devrig's home is the
 * container-natural `/home/agent/.mcp-steroid` and nothing touches the developer's real `~/.mcp-steroid`.
 * The IDE is faked two ways:
 *  - a tiny JDK [HttpServer] "bridge" runs on the HOST bound to `0.0.0.0`; the in-container devrig reaches
 *    it via `host.docker.internal` (auto-added by the docker-start helper);
 *  - the marker is written into the container's home and claims **pid 1** (always alive in the container
 *    PID namespace), so devrig's marker liveness check keeps it.
 *
 * Moved from `:npx-kt`'s host-launcher `CliMcpStdioFakeIdeIntegrationTest` (which ran devrig on the host
 * and polluted `~/.mcp-steroid`); this version reuses [DevrigContainer] instead of re-implementing it.
 */
class DevrigFakeIdeBridgeIntegrationTest {

    private val lifetime = CloseableStackHost(DevrigFakeIdeBridgeIntegrationTest::class.java.simpleName)

    // The fake IDE's pid is the container's pid 1 — always present in the container PID namespace, so
    // devrig (IdeDiscovery) does not drop the marker for a dead pid.
    private val fakeIdePid = 1L
    private val seq = AtomicLong(0)
    private val bridgeToolCallCount = AtomicLong(0)
    private val bridgeStopped = AtomicBoolean(false)
    private var server: HttpServer? = null
    private var port: Int = 0
    private var receivedToolCall: NpxBridgeToolCallRequest? = null
    @Volatile private var receivedProjectsStreamAuth: String? = null
    @Volatile private var receivedWindowsAuth: String? = null
    @Volatile private var receivedToolAuth: String? = null

    // A path STRING reported to devrig as the open project. It must EXIST in the container: devrig
    // canonicalizes it via Path.toRealPath() (DevrigProjectRoutingService.canonicalProjectHome).
    private val fakeProjectPath = "/home/agent/sample-project"

    @AfterEach
    fun tearDown() {
        bridgeStopped.set(true)
        server?.stop(0)
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun devrigStdioDiscoversFakeIdeAndRoutesToolCallThroughBridge() {
        val container = DevrigContainer.create(lifetime, DevrigContainerOpts(consoleTitle = "devrig fake IDE bridge"))
        val scope = container.scope

        startFakeIdeBridge() // host bridge on 0.0.0.0; container reaches it via host.docker.internal

        // Project dir must exist (toRealPath). Then forge the fake-IDE marker in the container's home,
        // pointing the bridge URLs at the host bridge through host.docker.internal.
        scope.mkdirs(fakeProjectPath)
        scope.mkdirs("/home/agent/.mcp-steroid/markers")
        scope.writeFileInContainer(
            "/home/agent/.mcp-steroid/markers/${PidMarker.markerFileNameFor(fakeIdePid)}",
            PidMarkerJson.encode(buildMarker()),
        )

        val process = startStdioMcpProcess(lifetime, resourceName = "container-devrig-fake-ide") { stdin: Flow<ByteArray> ->
            scope.startProcessInContainer {
                args(container.devrig, "mpc")
                    .interactive()
                    .stdin(stdin)
                    .timeoutSeconds(300)
                    .description("devrig mpc against fake IDE bridge")
                    .quietly()
            }
        }
        process.initialize()

        val projectName = waitForProjectName(process)
        assertTrue(projectName.startsWith("sample-"), "project name should include hash suffix: $projectName")
        assertEquals(false, projectName == "sample", "project name must carry a hash suffix: $projectName")

        // Since S6 (commit 919e1e03) devrig exposes no MCP prompts/resources — these list endpoints must
        // answer empty and be served LOCALLY by devrig, never routed to the IDE bridge.
        val resources = process.request("resources/list", buildJsonObject {})
        assertNull(resources["error"], "resources/list returned error: $resources")
        assertEquals(0, resources["result"]?.jsonObject?.get("resources")?.jsonArray?.size, "no MCP resources: $resources")

        val prompts = process.request("prompts/list", buildJsonObject {})
        assertNull(prompts["error"], "prompts/list returned error: $prompts")
        assertEquals(0, prompts["result"]?.jsonObject?.get("prompts")?.jsonArray?.size, "no MCP prompts: $prompts")

        assertEquals(0L, bridgeToolCallCount.get(), "Local MCP methods must not route tool calls to the bridge")

        val result = toolCall(process, "steroid_execute_code", buildJsonObject {
            put("project_name", projectName)
            put("task_id", "fake-ide")
            put("reason", "verify bridge routing")
            put("code", "println(1)")
        })
        assertFalse(isToolError(result), "execute_code routed result must not be an error: $result")
        assertEquals("Bearer fake-token", receivedProjectsStreamAuth)
        assertEquals("Bearer fake-token", receivedToolAuth)
        assertEquals("steroid_execute_code", receivedToolCall?.name)
        assertEquals("sample", receivedToolCall?.arguments?.get("project_name")?.jsonPrimitive?.content)
        assertEquals(1L, bridgeToolCallCount.get(), "steroid_execute_code should have routed to the bridge")

        val windowsResult = toolCall(process, "steroid_list_windows", buildJsonObject {})
        assertEquals("Bearer fake-token", receivedWindowsAuth)
        val windowsText = textContent(windowsResult)
        val window = McpJson.parseToJsonElement(windowsText).jsonObject["windows"]?.jsonArray?.single()?.jsonObject
            ?: error("list_windows result missing windows: $windowsText")
        // devrig rewrites only the window's projectName to the routed (hash-suffixed) name; windowId is
        // forwarded as-is (DevrigProjectRoutingService.rewriteWindow — "window_id is unique within the IDE
        // resolved by project_name; forward it as-is").
        assertEquals(projectName, window["projectName"]?.jsonPrimitive?.content)
        assertEquals("fake-window", window["windowId"]?.jsonPrimitive?.content, "windowId must be forwarded as-is: $window")
    }

    private fun waitForProjectName(process: StdioMcpProcess): String {
        repeat(120) {
            val result = toolCall(process, "steroid_list_projects", buildJsonObject {})
            val projects = McpJson.parseToJsonElement(textContent(result)).jsonObject["projects"]?.jsonArray
                ?: error("list_projects result missing projects: $result")
            if (projects.isNotEmpty()) {
                return projects.single().jsonObject["name"]?.jsonPrimitive?.content
                    ?: error("project missing name: ${projects.single()}")
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for the fake IDE project to appear in steroid_list_projects")
    }

    private fun toolCall(process: StdioMcpProcess, name: String, arguments: JsonObject): JsonObject {
        val response = process.request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis = 20_000,
        )
        assertNull(response["error"], "tools/call returned error: $response")
        return response["result"]?.jsonObject ?: error("tools/call response missing result: $response")
    }

    private fun isToolError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true

    private fun textContent(result: JsonObject): String =
        result["content"]?.jsonArray?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: error("tool result missing text content: $result")

    /** Tiny host-side fake of the IDE's devrig bridge, using the JDK's built-in HttpServer (no ktor). */
    private fun startFakeIdeBridge() {
        val srv = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0)
        srv.executor = Executors.newCachedThreadPool()
        port = srv.address.port

        srv.createContext("$DEVRIG_RPC_PATH_PREFIX/projects/stream") { ex ->
            receivedProjectsStreamAuth = ex.requestHeaders.getFirst("Authorization")
            ex.requestBody.use { it.readBytes() }
            ex.responseHeaders.add("Content-Type", NPX_NDJSON_MIME_TYPE)
            ex.sendResponseHeaders(200, 0)
            val os = ex.responseBody
            os.write((NpxStreamJson.encodeEnvelope(snapshot()) + "\n").toByteArray())
            os.flush()
            // Hold the stream open like the real IDE bridge does, until the test tears the server down.
            while (!bridgeStopped.get()) Thread.sleep(50)
            try { os.close() } catch (e: Exception) { System.err.println("fake bridge stream close: ${e.message}") }
        }

        srv.createContext("$DEVRIG_RPC_PATH_PREFIX/windows") { ex ->
            receivedWindowsAuth = ex.requestHeaders.getFirst("Authorization")
            respondJson(ex, McpJson.encodeToString(NpxBridgeWindowsResponse.serializer(), windowsResponse()))
        }

        srv.createContext("$DEVRIG_RPC_PATH_PREFIX/tools/call/stream") { ex ->
            bridgeToolCallCount.incrementAndGet()
            receivedToolAuth = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.use { it.readBytes() }.decodeToString()
            receivedToolCall = McpJson.decodeFromString(NpxBridgeToolCallRequest.serializer(), body)
            // devrig reads this stream as NDJSON (see DevrigBridgeToolHandlers.readNdjson): one JSON object
            // per line, dispatched on its `type` ("progress" | "result" | "error"). NOT SSE.
            ex.responseHeaders.add("Content-Type", NPX_NDJSON_MIME_TYPE)
            ex.sendResponseHeaders(200, 0)
            val result = ToolCallResult(listOf(ContentItem.Text("routed-ok")))
            val ndjson = buildJsonObject {
                put("type", "result")
                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
            }.toString() + "\n"
            ex.responseBody.use { it.write(ndjson.toByteArray()); it.flush() }
        }

        srv.start()
        server = srv
    }

    private fun respondJson(ex: HttpExchange, json: String) {
        val bytes = json.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun snapshot(): NpxStreamEnvelope =
        NpxStreamEnvelope(
            type = "snapshot",
            instanceId = "fake-instance",
            seq = seq.incrementAndGet(),
            sentAt = Instant.now().toString(),
            pid = fakeIdePid,
            projects = listOf(ProjectInfo("sample", fakeProjectPath)),
        )

    private fun windowsResponse(): NpxBridgeWindowsResponse =
        NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "sample",
                    projectPath = fakeProjectPath,
                    title = "Fake IDE",
                    isActive = true,
                    isVisible = true,
                    bounds = null,
                    windowId = "fake-window",
                )
            ),
            backgroundTasks = emptyList(),
            pid = fakeIdePid,
            mcpUrl = "http://host.docker.internal:$port/mcp",
            instanceId = "fake-instance",
            seq = seq.incrementAndGet(),
            schemaVersion = "1",
            updatedAt = Instant.now().toString(),
        )

    private fun buildMarker(): PidMarker =
        PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = fakeIdePid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://host.docker.internal:$port/mcp",
                headers = mapOf("Authorization" to "Bearer fake-token"),
            ),
            devrigEndpoint = DevrigEndpointInfo(
                rpcBaseUrl = "http://host.docker.internal:$port$DEVRIG_RPC_PATH_PREFIX",
                headers = mapOf("Authorization" to "Bearer fake-token"),
            ),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
            createdAt = Instant.now().toString(),
            intellijWebServer = null,
            intellijMcpServer = null,
        )
}
