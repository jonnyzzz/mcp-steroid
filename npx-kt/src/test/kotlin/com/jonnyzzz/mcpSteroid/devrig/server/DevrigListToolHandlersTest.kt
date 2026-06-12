/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendRow
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendInfo
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendState
import com.jonnyzzz.mcpSteroid.devrig.backendNameForRow
import com.jonnyzzz.mcpSteroid.devrig.backendNameForPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir

/**
 * W2 (#89) — devrig MCP handlers over the shared [BackendInventory]:
 *  - `steroid_list_projects` backends[] carries ALL inventory rows (markers + port-only + managed);
 *  - a managed row with a dead pid degrades to not-running with NO HTTP involved;
 *  - every `steroid_list_windows` window/background-task carries the backend_name of its source pid.
 */
class DevrigListToolHandlersTest {
    private var server: EmbeddedServer<*, *>? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0L, 0L)
        server = null
    }

    @Test
    fun `list_projects backends include port and managed inventory rows as non routable entries`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("alpha"))
        val state = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1", projectHome = projectHome),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("alpha", projectHome.toString())),
        )
        val portIde = DiscoveredIdeByPort(
            port = 63342,
            baseUrl = "http://127.0.0.1:63342",
            productName = "PyCharm",
            productFullName = "PyCharm 2026.1",
            edition = "Professional",
            baselineVersion = 261,
            buildNumber = "261.5555.1",
        )
        val managed = managedBackendInfo(tempDir, runningPid = null, state = ManagedBackendState.INSTALLED)
        val routing = DevrigProjectRoutingService { mapOf(42L to state) }
        val inventory = BackendInventory(
            markerRows = { listOf(BackendRow.FromMarker(ide = state.ide, projects = state.lastSnapshot)) },
            portIdes = { setOf(portIde) },
            managedBackends = { listOf(managed) },
            isProcessAlive = { true },
        )

        val response = DevrigListProjectsToolHandler(routing, inventory).collectListProjectsResponse()

        val markerName = backendNameForMarker(42L, "IU-261.1")
        val portName = backendNameForPort(63342, "261.5555.1")
        // Derive via the production rule (re-attaches productCode to the bare managed buildNumber).
        val managedName = backendNameForRow(BackendRow.FromManaged(managed))
        assertEquals(
            setOf(markerName, portName, managedName),
            response.backends.map { it.backendName }.toSet(),
        )

        val markerBackend = response.backends.single { it.backendName == markerName }
        assertEquals(true, markerBackend.routable)
        assertEquals("marker", markerBackend.source)
        assertEquals(response.projects.map { it.path }.toSet(), markerBackend.openProjects.map { it.path }.toSet())

        val portBackend = response.backends.single { it.backendName == portName }
        assertEquals(false, portBackend.routable)
        assertEquals("port", portBackend.source)
        assertTrue(portBackend.openProjects.isEmpty(), "port rows own no projects: $portBackend")

        val managedBackend = response.backends.single { it.backendName == managedName }
        assertEquals(false, managedBackend.routable)
        assertEquals("managed", managedBackend.source)
        assertTrue(managedBackend.openProjects.isEmpty(), "managed rows own no projects: $managedBackend")

        // projects[] stays marker-routed only — the extra inventory rows never leak phantom projects.
        assertEquals(listOf(markerName), response.projects.map { it.backendName })
    }

    @Test
    fun `managed backend with dead pid is listed as not running without any http fetch`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // RUNNING per its (stale) pid file, but the process is dead. The liveness check must settle this
        // before any HTTP — here neither the marker nor the port source can do HTTP at all, so the only
        // way to a green assertion is the inventory's own ProcessHandle-style liveness downgrade.
        val managed = managedBackendInfo(tempDir, runningPid = 99999L, state = ManagedBackendState.RUNNING)
        val routing = DevrigProjectRoutingService { emptyMap() }
        val inventory = BackendInventory(
            markerRows = { emptyList() },
            portIdes = { emptySet() },
            managedBackends = { listOf(managed) },
            isProcessAlive = { false },
        )

        val response = DevrigListProjectsToolHandler(routing, inventory).collectListProjectsResponse()

        val backend = response.backends.single()
        assertEquals("managed", backend.source)
        assertEquals(false, backend.reachable)
        assertEquals(false, backend.routable)
        assertEquals(null, backend.pid)
        val detail = backend.managedDetail ?: error("managed row must carry managedDetail: $backend")
        assertEquals("unreachable", detail.state)
        assertEquals(null, detail.runningPid)
    }

    @Test
    fun `list_windows binds every window and background task to its source pid backend_name`(
        @TempDir tempDir: Path,
    ) {
        val port = freePort()
        // The server is deliberately created OUTSIDE runBlocking: inside it, `embeddedServer` resolves
        // to the CoroutineScope extension, the server becomes a child of the test coroutine, and
        // runBlocking then waits forever for it (the AfterEach stop can never run). Top-level call ==
        // standalone server lifecycle, stopped in tearDown.
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("/api/jonnyzzz/mcp-steroid/v1/windows") {
                    // One embedded server plays both IDEs — the bearer token tells which pid is asked.
                    val pid = when (val auth = call.request.headers["Authorization"]) {
                        "Bearer token-42" -> 42L
                        "Bearer token-43" -> 43L
                        else -> error("unexpected Authorization: $auth")
                    }
                    call.respondText(windowsResponseJson(pid), ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }

        val homeA = Files.createDirectories(tempDir.resolve("a"))
        val homeB = Files.createDirectories(tempDir.resolve("b"))
        val stateA = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1", projectHome = homeA, port = port, token = "token-42"),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("project-42", homeA.toString())),
        )
        val stateB = IdeMonitorState(
            ide = discoveredIde(pid = 43, build = "IU-253.9", projectHome = homeB, port = port, token = "token-43"),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("project-43", homeB.toString())),
        )
        val states = listOf(stateA, stateB)
        val routing = DevrigProjectRoutingService { states.associateBy { it.ide.pid } }
        val inventory = BackendInventory(
            markerRows = { states.map { BackendRow.FromMarker(ide = it.ide, projects = it.lastSnapshot) } },
            portIdes = { emptySet() },
            managedBackends = { emptyList() },
            isProcessAlive = { true },
        )

        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 2_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        val response = httpClient.use {
            runBlocking {
                DevrigListWindowsToolHandler(
                    states = { states },
                    httpClient = it,
                    routing = routing,
                    inventory = inventory,
                ).collectListWindowsResponse()
            }
        }

        val name42 = backendNameForMarker(42L, "IU-261.1")
        val name43 = backendNameForMarker(43L, "IU-253.9")

        // Each window carries the backend_name of the pid whose bridge produced it.
        assertEquals(2, response.windows.size)
        assertEquals(name42, response.windows.single { it.windowId == "frame-42" }.backendName)
        assertEquals(name43, response.windows.single { it.windowId == "frame-43" }.backendName)
        // ...background tasks likewise.
        assertEquals(2, response.backgroundTasks.size)
        assertEquals(name42, response.backgroundTasks.single { it.title == "task-42" }.backendName)
        assertEquals(name43, response.backgroundTasks.single { it.title == "task-43" }.backendName)
        // Each window's project name is rewritten to the devrig-exposed form of ITS OWN backend's route.
        for (window in response.windows) {
            val pid = if (window.backendName == name42) 42L else 43L
            val route = routing.routes().values.single { it.idePid == pid }
            assertEquals(route.exposedProjectName, window.projectName)
        }
        // backends[] joins by the same names.
        assertEquals(setOf(name42, name43), response.backends.map { it.backendName }.toSet())
    }

    private fun windowsResponseJson(pid: Long): String = McpJson.encodeToString(
        NpxBridgeWindowsResponse.serializer(),
        NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "project-$pid",
                    projectPath = null,
                    title = "window of $pid",
                    isActive = true,
                    isVisible = true,
                    bounds = null,
                    windowId = "frame-$pid",
                ),
            ),
            backgroundTasks = listOf(
                ProgressTaskInfo(
                    title = "task-$pid",
                    text = "",
                    text2 = "",
                    fraction = null,
                    isIndeterminate = true,
                    isCancellable = false,
                    projectName = null,
                ),
            ),
            pid = pid,
            mcpUrl = "http://127.0.0.1/mcp",
            instanceId = "instance-$pid",
            seq = 1L,
            schemaVersion = "1",
            updatedAt = "2026-06-10T00:00:00Z",
        ),
    )

    private fun managedBackendInfo(
        tempDir: Path,
        runningPid: Long?,
        state: ManagedBackendState,
    ): ManagedBackendInfo = ManagedBackendInfo(
        id = "ideaIC-2026.1",
        productKey = "ideaIC",
        productCode = "IC",
        version = "2026.1",
        buildNumber = "261.1",
        installPath = tempDir.resolve("backends/ideaIC-2026.1"),
        cachePath = tempDir.resolve("caches/ideaIC-2026.1"),
        runningPid = runningPid,
        state = state,
    )

    private fun discoveredIde(
        pid: Long,
        build: String,
        projectHome: Path,
        port: Int = 0,
        token: String = "token-$pid",
    ): DiscoveredIde = DiscoveredIde(
        pid = pid,
        rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
        bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
        markerPath = projectHome.resolve("$pid.mcp-steroid").toString(),
        marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:$port/mcp",
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:$port/mcp", mapOf("Authorization" to "Bearer $token")),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
            createdAt = "2026-05-17T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        ),
    )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
