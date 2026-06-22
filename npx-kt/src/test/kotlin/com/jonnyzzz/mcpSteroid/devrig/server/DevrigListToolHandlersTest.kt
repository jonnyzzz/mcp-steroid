/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendInfo
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendState
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
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
 * devrig MCP list handlers ([DevrigListProjectsToolHandler] / [DevrigListWindowsToolHandler]):
 *  - `backends[]` is exactly the routing-discovered backends — port-only and managed backends are a CLI
 *    concern (`devrig backend`) and never leak onto the MCP surface;
 *  - every `steroid_list_windows` window/background-task carries the backend_name of its source IDE;
 *  - the inventory's own dead-pid liveness downgrade is asserted on the rich CLI [BackendInventory] surface.
 */
class DevrigListToolHandlersTest {
    private var server: EmbeddedServer<*, *>? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0L, 0L)
        server = null
    }

    @Test
    fun `list_projects backends are exactly the routing-discovered backends (no port or managed leak)`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homeA = Files.createDirectories(tempDir.resolve("alpha"))
        val withProject = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1"),
            projects = listOf(IdeProjectState("alpha", homeA.toString())),
        )
        // A discovered IDE with NO open project is still a routable backend, so it must appear in backends[].
        val idle = IdeMonitorState(ide = discoveredIde(pid = 43, build = "IU-253.9"))
        val routing = DevrigProjectRoutingService { listOf(withProject, idle) }

        val response = DevrigListProjectsToolHandler(routing).collectListProjectsResponse()

        val name42 = backendNameForMarker(42L, "IU-261.1")
        val name43 = backendNameForMarker(43L, "IU-253.9")

        // backends[] = every discovered IDE (incl. the idle one), and nothing else — these are all marker
        // backend_names (`iu-…`); no port/managed entry is ever synthesized onto this surface.
        assertEquals(setOf(name42, name43), response.backends.map { it.backendName }.toSet())
        assertTrue(response.backends.all { it.backendName.startsWith("iu-") }, "$response")

        // Identity fields are carried from the IDE.
        val b42 = response.backends.single { it.backendName == name42 }
        assertEquals("IntelliJ IDEA", b42.displayName)
        assertEquals("IU-261.1", b42.build)
        assertEquals("2026.1", b42.version)

        // projects[] only lists the IDE that actually has a project open, tagged with its own backend_name.
        assertEquals(listOf(name42), response.projects.map { it.backendName })
        assertEquals(listOf("alpha"), response.projects.map { it.name })
    }

    @Test
    fun `managed backend with a dead pid degrades to unreachable without any http fetch`(
        @TempDir tempDir: Path,
    ): Unit = runBlocking {
        // RUNNING per its (stale) pid file, but the process is dead. The liveness check must settle this
        // BEFORE any HTTP — there is no marker or port source to fetch from, so the only path to a
        // not-running verdict is the inventory's own ProcessHandle-style liveness downgrade. Managed
        // backends are CLI-only, so this is asserted on the inventory's rich BackendInfo (the
        // `devrig backend --json` surface), not on the routing-backed MCP `steroid_list_projects`.
        val managed = managedBackendInfo(tempDir, runningPid = 99999L, state = ManagedBackendState.RUNNING)
        val inventory = BackendInventory(
            routing = DevrigProjectRoutingService { emptyList() },
            portDiscovery = { emptySet() },
            managedBackends = { listOf(managed) },
            isProcessAlive = { false },
        )

        val backend = inventory.collectBackendInfos().single()

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
            ide = discoveredIde(pid = 42, build = "IU-261.1", port = port, token = "token-42"),
            projects = listOf(IdeProjectState("project-42", homeA.toString())),
        )
        val stateB = IdeMonitorState(
            ide = discoveredIde(pid = 43, build = "IU-253.9", port = port, token = "token-43"),
            projects = listOf(IdeProjectState("project-43", homeB.toString())),
        )
        val states = listOf(stateA, stateB)
        val routing = DevrigProjectRoutingService { states }

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
                    bridge = DevrigToolBridgeClient(it),
                    routing = routing,
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
            val route = routing.routes().single { it.route.pid == pid }
            assertEquals(route.exposedProjectName, window.projectName)
        }
        // backends[] = the routing-discovered backends, joined by the same names.
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
        port: Int = 0,
        token: String = "token-$pid",
    ): DiscoveredIde = DiscoveredIde(
        pid = pid,
        rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
        bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
        ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
        plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        backendName = backendNameForMarker(pid, build),
    )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
