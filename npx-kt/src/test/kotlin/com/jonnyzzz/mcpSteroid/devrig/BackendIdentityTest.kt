/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.base36FixedWidth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** R3.3: one uniform `backend_name` scheme `<productCodeLower>-<hash8>` for every source. */
class BackendIdentityTest {
    @Test
    fun `backend_name uses the lowercased product code and an 8-char base36 hash of the source key`() {
        val marker = backendNameForRow(BackendRow.FromMarker(markerIde(pid = 4242L), emptyList()))
        val port = backendNameForRow(BackendRow.FromPort(portIde(port = 65432)))
        val managed = backendNameForRow(BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2")))

        // Product code prefix is lowercased; build is IC-… everywhere here.
        assertTrue(marker.startsWith("ic-"), marker)
        assertTrue(port.startsWith("ic-"), port)
        assertTrue(managed.startsWith("ic-"), managed)

        // hash8 is exactly 8 base36 (alphanumeric) chars.
        for (name in listOf(marker, port, managed)) {
            val hash = name.substringAfter('-')
            assertEquals(8, hash.length, name)
            assertTrue(hash.all { it.isLetterOrDigit() }, name)
        }

        // Deterministic and round-trippable: recomputing from the same source key + build gives the same id.
        assertEquals("ic-" + base36FixedWidth("pid:4242", "IC-253.1").take(8), marker)
        assertEquals("ic-" + base36FixedWidth("port:65432", "IC-253.1").take(8), port)
        assertEquals("ic-" + base36FixedWidth("managed:idea-community-2025.2.6.2", "IC-IC-252.1").take(8), managed)
    }

    @Test
    fun `the same pid yields the same id and different pids differ even with the same product`() {
        val a = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val aAgain = backendNameForMarker(pid = 1L, build = "IU-261.1")
        val b = backendNameForMarker(pid = 2L, build = "IU-261.1")
        assertEquals(a, aAgain)
        assertNotEquals(a, b)
        assertTrue(a.startsWith("iu-"))
    }

    @Test
    fun `missing product code falls back to the ide- prefix`() {
        assertTrue(backendNameForMarker(pid = 7L, build = null).startsWith("ide-"))
        assertTrue(backendNameForMarker(pid = 7L, build = "").startsWith("ide-"))
        // A build with no product-code prefix (port /api/about can return "253.x") also falls back.
        assertTrue(backendNameForPort(port = 63342, build = "253.21581.142").startsWith("ide-"))
    }

    private fun markerIde(pid: Long): DiscoveredIde {
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:6315/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = "IC-253.1"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0"),
            backendName = "mock-backend-name",
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
