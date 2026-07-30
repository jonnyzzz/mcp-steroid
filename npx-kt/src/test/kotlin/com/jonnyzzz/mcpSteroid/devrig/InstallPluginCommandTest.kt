/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstallPluginCommandTest {
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val clients = mutableListOf<HttpClient>()

    @AfterEach
    fun tearDown() {
        clients.forEach { it.close() }
        servers.forEach { it.stop(0L, 0L) }
    }

    // --- KtorPluginRestClient against a fake built-in server ---------------------------------------

    @Test
    fun `checkCompatibility parses the single-id compatible=true form`() = runBlocking {
        val port = freePort()
        servers += installPluginServer(port, compatibleBody = """{"compatible": true}""")
        val client = KtorPluginRestClient(httpClient())

        assertEquals(true, client.checkCompatibility("http://127.0.0.1:$port", MCP_STEROID_PLUGIN_ID))
    }

    @Test
    fun `checkCompatibility parses compatible=false`() = runBlocking {
        val port = freePort()
        servers += installPluginServer(port, compatibleBody = """{"compatible": false}""")
        val client = KtorPluginRestClient(httpClient())

        assertEquals(false, client.checkCompatibility("http://127.0.0.1:$port", MCP_STEROID_PLUGIN_ID))
    }

    @Test
    fun `checkCompatibility returns null when the IDE is unreachable`() = runBlocking {
        val client = KtorPluginRestClient(httpClient())
        // Nothing is bound on this port — a refused connection must surface as null, not an exception.
        assertNull(client.checkCompatibility("http://127.0.0.1:${freePort()}", MCP_STEROID_PLUGIN_ID))
    }

    @Test
    fun `requestInstall hits action=install with a localhost Origin and returns true on 200`() = runBlocking {
        val port = freePort()
        val recorded = RecordedRequests()
        servers += installPluginServer(port, compatibleBody = """{"compatible": true}""", recorded = recorded)
        val client = KtorPluginRestClient(httpClient())

        assertTrue(client.requestInstall("http://127.0.0.1:$port", MCP_STEROID_PLUGIN_ID))
        // The Origin header is what bypasses the IDE's host-trust dialog (isLocalhost) while the plugin
        // install modal still appears — assert we actually send it.
        assertEquals("http://localhost", recorded.installOrigin)
        assertTrue(recorded.installUri.orEmpty().contains("action=install"), recorded.installUri)
        assertTrue(recorded.installUri.orEmpty().contains("pluginId=com.jonnyzzz.mcp-steroid"), recorded.installUri)
    }

    // --- orchestrator (fake client) ---------------------------------------------------------------

    @Test
    fun `install fires the dialog for a compatible IDE and narrates the modal`() {
        val target = portTarget(63342)
        val fake = FakePluginRestClient(compatibility = { true })

        val (text, reports) = orchestrate(check = false, targets = listOf(target), client = fake)

        assertEquals(listOf("http://127.0.0.1:63342"), fake.installCalls)
        assertEquals(PluginInstallOutcome.REQUESTED, reports.single().outcome)
        // The user must be prepared for the native modal before it appears. The wording must match the
        // real IDE dialog title ("Choose Plugins to Install or Enable") — verified live on a 261 build.
        assertTrue(text.contains("\"Choose Plugins to Install or Enable\""), text)
        assertTrue(text.contains("never installs silently"), text)
        assertTrue(text.contains("asking the IDE to open its install dialog now"), text)
    }

    @Test
    fun `install skips an IDE whose build already matches a marker`() {
        val target = portTarget(63342, buildNumber = "261.23567.138")
        val markers = listOf(markerIde(build = "IU-261.23567.138"))
        val fake = FakePluginRestClient(compatibility = { true })

        val (text, reports) = orchestrate(check = false, targets = listOf(target), markers = markers, client = fake)

        assertTrue(fake.installCalls.isEmpty(), "already-provisioned IDE must not be asked to install")
        assertEquals(PluginInstallOutcome.ALREADY_INSTALLED, reports.single().outcome)
        assertTrue(text.contains("already has the MCP Steroid plugin"), text)
    }

    @Test
    fun `install reports incompatible and unreachable IDEs without firing a dialog`() {
        val compatible = portTarget(63342)
        val incompatible = portTarget(63343)
        val unreachable = portTarget(63344)
        val fake = FakePluginRestClient(compatibility = { baseUrl ->
            when (baseUrl) {
                "http://127.0.0.1:63342" -> true
                "http://127.0.0.1:63343" -> false
                else -> null
            }
        })

        val (text, reports) = orchestrate(
            check = false,
            targets = listOf(compatible, incompatible, unreachable),
            client = fake,
        )

        assertEquals(listOf("http://127.0.0.1:63342"), fake.installCalls)
        val byPort = reports.associate { it.ide.port to it.outcome }
        assertEquals(PluginInstallOutcome.REQUESTED, byPort[63342])
        assertEquals(PluginInstallOutcome.INCOMPATIBLE, byPort[63343])
        assertEquals(PluginInstallOutcome.UNREACHABLE, byPort[63344])
        assertTrue(text.contains("no compatible MCP Steroid build"), text)
        assertTrue(text.contains("could not reach the IDE"), text)
    }

    @Test
    fun `check mode never fires the install and shows no modal preamble`() {
        val target = portTarget(63342)
        val fake = FakePluginRestClient(compatibility = { true })

        val (text, reports) = orchestrate(check = true, targets = listOf(target), client = fake)

        assertTrue(fake.installCalls.isEmpty(), "--check must be read-only")
        assertEquals(PluginInstallOutcome.WOULD_REQUEST, reports.single().outcome)
        assertTrue(text.contains("no install dialog is shown"), text)
        assertFalse(text.contains("\"Choose Plugins to Install or Enable\""), text)
        assertTrue(text.contains("Run 'devrig install plugin'"), text)
    }

    @Test
    fun `no running IDE prints a helpful fallback`() {
        val fake = FakePluginRestClient(compatibility = { true })

        val (text, reports) = orchestrate(check = false, targets = emptyList(), client = fake)

        assertTrue(reports.isEmpty())
        assertTrue(text.contains("No running JetBrains IDE answered"), text)
        assertTrue(text.contains("Settings -> Plugins -> Marketplace"), text)
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun orchestrate(
        check: Boolean,
        targets: List<ProvisionTarget>,
        markers: List<DiscoveredIde> = emptyList(),
        client: PluginRestClient,
    ): Pair<String, List<PluginInstallReport>> {
        val buf = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val reports = runBlocking {
            installPluginIntoRunningIdes(
                out = PrintStream(buf, true, Charsets.UTF_8),
                err = PrintStream(err, true, Charsets.UTF_8),
                check = check,
                pluginId = MCP_STEROID_PLUGIN_ID,
                targets = targets,
                markers = markers,
                client = client,
            )
        }
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n") to reports
    }

    private fun portTarget(
        port: Int,
        buildNumber: String? = "261.23567.138",
        productFullName: String? = "IntelliJ IDEA 2026.1.1",
        productName: String? = "IDEA",
    ) = ProvisionTarget(
        id = provisionTargetId(port),
        ide = DiscoveredIdeByPort(
            port = port,
            baseUrl = "http://127.0.0.1:$port",
            productName = productName,
            productFullName = productFullName,
            edition = null,
            baselineVersion = 261,
            buildNumber = buildNumber,
        ),
    )

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2026.1.1",
        pid: Long = 1234L,
        build: String = "IU-261.23567.138",
    ): DiscoveredIde = DiscoveredIde(
        pid = pid,
        rpcBaseUrl = testDevrigEndpoint("http://localhost:6315/mcp").rpcBaseUrl,
        bridgeHeaders = emptyMap(),
        ide = IdeInfo(name = name, version = version, build = build),
        plugin = PluginInfo(id = MCP_STEROID_PLUGIN_ID, name = "MCP Steroid", version = "0.0.0-test"),
        backendName = "mock-backend-name-$pid",
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun httpClient(): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 5_000
            socketTimeoutMillis = 5_000
        }
        expectSuccess = false
    }.also { clients += it }

    private class RecordedRequests {
        var installOrigin: String? = null
        var installUri: String? = null
    }

    private fun installPluginServer(
        port: Int,
        compatibleBody: String,
        recorded: RecordedRequests = RecordedRequests(),
    ): EmbeddedServer<*, *> = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
        routing {
            get("/api/installPlugin") {
                val action = call.request.queryParameters["action"]
                if (action == "install") {
                    recorded.installOrigin = call.request.headers["Origin"]
                    recorded.installUri = call.request.uri
                    call.respondText("OK", ContentType.Text.Plain)
                } else {
                    call.respondText(compatibleBody, ContentType.Application.Json)
                }
            }
        }
    }.also { it.start(wait = false) }

    private class FakePluginRestClient(
        private val compatibility: (String) -> Boolean?,
        private val installAllowed: Boolean = true,
    ) : PluginRestClient {
        val installCalls = mutableListOf<String>()
        override suspend fun checkCompatibility(baseUrl: String, pluginId: String): Boolean? = compatibility(baseUrl)
        override suspend fun requestInstall(baseUrl: String, pluginId: String): Boolean {
            installCalls += baseUrl
            return installAllowed
        }
    }
}
