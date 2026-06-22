/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendListTest {

    @Test
    fun `mergeRows includes marker port and managed rows`() {
        val rows = mergeRows(
            markerRows = listOf(BackendRow.FromMarker(markerIde(pid = 11, build = "IU-253.1"), projects = emptyList())),
            portIdes = setOf(portIde(port = 63342, buildNumber = "WS-253.2")),
            managedBackends = listOf(managed("idea-community-2025.3.3", state = ManagedBackendState.INSTALLED)),
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is BackendRow.FromMarker)
        assertTrue(rows[1] is BackendRow.FromPort)
        assertTrue(rows[2] is BackendRow.FromManaged)
    }

    @Test
    fun `running managed backend surfaced by marker is deduped and annotates marker`() {
        val rows = mergeRows(
            markerRows = listOf(BackendRow.FromMarker(markerIde(pid = 22, build = "IC-253.1"), projects = emptyList())),
            portIdes = emptySet(),
            managedBackends = listOf(
                managed("idea-community-2025.3.3", state = ManagedBackendState.RUNNING, runningPid = 22, buildNumber = "IC-253.1"),
            ),
        )

        assertEquals(1, rows.size)
        val row = rows.single() as BackendRow.FromMarker
        assertTrue(row.managed)
        assertTrue(row.locatorLabel.contains("managed"))
    }

    @Test
    fun `marker IDE sharing a build but not the pid of a managed backend is NOT flagged managed`() {
        val rows = mergeRows(
            // The user's own IDEA Ultimate: same build baseline as the managed Community, different pid.
            markerRows = listOf(BackendRow.FromMarker(markerIde(pid = 99, build = "IU-261.24374.151"), projects = emptyList())),
            portIdes = emptySet(),
            managedBackends = listOf(
                managed("idea-community-2026.1.2", state = ManagedBackendState.RUNNING, runningPid = 22, buildNumber = "IC-261.24374.151"),
            ),
        )

        val markerRow = rows.filterIsInstance<BackendRow.FromMarker>().single()
        assertEquals(false, markerRow.managed, "an unrelated IDE must not be flagged managed by build coincidence")
        // The managed backend still surfaces as its own row (not deduped onto the unrelated marker).
        assertTrue(rows.any { it is BackendRow.FromManaged && it.info.id == "idea-community-2026.1.2" })
    }

    @Test
    fun `running managed backend surfaced by port is deduped and annotates port`() {
        val rows = mergeRows(
            markerRows = emptyList(),
            portIdes = setOf(portIde(buildNumber = "IC-253.1")),
            managedBackends = listOf(
                managed("idea-community-2025.3.3", state = ManagedBackendState.RUNNING, runningPid = 33, buildNumber = "IC-253.1"),
            ),
        )

        assertEquals(1, rows.size)
        val row = rows.single() as BackendRow.FromPort
        assertTrue(row.managed)
        assertTrue(row.locatorLabel.contains("managed"))
    }

    @Test
    fun `managed json row carries install cache running pid and version`() {
        val buf = ByteArrayOutputStream()
        renderBackendJson(
            listOf(BackendRow.FromManaged(managed("idea-community-2025.3.3", state = ManagedBackendState.RUNNING, runningPid = 44))),
            PrintStream(buf, true, Charsets.UTF_8),
        )

        val root = Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        assertEquals("intellij", backend["type"]!!.jsonPrimitive.content)
        assertEquals("managed", backend["source"]!!.jsonPrimitive.content)
        assertEquals(true, backend["managed"]!!.jsonPrimitive.boolean)
        // R3.4: managed-only fields live under managedDetail, not flattened on the backend.
        val detail = backend["managedDetail"]!!.jsonObject
        assertEquals("idea-community-2025.3.3", detail["managedId"]!!.jsonPrimitive.content)
        assertEquals("2025.3.3", detail["version"]!!.jsonPrimitive.content)
        assertEquals("/managed/idea-community-2025.3.3", detail["installPath"]!!.jsonPrimitive.contentOrNull)
        assertEquals("/caches/idea-community-2025.3.3", detail["cachePath"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `managed rows render running and stopped annotations`() {
        val running = BackendRow.FromManaged(
            managed("idea-community-2025.3.3", state = ManagedBackendState.RUNNING, runningPid = 44),
        )
        val stopped = BackendRow.FromManaged(
            managed("idea-community-2025.3.2", state = ManagedBackendState.INSTALLED),
        )

        assertEquals("managed, pid 44", running.locatorLabel)
        assertEquals("managed, installed", stopped.locatorLabel)
    }

    @Test
    fun `json marks every backend with managed boolean`() {
        val buf = ByteArrayOutputStream()
        renderBackendJson(
            listOf(
                BackendRow.FromMarker(markerIde(pid = 11, build = "IC-253.1"), projects = emptyList(), managed = false),
                BackendRow.FromPort(portIde(buildNumber = "IC-253.2"), managed = true),
            ),
            PrintStream(buf, true, Charsets.UTF_8),
        )

        val backends = Json.parseToJsonElement(buf.toString(Charsets.UTF_8)).jsonObject["backends"]!!.jsonArray
        assertEquals(false, backends[0].jsonObject["managed"]!!.jsonPrimitive.boolean)
        assertEquals(true, backends[1].jsonObject["managed"]!!.jsonPrimitive.boolean)
    }

    private fun managed(
        id: String,
        state: ManagedBackendState,
        runningPid: Long? = null,
        buildNumber: String? = "IC-253.1",
    ) = ManagedBackendInfo(
        id = id,
        productKey = id.substringBeforeLast('-'),
        productCode = "IC",
        version = id.substringAfterLast('-'),
        buildNumber = buildNumber,
        installPath = Path.of("/managed/$id"),
        cachePath = Path.of("/caches/$id"),
        runningPid = runningPid,
        state = state,
    )

    private fun markerIde(pid: Long, build: String): DiscoveredIde {
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:6315/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = build),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            backendName = "mock-backend-name",
        )
    }

    private fun portIde(
        port: Int = 63342,
        buildNumber: String,
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = "IDEA",
        productFullName = "IntelliJ IDEA",
        edition = "Community",
        baselineVersion = 253,
        buildNumber = buildNumber,
    )
}
