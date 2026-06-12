/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevrigOpenProjectBackendRoutingTest {
    private val build = "IU-261.1"

    @Test
    fun `resolveBackend matches the uniform-hash backend_name`() {
        val routing = routingWith(pids = listOf(42L, 43L))
        assertEquals(42L, routing.resolveBackend(backendNameForMarker(42L, build))?.pid)
        assertEquals(43L, routing.resolveBackend(backendNameForMarker(43L, build))?.pid)
    }

    @Test
    fun `resolveBackend trims surrounding whitespace`() {
        val routing = routingWith(pids = listOf(42L))
        val name = backendNameForMarker(42L, build)
        assertEquals(42L, routing.resolveBackend("  $name  ")?.pid)
    }

    @Test
    fun `resolveBackend returns null for unknown name`() {
        val routing = routingWith(pids = listOf(42L))
        assertNull(routing.resolveBackend(backendNameForMarker(999L, build)))
        assertNull(routing.resolveBackend("garbage"))
        assertNull(routing.resolveBackend(""))
    }

    @Test
    fun `backendNameForIde computes the uniform-hash form over the pid`() {
        val routing = routingWith(pids = listOf(7L))
        val ide = routing.newestIdeOrNull()!!
        assertEquals(backendNameForMarker(7L, build), routing.backendNameForIde(ide))
        assertTrue(routing.backendNameForIde(ide).startsWith("IU-"))
    }

    @Test
    fun `discoveredBackends pairs every backend_name with its ide`() {
        val routing = routingWith(pids = listOf(42L, 43L))
        val backends = routing.discoveredBackends()
        assertEquals(
            setOf(backendNameForMarker(42L, build), backendNameForMarker(43L, build)),
            backends.map { it.first }.toSet(),
        )
        assertEquals(setOf(42L, 43L), backends.map { it.second.pid }.toSet())
        for ((name, ide) in backends) {
            assertEquals(name, routing.backendNameForIde(ide))
        }
    }

    private fun routingWith(pids: List<Long>): DevrigProjectRoutingService {
        val states = pids.map { pid ->
            IdeMonitorState(
                ide = discoveredIde(pid),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = emptyList(),
            )
        }
        return DevrigProjectRoutingService { states.associateBy { it.ide.pid } }
    }

    private fun discoveredIde(pid: Long): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer secret-$pid"),
            markerPath = "/tmp/$pid.mcp-steroid",
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:4343/mcp",
                    headers = mapOf("Authorization" to "Bearer secret-$pid"),
                ),
                devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:4343/mcp", mapOf("Authorization" to "Bearer secret-$pid")),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )
}
