/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.devrig.monitor.AboutResponse
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.notExists
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BackendProvisionTest {
    private val parser = Json { ignoreUnknownKeys = true }

    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0L, 0L) }
    }

    @Test
    fun `provision parser target ids are stable`() {
        assertEquals("port-63342", provisionTargetId(63342))
        assertEquals("devrig backend provision port-63342", provisionCommand("port-63342"))
    }

    @Test
    fun `mac IDEA Ultimate api-about maps to IntelliJIdea selector plugins folder`(@TempDir tempDir: Path) {
        val about = AboutResponse(
            name = "IntelliJ IDEA 2026.1.1",
            productName = "IDEA",
            baselineVersion = 261,
            buildNumber = "261.23567.138",
        )

        val selector = deriveIdePathSelector(about, productCode = "IU")
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.MAC,
            userHome = tempDir,
            env = emptyMap(),
        )

        assertEquals("IntelliJIdea2026.1", selector.selector)
        assertEquals("JetBrains", selector.vendor)
        assertEquals(tempDir.resolve("Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins"), path)
        assertEquals("IntelliJ IDEA Ultimate", provisionTargetProductName(about, selector.selector))
        assertEquals("2026.1.1", provisionTargetVersion(about))
    }

    @Test
    fun `windows PyCharm Community maps through product code to APPDATA plugins folder`() {
        val about = AboutResponse(
            name = "PyCharm Community Edition 2025.3",
            productName = "PyCharm",
            baselineVersion = 253,
            buildNumber = "253.1",
        )

        val selector = deriveIdePathSelector(about, productCode = "PC")
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.WINDOWS,
            userHome = Path.of("C:\\Users\\ada"),
            env = mapOf("APPDATA" to "C:\\Users\\ada\\AppData\\Roaming"),
        )

        assertEquals("PyCharmCE2025.3", selector.selector)
        assertEquals("C:\\Users\\ada\\AppData\\Roaming\\JetBrains\\PyCharmCE2025.3\\plugins", path.toString())
    }

    @Test
    fun `linux GoLand maps to XDG data plugin directory without an extra plugins segment`() {
        val about = AboutResponse(
            name = "GoLand 2026.1",
            productName = "GoLand",
            baselineVersion = 261,
            buildNumber = "GO-261.10",
        )

        val selector = deriveIdePathSelector(about)
        val path = defaultIdePluginsDir(
            selector = selector.selector,
            vendor = selector.vendor,
            os = HostOs.LINUX,
            userHome = Path.of("/home/ada"),
            env = mapOf("XDG_DATA_HOME" to "/xdg/data"),
        )

        assertEquals("GoLand2026.1", selector.selector)
        assertEquals(Path.of("/xdg/data/JetBrains/GoLand2026.1"), path)
    }

    @Test
    fun `provision list text prints action command next to each port-discovered IDE`() {
        val text = renderProvisionText(
            listOf(
                ProvisionTarget(
                    id = "port-63342",
                    ide = portIde(port = 63342, productFullName = "IntelliJ IDEA 2026.1.1", buildNumber = "261.23567.138"),
                ),
            )
        )

        assertTrue(text.contains("Port-discovered IDEs that can be provisioned:"), text)
        assertTrue(text.contains("port-63342"), text)
        assertTrue(text.contains("run: devrig backend provision port-63342"), text)
    }

    @Test
    fun `provision list json exposes actions array`() {
        val root = renderProvisionJson(
            listOf(
                ProvisionTarget(
                    id = "port-63342",
                    ide = portIde(port = 63342),
                ),
            )
        )
        assertEquals(setOf("tool", "targets"), root.keys)
        val target = root["targets"]!!.jsonArray.single().jsonObject
        assertEquals("port-63342", target["id"]!!.jsonPrimitive.content)
        val action = target["actions"]!!.jsonArray.single().jsonObject
        assertEquals("provision", action["id"]!!.jsonPrimitive.content)
        assertEquals("devrig backend provision port-63342", action["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provision command list keeps all port rows when no markers are discovered`() {
        val text = renderProvisionCommandText(
            listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
                ProvisionTarget(id = "port-63343", ide = portIde(port = 63343, buildNumber = "GO-261.1")),
            ),
        )

        assertTrue(text.contains("port-63342"), text)
        assertTrue(text.contains("port-63343"), text)
        assertTrue(text.contains("Port-discovered IDEs that can be provisioned:"), text)
        assertFalse(text.contains("All running IDEs already have MCP Steroid installed."), text)
    }

    @Test
    fun `provision command list hides a port row whose build matches a marker`() {
        val text = renderProvisionCommandText(
            targets = listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
                ProvisionTarget(id = "port-63343", ide = portIde(port = 63343, productFullName = "GoLand 2026.1", productName = "GoLand", buildNumber = "GO-261.1")),
            ),
            markers = listOf(markerIde(build = "IU-261.23567.138")),
        )

        assertFalse(text.contains("port-63342"), text)
        assertTrue(text.contains("port-63343"), text)
        assertTrue(text.contains("Port-discovered IDEs that can be provisioned:"), text)
    }

    @Test
    fun `provision command list reports when all port rows are already provisioned`() {
        val text = renderProvisionCommandText(
            targets = listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
                ProvisionTarget(id = "port-63343", ide = portIde(port = 63343, buildNumber = "GO-261.1")),
            ),
            markers = listOf(
                markerIde(pid = 11, build = "IU-261.23567.138"),
                markerIde(pid = 12, name = "GoLand", build = "GO-261.1"),
            ),
        )

        assertTrue(text.contains("All running IDEs already have MCP Steroid installed."), text)
        assertTrue(text.contains("Run \"devrig backend\" to see them."), text)
        assertFalse(text.contains("Port-discovered IDEs that can be provisioned:"), text)
        assertFalse(text.contains("port-63342"), text)
        assertFalse(text.contains("port-63343"), text)
    }

    @Test
    fun `provision command list does not promote marker rows that have no matching port row`() {
        val text = renderProvisionCommandText(
            targets = listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
            ),
            markers = listOf(markerIde(name = "GoLand", build = "GO-261.1")),
        )

        assertTrue(text.contains("port-63342"), text)
        assertFalse(text.contains("GoLand"), text)
        assertTrue(text.contains("Port-discovered IDEs that can be provisioned:"), text)
    }

    @Test
    fun `provision command json filters targets and reports discovery note`() {
        val root = renderProvisionCommandJson(
            targets = listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
                ProvisionTarget(id = "port-63343", ide = portIde(port = 63343, buildNumber = "GO-261.1")),
            ),
            markers = listOf(markerIde(build = "IU-261.23567.138")),
        )

        val targets = root["targets"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("port-63343"), targets)
        assertEquals(
            "Filtered 1 entries already provisioned. Use 'backend --json' for the full set.",
            root["discoveryNote"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `provision command json omits discovery note when no filtering is needed`() {
        val root = renderProvisionCommandJson(
            targets = listOf(
                ProvisionTarget(id = "port-63342", ide = portIde(port = 63342, buildNumber = "261.23567.138")),
            ),
            markers = listOf(markerIde(name = "GoLand", build = "GO-261.1")),
        )

        val targets = root["targets"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("port-63342"), targets)
        assertFalse("discoveryNote" in root.keys, root.toString())
    }

    @Test
    fun `provision resolves port id and computes manual instruction paths without installing plugin`(@TempDir tempDir: Path) = runBlocking {
        val sourcePlugin = tempDir.resolve("source-plugin.zip")
        Files.writeString(sourcePlugin, "fake plugin zip")
        val port = ServerSocket(0).use { it.localPort }
        val installPluginCalls = mutableListOf<Unit>()
        val server = ideServer(
            port = port,
            aboutBody = """
                {
                  "name": "IntelliJ IDEA 2026.1.1",
                  "productName": "IDEA",
                  "edition": null,
                  "baselineVersion": 261,
                  "buildNumber": "261.23567.138"
                }
            """.trimIndent(),
            installPluginCalls = installPluginCalls,
        )
        servers += server
        val httpClient = httpClient()
        clients += httpClient

        val provisioner = BackendProvisioner(
            bundledPluginResolver = FixedBundledPluginResolver(sourcePlugin),
            os = HostOs.MAC,
            userHome = tempDir.resolve("home"),
            env = emptyMap(),
            portRanges = listOf(port..port),
        )

        val result = provisioner.provision("port-$port", httpClient)

        assertEquals("IntelliJIdea2026.1", result.selector)
        assertEquals(null, result.productCode)
        assertEquals(sourcePlugin, result.pluginSource)
        assertEquals(tempDir.resolve("home/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins"), result.pluginsDir)
        assertEquals(tempDir.resolve("home/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/mcp-steroid"), result.suggestedDestination)
        assertTrue(result.suggestedDestination.notExists(), "provision must not create the suggested destination")
        assertTrue(installPluginCalls.isEmpty(), "provision must not call /api/installPlugin")
    }

    @Test
    fun `provision action text prints manual install instructions and exits zero`(@TempDir tempDir: Path) {
        val result = provisionResult(tempDir)
        val buf = ByteArrayOutputStream()

        val exit = runBackendProvisionCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            command = DevrigCommand.DevrigCommandBackendProvision(id = "port-63342"),
            provision = { result },
        )
        val text = buf.toString(Charsets.UTF_8)

        assertEquals(0, exit)
        assertTrue(text.contains("Target: IntelliJ IDEA Ultimate 2026.1.1 (port 63342)"), text)
        assertTrue(text.contains("MCP Steroid is not installed in this IDE. To install:"), text)
        assertTrue(text.contains("Settings → Plugins → Marketplace → search \"MCP Steroid\" → Install"), text)
        assertTrue(text.contains("Plugin source on this machine:\n        ${result.pluginSource}"), text)
        assertTrue(text.contains("Suggested install path:\n        ${result.suggestedDestination}"), text)
        assertTrue(text.contains("the actual plugins folder may differ if the user customised it"), text)
        assertFalse(text.contains("plugin installed at"), text)
        assertFalse(text.contains("already provisioned"), text)
    }

    @Test
    fun `provision json reports manual instructions with marketplace and files steps`(@TempDir tempDir: Path) {
        val result = provisionResult(tempDir)
        val buf = ByteArrayOutputStream()
        val exit = runBackendProvisionCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            command = DevrigCommand.DevrigCommandBackendProvision(id = "port-63342", json = true),
            provision = { result },
        )
        val root = parser.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject

        assertEquals(0, exit)
        assertEquals(setOf("tool", "action", "id", "target", "instructions", "note"), root.keys)
        assertEquals("provision", root["action"]!!.jsonPrimitive.content)
        assertEquals("port-63342", root["id"]!!.jsonPrimitive.content)
        assertFalse("method" in root.keys)

        val target = root["target"]!!.jsonObject
        assertEquals("IntelliJ IDEA Ultimate", target["productName"]!!.jsonPrimitive.content)
        assertEquals("2026.1.1", target["version"]!!.jsonPrimitive.content)
        assertEquals("261.23567.138", target["buildNumber"]!!.jsonPrimitive.content)
        assertEquals("63342", target["port"]!!.jsonPrimitive.content)

        val instructions = root["instructions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("marketplace", "files"), instructions.map { it["step"]!!.jsonPrimitive.content })
        assertEquals("Use Settings → Plugins → Marketplace from within the IDE.", instructions[0]["description"]!!.jsonPrimitive.content)
        assertEquals(result.pluginSource.toString(), instructions[1]["pluginSource"]!!.jsonPrimitive.content)
        assertEquals(result.suggestedDestination.toString(), instructions[1]["suggestedDestination"]!!.jsonPrimitive.content)
        assertEquals(
            "Suggested destination assumes the default plugins directory; user-customised paths require manual adjustment.",
            root["note"]!!.jsonPrimitive.content,
        )
    }

    private fun provisionResult(tempDir: Path) = ProvisionResult(
        id = "port-63342",
        ide = portIde(port = 63342),
        about = AboutResponse(
            name = "IntelliJ IDEA 2026.1.1",
            productName = "IDEA",
            baselineVersion = 261,
            buildNumber = "261.23567.138",
        ),
        productCode = "IU",
        selector = "IntelliJIdea2026.1",
        pluginsDir = tempDir.resolve("plugins"),
        pluginSource = tempDir.resolve("ij-plugin.zip"),
        suggestedDestination = tempDir.resolve("plugins/mcp-steroid"),
    )

    private fun renderProvisionText(rows: List<ProvisionTarget>): String {
        val buf = ByteArrayOutputStream()
        renderBackendProvisionListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderProvisionJson(rows: List<ProvisionTarget>) = parser
        .parseToJsonElement(
            ByteArrayOutputStream().also { buf ->
                renderBackendProvisionListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
            }.toString(Charsets.UTF_8),
        ).jsonObject

    private fun renderProvisionCommandText(
        targets: List<ProvisionTarget>,
        markers: List<DiscoveredIde> = emptyList(),
    ): String {
        val buf = ByteArrayOutputStream()
        runBackendProvisionListCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            json = false,
            targets = { targets },
            markers = { markers },
        )
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderProvisionCommandJson(
        targets: List<ProvisionTarget>,
        markers: List<DiscoveredIde> = emptyList(),
    ) = parser.parseToJsonElement(
        ByteArrayOutputStream().also { buf ->
            runBackendProvisionListCommand(
                out = PrintStream(buf, true, Charsets.UTF_8),
                json = true,
                targets = { targets },
                markers = { markers },
            )
        }.toString(Charsets.UTF_8),
    ).jsonObject

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2026.1.1",
        pid: Long = 1234L,
        build: String = "IU-261.23567.138",
        mcpUrl: String = "http://localhost:6315/mcp",
    ): DiscoveredIde {
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = IdeInfo(name = name, version = version, build = build),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test"),
            backendName = "mock-backend-name",
        )
    }

    private fun portIde(
        port: Int,
        productFullName: String? = "IntelliJ IDEA 2026.1.1",
        productName: String? = "IDEA",
        buildNumber: String? = "261.23567.138",
        baselineVersion: Int? = 261,
        edition: String? = null,
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    private fun httpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
    }

    private fun ideServer(
        port: Int,
        aboutBody: String,
        installPluginCalls: MutableList<Unit>,
    ): EmbeddedServer<*, *> {
        val server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    call.respondText(aboutBody, ContentType.Application.Json)
                }
                get("/api/installPlugin") {
                    installPluginCalls += Unit
                    call.respondText("{}", ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }
        return server
    }

    private class FixedBundledPluginResolver(private val zip: Path) : BundledPluginResolver {
        override fun resolveBundledPluginZip(): Path = zip
    }
}
