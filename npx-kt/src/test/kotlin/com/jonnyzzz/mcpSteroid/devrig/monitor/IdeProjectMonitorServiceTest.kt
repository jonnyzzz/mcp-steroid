/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.devrig.waitForValue
import com.jonnyzzz.mcpSteroid.server.DEVRIG_RPC_PATH_PREFIX
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end round-trip: a tiny Ktor server stands in for the IDE, we point
 * an [IdePidDiscoveryService] at a fake `~/.mcp-steroid/markers/<pid>.mcp-steroid` marker, and
 * verify that [IdeProjectMonitorService] connects, fetches the IDE's project
 * snapshot via `GET <rpcBaseUrl>/projects`, and surfaces it on
 * [IdeProjectMonitorService.stateSnapshot].
 */
class IdeProjectMonitorServiceTest {

    private val ourPid = ProcessHandle.current().pid()

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private lateinit var scope: CoroutineScope
    private var port: Int = 0

    /** Per-test handle for the project list the fake server returns from `/projects`. */
    private val projectsJson = StringBuilder()

    /** Authorization headers seen by the fake server, indexed by request. */
    private val receivedAuthHeaders = mutableListOf<String?>()

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("$DEVRIG_RPC_PATH_PREFIX/projects") {
                    receivedAuthHeaders += call.request.headers["Authorization"]
                    call.respondText(projectsJson.toString(), ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `monitor receives the IDE's snapshot and surfaces it on states`(
        @TempDir homeDir: Path,
    ) = runBlocking {
        // Marker pointing the discovery layer at our fake IDE.
        writeMarker(homeDir, port)

        // The project list the fake IDE will return on /projects.
        projectsJson.append(projectsResponse(listOf(project("alpha", "/p/alpha"))))

        val discovery = IdePidDiscoveryService(
            markersDir = PidMarker.markerDirectory(homeDir),
            allowHosts = listOf("127.0.0.1"),
        )
        val monitor = IdeProjectMonitorService(
            httpClient = httpClient,
            discovery = discovery,
        )

        val ide = waitForValue(10.seconds.inWholeMilliseconds) {
            monitor.stateSnapshot().firstOrNull {
                it.ide.pid == ourPid && it.projects.isNotEmpty()
            }
        }

        assertEquals(listOf(IdeProjectState("alpha", "/p/alpha")), ide.projects)

        assertTrue(
            receivedAuthHeaders.any { it == "Bearer deadbeef" },
            "expected the monitor to send Authorization: Bearer <token>; saw: $receivedAuthHeaders"
        )
    }

    private fun writeMarker(homeDir: Path, port: Int, token: String = "deadbeef") {
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = ourPid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:$port/mcp",
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:$port/mcp", mapOf("Authorization" to "Bearer $token")),
            ide = IdeInfo(name = "FakeIDE", version = "x", build = "y"),
            plugin = PluginInfo(id = "x", name = "y", version = "z"),
            createdAt = "2026-05-10T12:34:56Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        val markerDir = PidMarker.markerDirectory(homeDir)
        Files.createDirectories(markerDir)
        File(markerDir.toFile(), PidMarker.markerFileNameFor(ourPid))
            .writeText(PidMarkerJson.encode(marker))
    }

    /** One project entry in the shape the monitor's `/projects` parser expects. */
    private fun project(name: String, path: String): String =
        """{"name":"$name","path":"$path","backend_name":"","project_name":"$name"}"""

    private fun projectsResponse(projects: List<String>): String =
        """{"projects":[${projects.joinToString(",")}]}"""

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
