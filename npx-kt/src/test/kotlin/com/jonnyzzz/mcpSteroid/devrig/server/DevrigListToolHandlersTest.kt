/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.devrig.startTestHttpServer
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ProgressTaskInfo
import com.jonnyzzz.mcpSteroid.server.WindowInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.toIntelliJInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir

/**
 * devrig MCP list handlers ([DevrigListProjectsToolHandler] / [DevrigListWindowsToolHandler]):
 *  - every project in `steroid_list_projects` carries the backend_name of its source IDE;
 *  - every `steroid_list_windows` window/background-task carries the backend_name of its source IDE.
 */
class DevrigListToolHandlersTest {
    private var server: EmbeddedServer<*, *>? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0L, 0L)
        server = null
    }

    @Test
    fun `list_projects projects carry backend_name of their source ide`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homeA = Files.createDirectories(tempDir.resolve("alpha"))
        val withProject = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1"),
            projects = listOf(IdeProjectState("alpha", homeA.toString())),
        )
        // A discovered IDE with NO open project contributes no projects.
        val idle = IdeMonitorState(ide = discoveredIde(pid = 43, build = "IU-253.9"))
        val routing = DevrigProjectRoutingService { listOf(withProject, idle) }

        val response = DevrigListProjectsToolHandler(routing).collectListProjectsResponse()

        val name42 = backendNameForMarker(42L, "IU-261.1")

        // projects[] only lists the IDE that actually has a project open, tagged with its own backend_name.
        assertEquals(listOf(name42), response.projects.map { it.backendName })
        assertEquals(listOf("alpha"), response.projects.map { it.name })
    }

    @Test
    fun `list_projects backends is the referenced-only identity table sorted by backend_name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homeZ = Files.createDirectories(tempDir.resolve("zulu"))
        val homeA = Files.createDirectories(tempDir.resolve("alpha"))
        val homeM = Files.createDirectories(tempDir.resolve("mike"))
        // Deliberately unsorted snapshot: the "zulu" project first, backends in scan (not name) order.
        // Note the fixture markers carry no ideHome (the incompatible/old-plugin marker shape) — such
        // backends own routes, so they MUST still appear in the table (#155 membership rule).
        val ideZ = IdeMonitorState(
            ide = discoveredIde(pid = 99, build = "IU-261.1"),
            projects = listOf(
                IdeProjectState("zulu", homeZ.toString()),
                IdeProjectState("alpha", homeA.toString()),
            ),
        )
        val ideM = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "GO-261.2"),
            projects = listOf(IdeProjectState("mike", homeM.toString())),
        )
        // Running with zero open projects: owns no route => absent from backends[] (referenced-only).
        // Startable and port-only IDEs never enter the routing snapshot at all, so they are absent
        // structurally — their inventory is `devrig backend --json` (#151).
        val idle = IdeMonitorState(ide = discoveredIde(pid = 7, build = "IU-253.9"))
        val routing = DevrigProjectRoutingService { listOf(ideZ, ideM, idle) }

        val response = DevrigListProjectsToolHandler(routing).collectListProjectsResponse()

        // (d) projects[] sorted by project_name regardless of the routing-snapshot order.
        assertEquals(response.projects.map { it.projectName }.sorted(), response.projects.map { it.projectName })
        assertEquals(3, response.projects.size)

        // (a) every entry's backend_name resolves to exactly one backends[] element.
        for (project in response.projects) {
            assertEquals(1, response.backends.count { it.backendName == project.backendName })
        }

        // (b) every backends[] element is referenced by >=1 entry; (e) the idle backend is absent.
        val referenced = response.projects.map { it.backendName }.toSet()
        assertEquals(referenced, response.backends.map { it.backendName }.toSet())
        assertTrue(response.backends.none { it.backendName == idle.ide.backendName })

        // (c) de-duped (ideZ owns two projects yet appears once) + sorted by backend_name.
        assertEquals(response.backends.map { it.backendName }.distinct(), response.backends.map { it.backendName })
        assertEquals(response.backends.map { it.backendName }.sorted(), response.backends.map { it.backendName })

        // (f)+(g) each element's identity is the marker-decoded IdeInfo projected via toIntelliJInfo().
        val routedByName = listOf(ideZ, ideM).associateBy { it.ide.backendName }
        for (backend in response.backends) {
            assertEquals(routedByName.getValue(backend.backendName).ide.ide.toIntelliJInfo(), backend.intellij)
        }
    }

    @Test
    fun `list_projects name is the human folder name, not the IDE project_name hash`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homeA = Files.createDirectories(tempDir.resolve("dupproj"))
        // The IDE reports project_name as the opaque hash (ideProjectName) distinct from the folder name.
        val ide = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1"),
            projects = listOf(IdeProjectState("dupproj", homeA.toString(), ideProjectName = "dupproj-9fk2a0xq")),
        )
        val routing = DevrigProjectRoutingService { listOf(ide) }

        val project = DevrigListProjectsToolHandler(routing).collectListProjectsResponse().projects.single()

        // `name` is the raw folder name (informational), NOT originalProjectName (the IDE's project_name hash).
        assertEquals("dupproj", project.name)
        // `project_name` is the opaque routing key, and it must differ from the folder name.
        assertNotEquals("dupproj", project.projectName)
    }

    @Test
    fun `list_windows binds every window and background task to its source pid backend_name`(
        @TempDir tempDir: Path,
    ) {
        // startTestHttpServer binds port 0 and reports what it bound: no probe-then-bind window, and a
        // startup failure surfaces here instead of as a cancelled coroutine later (#477). It also keeps
        // the standalone (non-child) server lifecycle this test needs — see its KDoc.
        val started = startTestHttpServer {
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
        }
        server = started.server
        val port = started.port

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
            val route = routing.routes().single { it.route.processId == pid }
            assertEquals(route.exposedProjectName, window.projectName)
        }
        // #155: backends[] resolves both referenced backend_names — referenced-only, sorted, with the
        // marker-decoded identity projected via toIntelliJInfo().
        assertEquals(listOf(name42, name43).sorted(), response.backends.map { it.backendName })
        assertEquals(stateA.ide.ide.toIntelliJInfo(), response.backends.single { it.backendName == name42 }.intellij)
        assertEquals(stateB.ide.ide.toIntelliJInfo(), response.backends.single { it.backendName == name43 }.intellij)
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

    private fun discoveredIde(
        pid: Long,
        build: String,
        port: Int = 0,
        token: String = "token-$pid",
    ): DiscoveredIde = DiscoveredIde(
        processId = pid,
        rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
        bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
        ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
        plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        backendName = backendNameForMarker(pid, build),
    )
}


