/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Pins the contract that `backend --json` and `project --json` are byte-for-byte identical. */
class BackendAndProjectJsonAreIdenticalTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun captureBackendJson(rows: List<BackendRow>): String = ByteArrayOutputStream().let { buf ->
        renderBackendJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        buf.toString(Charsets.UTF_8)
    }

    private fun captureProjectJson(listing: ProjectListing): String = ByteArrayOutputStream().let { buf ->
        renderProjectJson(listing, PrintStream(buf, true, Charsets.UTF_8))
        buf.toString(Charsets.UTF_8)
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = mcpUrl,
                headers = emptyMap(),
            ),
            devrigEndpoint = testDevrigEndpoint(mcpUrl),
            ide = ideInfo,
            plugin = pluginInfo,
            createdAt = "1970-01-01T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = "mock-backend-name"
        )
    }

    private fun portIde(
        port: Int,
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

    @Test
    fun `backend and project json renderers emit byte-for-byte identical documents`() {
        val firstMarkerIde = markerIde(name = "IntelliJ IDEA", pid = 1L, mcpUrl = "http://localhost:6315/mcp")
        val rows = listOf(
            BackendRow.FromMarker(
                ide = firstMarkerIde,
                projects = listOf(
                    ProjectRoute(
                        route = firstMarkerIde,
                        projectInfo = IdeProjectState("alpha", "/work/alpha"),
                        exposedProjectName = "alpha",
                        projectPath = "/work/alpha",
                    ),
                    ProjectRoute(
                        route = firstMarkerIde,
                        projectInfo = IdeProjectState("bravo", "/work/bravo"),
                        exposedProjectName = "bravo",
                        projectPath = "/work/bravo",
                    ),
                ),
            ),
            BackendRow.FromMarker(
                ide = markerIde(name = "PyCharm", version = "2025.3.1", pid = 2L, build = "PC-253.99"),
                projects = null,
                errorMessage = "timed out after 8s",
            ),
            BackendRow.FromPort(portIde(port = 63342, productFullName = "IntelliJ IDEA Ultimate")),
            BackendRow.FromPort(portIde(port = 63343, productFullName = "GoLand", productName = "GoLand", buildNumber = "GO-253.2")),
        )
        val listing = projectListingFromRows(rows)

        val backendJson = captureBackendJson(rows)
        val projectJson = captureProjectJson(listing)
        assertEquals(backendJson, projectJson, "backend --json and project --json must be byte-for-byte identical")

        val root = parser.parseToJsonElement(backendJson).jsonObject
        assertEquals(setOf("tool", "backends", "projects"), root.keys)
        val backends = root["backends"]!!.jsonArray
        assertEquals(4, backends.size)
        assertTrue(backends.all { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "intellij" },
            "every current backend must carry type=intellij: $backends")

        fun JsonObject.hasMcpSteroidPlugin(): Boolean =
            this["plugins"]!!.jsonArray.any { it.jsonObject["kind"]?.jsonPrimitive?.contentOrNull == "mcp-steroid" }

        val markerReachable = backends[0].jsonObject
        assertEquals("marker", markerReachable["source"]!!.jsonPrimitive.content)
        assertTrue(markerReachable.hasMcpSteroidPlugin(), "reachable marker has a kind=mcp-steroid plugin: $markerReachable")
        assertEquals(true, markerReachable["reachable"]!!.jsonPrimitive.boolean)

        val markerUnreachable = backends[1].jsonObject
        assertEquals("marker", markerUnreachable["source"]!!.jsonPrimitive.content)
        assertTrue(markerUnreachable.hasMcpSteroidPlugin(), "unreachable marker still reports its plugin: $markerUnreachable")
        assertEquals(false, markerUnreachable["reachable"]!!.jsonPrimitive.boolean)
        assertEquals("timed out after 8s", markerUnreachable["error"]!!.jsonPrimitive.content)

        val portBackends = backends.drop(2).map { it.jsonObject }
        assertTrue(portBackends.all { it["source"]!!.jsonPrimitive.content == "port" }, "last two rows are port rows: $backends")
        assertTrue(portBackends.none { it.hasMcpSteroidPlugin() }, "port rows have no plugin: $backends")
        assertTrue(portBackends.all { it["reachable"]!!.jsonPrimitive.boolean }, "port rows are reachable because the probe succeeded: $backends")

        // Both projects belong to the same (first) marker backend; project_name keeps the raw name as prefix.
        val expectedFirstBackend = backends[0].jsonObject["backend_name"]!!.jsonPrimitive.content
        val projects = root["projects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(expectedFirstBackend, expectedFirstBackend), projects.map { it["backend_name"]!!.jsonPrimitive.content })
        assertEquals(listOf("alpha", "bravo"), projects.map { it["name"]!!.jsonPrimitive.content })
    }
}
