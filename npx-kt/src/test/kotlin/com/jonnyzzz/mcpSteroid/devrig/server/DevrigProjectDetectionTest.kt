/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class DevrigProjectDetectionTest {
    @TempDir lateinit var tempDir: Path

    private fun route(name: String, path: String): ProjectRoute {
        val ide = DiscoveredIde(
            pid = 0L,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:65000/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = IdeInfo(name = name, version = "1", build = "IU-261.1"),
            plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test"),
            backendName = "backend-$name",
        )
        val canonical = DevrigProjectRoutingService.canonicalProjectHome(path)
        return ProjectRoute(
            route = ide,
            projectInfo = IdeProjectState(name = name, projectPath = path),
            exposedProjectName = "$name-hash",
            projectPath = canonical,
        )
    }

    @Test fun `no routes yields NoBackends`() {
        assertEquals(ProjectDetection.NoBackends, selectProjectByCwd(emptyList(), listOf(tempDir)))
    }

    @Test fun `exact cwd equals project root matches uniquely`() {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val d = selectProjectByCwd(listOf(route("repo", repo.toString())), listOf(repo))
        assertTrue(d is ProjectDetection.Unique && d.route.originalProjectName == "repo")
    }

    @Test fun `cwd inside a subdirectory of the project matches the project`() {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val sub = Files.createDirectories(repo.resolve("src").resolve("app"))
        val d = selectProjectByCwd(listOf(route("repo", repo.toString())), listOf(sub))
        assertTrue(d is ProjectDetection.Unique && d.route.originalProjectName == "repo")
    }

    @Test fun `nested projects pick the deepest match`() {
        val outer = Files.createDirectories(tempDir.resolve("repo"))
        val inner = Files.createDirectories(outer.resolve("sub"))
        val cwd = Files.createDirectories(inner.resolve("x"))
        val routes = listOf(route("outer", outer.toString()), route("inner", inner.toString()))
        val d = selectProjectByCwd(routes, listOf(cwd))
        assertTrue(d is ProjectDetection.Unique && d.route.originalProjectName == "inner")
    }

    @Test fun `unrelated matches across dirs are ambiguous`() {
        val a = Files.createDirectories(tempDir.resolve("a"))
        val b = Files.createDirectories(tempDir.resolve("b"))
        val routes = listOf(route("a", a.toString()), route("b", b.toString()))
        val d = selectProjectByCwd(routes, listOf(a, b))
        assertTrue(d is ProjectDetection.Ambiguous && d.matches.size == 2)
    }

    @Test fun `dirs matching no project yields NoMatch`() {
        val repo = Files.createDirectories(tempDir.resolve("repo"))
        val elsewhere = Files.createDirectories(tempDir.resolve("elsewhere"))
        val d = selectProjectByCwd(listOf(route("repo", repo.toString())), listOf(elsewhere))
        assertEquals(ProjectDetection.NoMatch, d)
    }
}
