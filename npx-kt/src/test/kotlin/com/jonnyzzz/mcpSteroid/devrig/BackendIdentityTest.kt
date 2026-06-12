/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.backendNameFor
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.hash8
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** ONE uniform `backend_name` scheme `<PRODUCTCODE>-<hash8>` for every source — marker, port, managed. */
class BackendIdentityTest {
    @Test
    fun `backend_name uses the verbatim capital product code and an 8-char base62 hash of the source key`() {
        val marker = backendNameForRow(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList()))
        val port = backendNameForRow(BackendRow.FromPort(portIde(port = 65432)))
        val managed = backendNameForRow(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")))

        // (a) The product code is the verbatim build prefix, capitals included — never lowercased.
        // Build is IC-… everywhere here; the managed row's catalog productCode carries the same "IC".
        assertTrue(marker.startsWith("IC-"), marker)
        assertTrue(port.startsWith("IC-"), port)
        assertTrue(managed.startsWith("IC-"), managed)
        for (name in listOf(marker, port, managed)) {
            assertNotEquals("ic", name.substringBefore('-'), "product segment must not be lowercased: $name")
        }

        // hash8 is exactly 8 base62 (alphanumeric) chars.
        for (name in listOf(marker, port, managed)) {
            val hash = name.substringAfter('-')
            assertEquals(8, hash.length, name)
            assertTrue(hash.all { it.isLetterOrDigit() }, name)
        }

        // Deterministic and round-trippable: recomputing from the same source key gives the same id.
        assertEquals("IC-" + hash8("pid:4242"), marker)
        assertEquals("IC-" + hash8("port:65432"), port)
        assertEquals("IC-" + hash8("managed:idea-community-2025.2.6.2"), managed)
    }

    @Test
    fun `marker, port, and managed rows all flow through the single backendNameFor formula`() {
        // (b) No divergent per-source path: each row kind recomputes to the ONE shared formula.
        assertEquals(
            backendNameFor(productCode = "IC", sourceKey = "pid:4242"),
            backendNameForRow(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList())),
        )
        assertEquals(
            backendNameFor(productCode = "IC", sourceKey = "port:65432"),
            backendNameForRow(BackendRow.FromPort(portIde(port = 65432))),
        )
        assertEquals(
            backendNameFor(productCode = "IC", sourceKey = "managed:idea-community-2025.2.6.2"),
            backendNameForRow(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2"))),
        )
    }

    @Test
    fun `hash inputs are stable across rescans for the same IDE`() {
        // (c) A rescan builds FRESH row objects for the same underlying IDE identity (same pid /
        // port / managedId) — the recomputed backend_name must not change.
        val markerScan1 = backendNameForRow(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList()))
        val markerScan2 = backendNameForRow(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList()))
        assertEquals(markerScan1, markerScan2)

        val portScan1 = backendNameForRow(BackendRow.FromPort(portIde(port = 65432)))
        val portScan2 = backendNameForRow(BackendRow.FromPort(portIde(port = 65432)))
        assertEquals(portScan1, portScan2)

        val managedScan1 = backendNameForRow(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")))
        val managedScan2 = backendNameForRow(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")))
        assertEquals(managedScan1, managedScan2)
    }

    @Test
    fun `the same pid yields the same id and different pids differ even with the same product`() {
        val a = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val aAgain = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val b = backendNameForMarker(pid = 2L, build = "IU-261.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("IU-"), a)
    }

    @Test
    fun `missing product code falls back to the IDE- prefix`() {
        assertTrue(backendNameForMarker(pid = 7L, build = null).startsWith("IDE-"))
        assertTrue(backendNameForMarker(pid = 7L, build = "").startsWith("IDE-"))
        // A build with no product-code prefix (port /api/about can return "253.x") also falls back.
        assertTrue(backendNameForPort(port = 63342, build = "253.21581.142").startsWith("IDE-"))
    }

    private fun markerIde(pid: Long): DiscoveredIde {
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://127.0.0.1:6315/mcp",
                headers = emptyMap(),
            ),
            devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:6315/mcp"),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = "IC-253.1"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            createdAt = "2026-05-14T21:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:6315/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            markerPath = "/tmp/$pid.mcp-steroid",
            marker = marker,
        )
    }

    private fun portIde(port: Int) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = "IDEA",
        productFullName = "IntelliJ IDEA",
        edition = "Community",
        baselineVersion = 253,
        buildNumber = "IC-253.1",
    )

    private fun managedInfo(id: String) = ManagedBackendInfo(
        id = id,
        productKey = "idea-community",
        productCode = "IC",
        version = "2025.2.6.2",
        buildNumber = "IC-252.1",
        installPath = Path.of("/managed/$id"),
        cachePath = Path.of("/caches/$id"),
        runningPid = null,
        state = ManagedBackendState.INSTALLED,
    )
}
