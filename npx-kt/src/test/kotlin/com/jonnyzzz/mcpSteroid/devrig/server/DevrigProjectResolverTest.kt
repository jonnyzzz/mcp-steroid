/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.mcp.Root
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class DevrigProjectResolverTest {
    @TempDir lateinit var tempDir: Path

    private fun ide(pid: Long) = DiscoveredIde(
        pid = pid,
        rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:6500$pid/mcp").rpcBaseUrl,
        bridgeHeaders = emptyMap(),
        ide = IdeInfo(name = "IDEA-$pid", version = "1", build = "IU-261.1"),
        plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test"),
        backendName = "backend-$pid",
    )

    private fun routing(vararg states: IdeMonitorState) =
        DevrigProjectRoutingService(stateProvider = { states.toList() })

    private fun resolver(routing: DevrigProjectRoutingService, dirs: List<Path>): DevrigProjectResolver =
        DevrigProjectResolver(
            routing = routing,
            workspaceRoots = DevrigWorkspaceRoots(
                rootsProvider = { dirs.map { Root(uri = it.toUri().toString()) } },
                processCwd = { tempDir.toString() },
            ),
        )

    @Test fun `explicit project_name wins over cwd`() = runTest {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val routing = routing(IdeMonitorState(ide(1), listOf(IdeProjectState("repo", repo.toString()))))
        val exposed = routing.routes().single().exposedProjectName
        val route = resolver(routing, dirs = listOf(tempDir)).resolve(exposed)
        assertEquals("repo", route.originalProjectName)
    }

    @Test fun `blank name auto-detects from cwd`() = runTest {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val routing = routing(IdeMonitorState(ide(1), listOf(IdeProjectState("repo", repo.toString()))))
        val route = resolver(routing, dirs = listOf(repo)).resolve("")
        assertEquals("repo", route.originalProjectName)
    }

    @Test fun `no backends yields friendly message`() = runTest {
        val ex = assertFailsWith<ToolCallErrorException> {
            resolver(routing(), dirs = listOf(tempDir)).resolve(null)
        }
        assertTrue(ex.message.contains("No IntelliJ IDE with the MCP Steroid plugin is running"))
    }

    @Test fun `no match yields friendly message`() = runTest {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"))
        val routing = routing(IdeMonitorState(ide(1), listOf(IdeProjectState("repo", repo.toString()))))
        val ex = assertFailsWith<ToolCallErrorException> {
            resolver(routing, dirs = listOf(elsewhere)).resolve(null)
        }
        assertTrue(ex.message.contains("No open project matches your working directory"))
    }

    @Test fun `ambiguous match yields friendly message`() = runTest {
        val a = Files.createDirectories(tempDir.resolve("a"))
        val b = Files.createDirectories(tempDir.resolve("b"))
        val routing = routing(
            IdeMonitorState(ide(1), listOf(IdeProjectState("a", a.toString()))),
            IdeMonitorState(ide(2), listOf(IdeProjectState("b", b.toString()))),
        )
        val ex = assertFailsWith<ToolCallErrorException> {
            resolver(routing, dirs = listOf(a, b)).resolve(null)
        }
        assertTrue(ex.message.contains("Multiple open projects match"))
    }
}
