/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.server.DevrigEndpointInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

class InstallPluginCommandTest {
    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
    }

    @Test
    fun `install plugin list combines marker and port ides`(@TempDir tempDir: Path) {
        val markersDir = tempDir.resolve("markers")
        Files.createDirectories(markersDir)

        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = 100,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://localhost:6315/mcp",
                headers = emptyMap(),
            ),
            devrigEndpoint = DevrigEndpointInfo(
                rpcBaseUrl = "http://localhost:6315/api/jonnyzzz/mcp-steroid/v1",
                headers = emptyMap(),
            ),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2026.1", build = "IU-261.23567.138"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            createdAt = "2026-06-12T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        Files.writeString(markersDir.resolve("100.mcp-steroid"), markerInfoJson(marker))

        val portIde = DiscoveredIdeByPort(
            port = 63343,
            baseUrl = "http://127.0.0.1:63343",
            productName = "WebStorm",
            productFullName = "WebStorm 2026.1.3",
            edition = null,
            baselineVersion = 261,
            buildNumber = "261.25134.101",
        )

        val buf = ByteArrayOutputStream()
        val exit = runInstallPluginListCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            ides = setOf(DiscoveredIde(100, "http://localhost:6315/api/jonnyzzz/mcp-steroid/v1", emptyMap(), markersDir.resolve("100.mcp-steroid").toString(), marker)),
            portIdes = setOf(portIde),
        )
        val text = buf.toString(Charsets.UTF_8)

        assertEquals(0, exit)
        assertContains(text, "pid-100")
        assertContains(text, "port-63343")
        assertContains(text, "WebStorm 2026.1.3")
    }

    @Test
    fun `install plugin pid- resolves marker ide and installs plugin`(@TempDir tempDir: Path) {
        val pluginZip = tempDir.resolve("ij-plugin.zip")
        Files.writeString(pluginZip, "fake plugin zip")

        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = 100,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://localhost:6315/mcp",
                headers = emptyMap(),
            ),
            devrigEndpoint = DevrigEndpointInfo(
                rpcBaseUrl = "http://localhost:6315/api/jonnyzzz/mcp-steroid/v1",
                headers = emptyMap(),
            ),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2026.1", build = "IU-261.23567.138"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            createdAt = "2026-06-12T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        val markersDir = tempDir.resolve("markers")
        Files.createDirectories(markersDir)
        Files.writeString(markersDir.resolve("100.mcp-steroid"), markerInfoJson(marker))
        val discoveryDir = markersDir.resolve("discovery")
        Files.createDirectories(discoveryDir)
        Files.writeString(discoveryDir.resolve("100-ide-instance.json"), """{"pid":100,"paths":{"config":"$tempDir/config","plugins":"$tempDir/plugins"},"ideInfo":{"productCode":""},"properties":{}}""")

        val ides = setOf(DiscoveredIde(100, "http://localhost:6315/api/jonnyzzz/mcp-steroid/v1", emptyMap(), markersDir.resolve("100.mcp-steroid").toString(), marker))
        val installDir = tempDir.resolve("plugins/mcp-steroid")

        val out = ByteArrayOutputStream()
        val exit = runInstallPluginPid("pid-100", ides, pluginZip, markersDir)
        assertEquals(0, exit)
        assertContains(out.toString(Charsets.UTF_8), "Plugin installed successfully")
        assertTrue(installDir.isDirectory())
    }

    @Test
    fun `install plugin port- installs plugin directly`(@TempDir tempDir: Path) {
        val pluginZip = tempDir.resolve("ij-plugin.zip")
        Files.writeString(pluginZip, "fake plugin zip")

        val portIde = DiscoveredIdeByPort(
            port = 63343,
            baseUrl = "http://127.0.0.1:63343",
            productName = "WebStorm",
            productFullName = "WebStorm 2026.1.3",
            edition = null,
            baselineVersion = 261,
            buildNumber = "261.25134.101",
        )

        val out = ByteArrayOutputStream()
        val exit = runInstallPluginPort("port-63343", setOf(portIde), pluginZip)
        assertEquals(0, exit)
        assertContains(out.toString(Charsets.UTF_8), "Plugin installed successfully")
    }

    @Test
    fun `install plugin unknown id returns 64`() {
        val exit = runInstallPluginUnknown("pid-999")
        assertEquals(64, exit)
    }

    private fun runInstallPluginListCommand(
        out: PrintStream,
        ides: Set<DiscoveredIde>,
        portIdes: Set<DiscoveredIdeByPort> = emptySet(),
    ): Int = com.jonnyzzz.mcpSteroid.devrig.runInstallPluginListCommand(out, ides, portIdes)

    private fun runInstallPluginPid(
        backendId: String,
        ides: Set<DiscoveredIde>,
        pluginSource: Path,
        markersDir: Path,
    ): Int {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return com.jonnyzzz.mcpSteroid.devrig.runInstallPluginCommand(
            out = PrintStream(out, true, Charsets.UTF_8),
            err = PrintStream(err, true, Charsets.UTF_8),
            backendId = backendId,
            homePaths = TestHomePaths(markersDir),
            httpClient = null,
            portDiscovery = null,
        )
    }

    private fun runInstallPluginPort(
        backendId: String,
        portIdes: Set<DiscoveredIdeByPort>,
        pluginSource: Path,
    ): Int {
        val homePaths = com.jonnyzzz.mcpSteroid.devrig.resolveHomePaths()
        val portDiscovery = RecordingPortDiscovery(portIdes)
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return com.jonnyzzz.mcpSteroid.devrig.runInstallPluginCommand(
            out = PrintStream(out, true, Charsets.UTF_8),
            err = PrintStream(err, true, Charsets.UTF_8),
            backendId = backendId,
            homePaths = homePaths,
            httpClient = null,
            portDiscovery = portDiscovery,
        ).also { clients += portDiscovery.httpClient }
    }

    private fun runInstallPluginUnknown(backendId: String): Int {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return com.jonnyzzz.mcpSteroid.devrig.runInstallPluginCommand(
            out = PrintStream(out, true, Charsets.UTF_8),
            err = PrintStream(err, true, Charsets.UTF_8),
            backendId = backendId,
        )
    }

    private class TestHomePaths(
        private val markersDir: Path,
    ) : com.jonnyzzz.mcpSteroid.devrig.HomePaths by com.jonnyzzz.mcpSteroid.devrig.resolveHomePaths() {
        override val markersDir: Path = markersDir
    }

    private class RecordingPortDiscovery(
        private val ports: Set<DiscoveredIdeByPort>,
        val httpClient: HttpClient = HttpClient(CIO),
    ) : IntelliJPortDiscovery(httpClient = httpClient) {
        override val detected: kotlinx.coroutines.flow.StateFlow<Set<DiscoveredIdeByPort>> =
            kotlinx.coroutines.flow.MutableStateFlow(ports)

        override suspend fun scanOnce() {
        }

        override fun close() {
        }
    }

    private fun markerInfoJson(marker: PidMarker): String {
        return buildJsonObject {
            put("schema", marker.schema)
            put("pid", marker.pid)
            put("mcpSteroidServer", buildJsonObject {
                put("mcpUrl", marker.mcpSteroidServer.mcpUrl)
                put("headers", buildJsonObject {})
            })
            put("devrigEndpoint", buildJsonObject {
                put("rpcBaseUrl", marker.devrigEndpoint?.rpcBaseUrl ?: "")
                put("headers", buildJsonObject {})
            })
            put("ide", buildJsonObject {
                put("name", marker.ide.name)
                put("version", marker.ide.version)
                put("build", marker.ide.build)
            })
            put("plugin", buildJsonObject {
                put("id", marker.plugin.id)
                put("name", marker.plugin.name)
                put("version", marker.plugin.version)
            })
            put("createdAt", marker.createdAt)
        }.toString()
    }
}
