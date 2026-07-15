/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CwdProjectResolverTest {

    private fun route(path: String, name: String): ProjectRoute = ProjectRoute(
        route = DiscoveredIde(
            backendName = "backend-$name",
            pid = 1L,
            rpcBaseUrl = "http://127.0.0.1:4343/mcp",
            bridgeHeaders = emptyMap(),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        ),
        projectInfo = IdeProjectState(name, path),
        exposedProjectName = name,
        projectPath = path,
    )

    @Test
    fun `single project containing cwd matches`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.One(r), resolveProjectFromCwd(Path.of("/home/u/proj/src"), listOf(r)))
    }

    @Test
    fun `cwd equal to project root matches`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.One(r), resolveProjectFromCwd(Path.of("/home/u/proj"), listOf(r)))
    }

    @Test
    fun `nested projects pick the most specific`() {
        val outer = route("/home/u/proj", "proj-abc")
        val inner = route("/home/u/proj/module", "module-def")
        assertEquals(
            CwdProjectMatch.One(inner),
            resolveProjectFromCwd(Path.of("/home/u/proj/module/src"), listOf(outer, inner)),
        )
    }

    @Test
    fun `no containing project yields None`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.None, resolveProjectFromCwd(Path.of("/tmp/elsewhere"), listOf(r)))
    }

    @Test
    fun `sibling prefix does not falsely match`() {
        val r = route("/home/u/proj", "proj-abc")
        assertEquals(CwdProjectMatch.None, resolveProjectFromCwd(Path.of("/home/u/projbeta"), listOf(r)))
    }

    @Test
    fun `two routes at the identical path tie and yield Ambiguous`() {
        val a = route("/home/u/proj", "proj-abc")
        val b = route("/home/u/proj", "proj-xyz")
        val result = resolveProjectFromCwd(Path.of("/home/u/proj/src"), listOf(a, b))
        assertEquals(CwdProjectMatch.Ambiguous(listOf(a, b)), result)
    }
}
