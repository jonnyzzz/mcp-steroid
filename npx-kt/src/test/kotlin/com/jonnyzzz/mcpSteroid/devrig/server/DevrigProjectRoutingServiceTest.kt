/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class DevrigProjectRoutingServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `canonical path collapses symbolic link variants`() {
        val realProject = Files.createDirectories(tempDir.resolve("real").resolve("project"))
        val symlink = tempDir.resolve("link-project")
        Files.createSymbolicLink(symlink, realProject)

        assertEquals(
            realProject.toRealPath().toString(),
            DevrigProjectRoutingService.canonicalProjectHome(symlink.toString()),
        )
    }

    @Test
    fun `canonical path degrades to normalized absolute path when the directory vanished`() {
        // A deleted project (e.g., a test project removed while its IDE snapshot is still cached)
        // must not throw — toRealPath() would — or one vanished path breaks routing for everyone.
        val vanished = tempDir.resolve("gone").resolve("..").resolve("gone-project")
        assertEquals(
            vanished.toAbsolutePath().normalize().toString(),
            DevrigProjectRoutingService.canonicalProjectHome(vanished.toString()),
        )
    }

    @Test
    fun `routes survive a project whose directory no longer exists`() {
        val existing = Files.createDirectories(tempDir.resolve("alive"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(
                    IdeProjectState("alive", existing.toString()),
                    IdeProjectState("vanished", tempDir.resolve("deleted-project").toString()),
                ),
            )
        )

        val routes = service.routes()
        assertEquals(setOf("alive", "vanished"), routes.map { it.originalProjectName }.toSet())
        // The surviving project still resolves normally.
        val alive = routes.single { it.originalProjectName == "alive" }
        assertEquals(alive, service.requireProject(alive.exposedProjectName))
    }

    @Test
    fun `project route exposes unique name and maps back to original project name`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(IdeProjectState("mcp-steroid", projectHome.toString())),
            )
        )

        val route = service.routes().single()

        assertEquals("mcp-steroid", route.originalProjectName)
        assertEquals(testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl, route.route.rpcBaseUrl)
        assertEquals(mapOf("Authorization" to "Bearer secret-42"), route.route.bridgeHeaders)
        assertEquals(route, service.requireProject(route.exposedProjectName))
    }

    @Test
    fun `duplicate original project names in different ides expose distinct names`() {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(IdeProjectState("mcp-steroid", projectA.toString())),
            ),
            state(
                pid = 43,
                projects = listOf(IdeProjectState("mcp-steroid", projectB.toString())),
            ),
        )

        val routes = service.routes()

        assertEquals(2, routes.size)
        assertEquals(2, routes.map { it.exposedProjectName }.distinct().size)
        assertEquals(setOf("mcp-steroid"), routes.map { it.originalProjectName }.toSet())
        assertEquals(setOf(42L, 43L), routes.map { it.route.pid }.toSet())
        for (route in routes) {
            assertEquals(route, service.requireProject(route.exposedProjectName))
        }
    }

    @Test
    fun `stale exposed project name returns actionable error`() {
        val service = routingService()

        val error = assertFailsWith<ProjectRouteNotFoundException> {
            service.requireProject("missing-project-abcdefgh")
        }

        assertEquals(
            "project_name 'missing-project-abcdefgh' is no longer present; call steroid_list_projects to refresh",
            error.message,
        )
    }

    @Test
    fun `newest ide returns null when no ides are discovered`() {
        assertEquals(null, routingService().newestIde())
    }

    @Test
    fun `newest ide returns the only discovered ide`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(pid = 42, projects = listOf(IdeProjectState("mcp-steroid", projectHome.toString()))),
        )

        assertNotNull(service.newestIde())
        assertEquals(42, service.newestIde()?.pid)
    }

    @Test
    fun `newest ide prefers the highest build regardless of start order or pid`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            // Higher pid and later start time, but the older build must not win.
            state(
                pid = 99,
                projects = listOf(IdeProjectState("a", projectA.toString())),
                build = "IU-253.24374.151",
            ),
            state(
                pid = 1,
                projects = listOf(IdeProjectState("b", projectB.toString())),
                build = "IU-261.1",
            ),
        )

        assertEquals(1, service.newestIde()?.pid)
    }

    @Test
    fun `newest ide breaks build ties by the most recently started ide`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            state(
                pid = 1,
                projects = listOf(IdeProjectState("a", projectA.toString())),
                build = "IU-261.24374.151",
            ),
            state(
                pid = 2,
                projects = listOf(IdeProjectState("b", projectB.toString())),
                build = "IU-261.24374.151",
            ),
        )

        assertEquals(2, service.newestIde()?.pid)
    }

    @Test
    fun `newest ide compares builds numerically across product codes`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        // "IU" sorts after "GO" lexically; numeric build comparison must ignore the product code.
        val service = routingService(
            state(
                pid = 1,
                projects = listOf(IdeProjectState("a", projectA.toString())),
                build = "IU-253.1",
            ),
            state(
                pid = 2,
                projects = listOf(IdeProjectState("b", projectB.toString())),
                build = "GO-261.1",
            ),
        )

        assertEquals(2, service.newestIde()?.pid)
    }

    @Test
    fun `newest ide considers an ide that has no project open`() {
        val service = routingService(
            state(pid = 7, projects = emptyList(), build = "IU-261.1"),
        )

        assertEquals(7, service.newestIde()?.pid)
    }

    @Test
    fun `prompt context is parsed from routed IDE build number`() = runTest {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            state(
                pid = 42,
                projects = listOf(IdeProjectState("mcp-steroid", projectHome.toString())),
                build = "IU-261.24374.151",
            ),
        )
        val route = routing.routes().single()

        val context = DevrigPromptsContextHandler(routing).buildPromptsContext(route.exposedProjectName)

        assertEquals("IU", context.productCode)
        assertEquals(261, context.baselineVersion)
    }

    @Test
    fun `prompt context for a stale project name surfaces the route-not-found error`() = runTest {
        assertFailsWith<ProjectRouteNotFoundException> {
            DevrigPromptsContextHandler(routingService()).buildPromptsContext("missing-project-abcdefgh")
        }
    }

    @Test
    fun `prompt context parser supports common product build prefixes`() {
        val riderCppProductCode = charArrayOf('R', 'D', 'C', 'P', 'P', 'P').concatToString()
        val builds = mapOf(
            "IU-261.24374.151" to "IU",
            "IC-253.1" to "IC",
            "CL-253.2" to "CL",
            "RD-253.3" to "RD",
            "GO-253.4" to "GO",
            "PY-253.5" to "PY",
            "WS-253.6" to "WS",
            "DB-253.7" to "DB",
            "RM-253.8" to "RM",
            "QA-253.9" to "QA",
            "$riderCppProductCode-253.10" to riderCppProductCode,
        )

        for ((build, productCode) in builds) {
            val context = DevrigPromptsContextHandler.promptsContextFromBuild(build)
            assertEquals(productCode, context.productCode)
            assertEquals(build.substringAfter('-').substringBefore('.').toInt(), context.baselineVersion)
        }
    }

    @Test
    fun `prompt context parser falls back to generic for malformed or unknown builds`() {
        val builds = listOf(
            "IU",
            "-261.1",
            "IU-",
            "IU-next",
        )

        for (build in builds) {
            val context = DevrigPromptsContextHandler.promptsContextFromBuild(build)
            assertEquals("Generic", context.productCode, build)
            assertEquals(253, context.baselineVersion, build)
        }
    }

    /** The newest discovered IDE — relocated from the removed `newestIdeOrNull()` to the kept companion. */
    private fun DevrigProjectRoutingService.newestIde(): DiscoveredIde? =
        DevrigProjectRoutingService.newestOf(discoveredBackends())

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.toList() }

    private fun state(
        pid: Long,
        projects: List<IdeProjectState>,
        build: String = "IU-261.1",
    ): IdeMonitorState =
        IdeMonitorState(
            ide = discoveredIde(pid, build),
            projects = projects,
        )

    private fun discoveredIde(pid: Long, build: String): DiscoveredIde =
        DiscoveredIde(
            backendName = backendNameForMarker(pid, build),
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer secret-$pid"),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
}
