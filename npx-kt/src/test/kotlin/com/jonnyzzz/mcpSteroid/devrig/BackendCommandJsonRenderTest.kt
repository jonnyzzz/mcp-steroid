/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** Pins the schema of `devrig backend --json` against the new 3-group model. */
class BackendCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(
        s1: List<DiscoveredIde> = emptyList(),
        s2: Set<DiscoveredIdeByPort> = emptySet(),
        s3: List<InstalledBackend> = emptyList(),
    ): JsonObject {
        val buf = ByteArrayOutputStream()
        renderBackendJson3(s1, s2, s3, PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        return parser.parseToJsonElement(text).jsonObject
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
        ideHome: String? = "/mock/ide/home",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer test-token"),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = backendNameForMarker(pid, build),
            ideHome = ideHome,
        )
    }

    private fun portIde(
        port: Int = 63342,
        productFullName: String? = "IntelliJ IDEA Ultimate",
        productName: String? = "IDEA",
        buildNumber: String? = "IU-253.21581.142",
        baselineVersion: Int? = 253,
        edition: String? = "IU",
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    private fun installedBackend(
        id: String = "goland-2026.1",
        name: String = "GoLand",
        version: String = "2026.1",
        build: String = "GO-261.1",
        ideHome: String = "/home/user/.mcp-steroid/backends/goland-2026.1/bundle-goland-2026.1",
    ) = InstalledBackend(
        id = id,
        ide = IdeInfo(name = name, version = version, build = build),
        ideHome = ideHome,
        launcher = Path.of("$ideHome/bin/goland.sh"),
    )

    // ----------------------------- top-level shape -------------------------

    @Test
    fun `output is valid JSON with exactly tool mcpSteroidBackends otherIdes startableBackends at the top level`() {
        val root = render()
        assertEquals(setOf("tool", "mcpSteroidBackends", "otherIdes", "startableBackends"), root.keys)
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null so scripts can correlate against changelog")
    }

    @Test
    fun `empty inputs produce empty arrays for all three groups`() {
        val root = render()
        assertEquals(0, root["mcpSteroidBackends"]!!.jsonArray.size)
        assertEquals(0, root["otherIdes"]!!.jsonArray.size)
        assertEquals(0, root["startableBackends"]!!.jsonArray.size)
    }

    @Test
    fun `output has no banner -- pure JSON on stdout for safe piping`() {
        val buf = ByteArrayOutputStream()
        renderBackendJson3(emptyList(), emptySet(), emptyList(), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8).trim()
        assertTrue(text.startsWith("{"),
            "output must start with '{' (the JSON document); got first 60 chars: '${text.take(60)}'")
        assertTrue(text.endsWith("}"),
            "output must end with '}' — no trailing banner allowed; got last 60 chars: '${text.takeLast(60)}'")
    }

    // -------------------------- mcpSteroidBackends -------------------------

    @Test
    fun `mcpSteroidBackends entry has backend_name displayName build and pid`() {
        val ide = markerIde(pid = 1234L, build = "IU-253.21581.142")
        val root = render(s1 = listOf(ide))
        val backends = root["mcpSteroidBackends"]!!.jsonArray
        assertEquals(1, backends.size)
        val entry = backends.single().jsonObject

        assertEquals(backendNameForMarker(1234L, "IU-253.21581.142"), entry["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(markerBackendDisplayName(ide), entry["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", entry["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1234L, entry["pid"]?.jsonPrimitive?.long)
    }

    @Test
    fun `mcpSteroidBackends are sorted by backend_name`() {
        // Same ordering as the MCP backends[] lookup (#155), whatever the discovery order.
        val a = markerIde(pid = 1111L)
        val b = markerIde(pid = 2222L)
        val expected = listOf(a, b).map { it.backendName }.sorted()
        val backends = render(s1 = listOf(b, a))["mcpSteroidBackends"]!!.jsonArray
        assertEquals(expected, backends.map { it.jsonObject["backend_name"]?.jsonPrimitive?.contentOrNull })
    }

    @Test
    fun `multiple S1 entries are ordered as provided`() {
        val ide1 = markerIde(pid = 1L, build = "IU-261.1")
        val ide2 = markerIde(name = "PyCharm", pid = 2L, build = "PC-253.9")
        val backends = render(s1 = listOf(ide1, ide2))["mcpSteroidBackends"]!!.jsonArray
        assertEquals(2, backends.size)
        assertEquals(backendNameForMarker(1L, "IU-261.1"), backends[0].jsonObject["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(backendNameForMarker(2L, "PC-253.9"), backends[1].jsonObject["backend_name"]?.jsonPrimitive?.contentOrNull)
    }

    // -------------------------- otherIdes ----------------------------------

    @Test
    fun `otherIdes entry has backend_name displayName build and port`() {
        val ide = portIde(port = 63342, buildNumber = "IU-253.21581.142")
        val root = render(s2 = setOf(ide))
        val others = root["otherIdes"]!!.jsonArray
        assertEquals(1, others.size)
        val entry = others.single().jsonObject

        assertEquals(backendNameForPort(63342, "IU-253.21581.142"), entry["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals(portBackendDisplayName(ide), entry["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", entry["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals(63342, entry["port"]?.jsonPrimitive?.int)
    }

    @Test
    fun `port-discovered otherIdes entry carries compatible=false`() {
        val ide = portIde(port = 63342, buildNumber = "IU-253.21581.142")
        val root = render(s2 = setOf(ide))
        val entry = root["otherIdes"]!!.jsonArray.single().jsonObject
        assertEquals(false, entry["compatible"]?.jsonPrimitive?.boolean,
            "port-discovered otherIdes entry must carry compatible=false; entry=$entry")
    }

    @Test
    fun `otherIdes entry omits build when buildNumber is null`() {
        val ide = portIde(buildNumber = null)
        val root = render(s2 = setOf(ide))
        val entry = root["otherIdes"]!!.jsonArray.single().jsonObject
        assertNull(entry["build"], "absent buildNumber must not serialise as null: $entry")
    }

    @Test
    fun `otherIdes are sorted by port`() {
        val ide1 = portIde(port = 63350)
        val ide2 = portIde(port = 63342)
        val others = render(s2 = setOf(ide1, ide2))["otherIdes"]!!.jsonArray
        assertEquals(63342, others[0].jsonObject["port"]?.jsonPrimitive?.int)
        assertEquals(63350, others[1].jsonObject["port"]?.jsonPrimitive?.int)
    }

    // -------------------------- startableBackends --------------------------

    @Test
    fun `startableBackends entry has backend_name displayName build ideHome and id`() {
        val installed = installedBackend(id = "goland-2026.1", name = "GoLand", version = "2026.1", build = "GO-261.1")
        val root = render(s3 = listOf(installed))
        val startable = root["startableBackends"]!!.jsonArray
        assertEquals(1, startable.size)
        val entry = startable.single().jsonObject

        assertEquals(startableBackendName(installed), entry["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("GoLand 2026.1", entry["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("GO-261.1", entry["build"]?.jsonPrimitive?.contentOrNull)
        assertEquals(installed.ideHome, entry["ideHome"]?.jsonPrimitive?.contentOrNull)
        assertEquals("goland-2026.1", entry["id"]?.jsonPrimitive?.contentOrNull)
    }

    // -------------------------- mixed --------------------------------------

    @Test
    fun `all three groups present with correct entries`() {
        val s1 = listOf(markerIde(pid = 1L, build = "IU-253.21581.142"))
        // Use a different build for the port IDE so it is NOT deduped out (different product/build)
        val s2 = setOf(portIde(port = 63342, buildNumber = "GO-261.999"))
        val s3 = listOf(installedBackend())
        val root = render(s1 = s1, s2 = s2, s3 = s3)

        assertEquals(1, root["mcpSteroidBackends"]!!.jsonArray.size)
        assertEquals(1, root["otherIdes"]!!.jsonArray.size,
            "port IDE with different build must not be deduped by the marker build")
        assertEquals(1, root["startableBackends"]!!.jsonArray.size)
    }
    // -------------- Finding C: compatibility by ideHome ---------------------

    @Test
    fun `incompatible marker (no ideHome) goes to otherIdes with compatible=false`() {
        val incompatible = markerIde(pid = 1L, build = "IU-261.1", ideHome = null)
        val root = render(s1 = listOf(incompatible))
        assertEquals(0, root["mcpSteroidBackends"]!!.jsonArray.size,
            "incompatible marker must NOT be in mcpSteroidBackends")
        assertEquals(1, root["otherIdes"]!!.jsonArray.size,
            "incompatible marker must appear in otherIdes")
        val entry = root["otherIdes"]!!.jsonArray.single().jsonObject
        assertEquals(false, entry["compatible"]?.jsonPrimitive?.boolean,
            "incompatible entry must have compatible=false")
    }

    @Test
    fun `compatible marker (has ideHome) goes to mcpSteroidBackends with compatible=true`() {
        val compatible = markerIde(pid = 1L, build = "IU-261.1", ideHome = "/home/idea")
        val root = render(s1 = listOf(compatible))
        assertEquals(1, root["mcpSteroidBackends"]!!.jsonArray.size,
            "compatible marker must be in mcpSteroidBackends")
        assertEquals(0, root["otherIdes"]!!.jsonArray.size,
            "compatible marker must NOT be in otherIdes")
        val entry = root["mcpSteroidBackends"]!!.jsonArray.single().jsonObject
        assertEquals(true, entry["compatible"]?.jsonPrimitive?.boolean,
            "compatible entry must have compatible=true")
    }

    // -------------- Finding B: no port duplicates ---------------------------

    @Test
    fun `port IDE matching compatible marker build is excluded from otherIdes`() {
        val build = "IU-253.21581.142"
        val marker = markerIde(pid = 1L, build = build, ideHome = "/home/idea")
        val port = portIde(port = 63342, buildNumber = build)
        val root = render(s1 = listOf(marker), s2 = setOf(port))
        assertEquals(0, root["otherIdes"]!!.jsonArray.size,
            "port IDE with same build as marker must be deduped from otherIdes")
    }

    @Test
    fun `port IDE matching incompatible marker build is also excluded from otherIdes`() {
        val build = "IU-253.21581.142"
        val incompatible = markerIde(pid = 1L, build = build, ideHome = null)
        val port = portIde(port = 63342, buildNumber = build)
        val root = render(s1 = listOf(incompatible), s2 = setOf(port))
        // otherIdes has the incompatible marker but NOT the port duplicate
        assertEquals(1, root["otherIdes"]!!.jsonArray.size,
            "incompatible marker in otherIdes but port IDE with same build must be deduped")
        val entry = root["otherIdes"]!!.jsonArray.single().jsonObject
        assertEquals(false, entry["compatible"]?.jsonPrimitive?.boolean,
            "the single entry must be the incompatible marker (compatible=false)")
        // Port IDEs have a "port" field; marker entries don't
        assertNull(entry["port"], "the single entry must be the marker, not the port IDE")
    }

}
