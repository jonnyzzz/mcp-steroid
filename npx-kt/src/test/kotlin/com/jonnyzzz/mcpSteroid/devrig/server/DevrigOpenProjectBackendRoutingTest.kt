/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DevrigOpenProjectBackendRoutingTest {
    private val build = "IU-261.1"

    @Test
    fun `discoveredBackends lists every discovered ide deduped by backend_name`() {
        val routing = routingWith(pids = listOf(42L, 43L))
        val backends = routing.discoveredBackends()
        assertEquals(
            setOf(backendNameForMarker(42L, build), backendNameForMarker(43L, build)),
            backends.map { it.backendName }.toSet(),
        )
        assertEquals(setOf(42L, 43L), backends.map { it.pid }.toSet())
    }

    private fun routingWith(pids: List<Long>): DevrigProjectRoutingService {
        val states = pids.map { pid ->
            IdeMonitorState(
                ide = discoveredIde(pid),
                projects = emptyList(),
            )
        }
        return DevrigProjectRoutingService { states }
    }

    private fun discoveredIde(pid: Long): DiscoveredIde =
        DiscoveredIde(
            backendName = backendNameForMarker(pid, build),
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer secret-$pid"),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
}
