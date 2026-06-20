/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.canonicalProjectHome
import com.jonnyzzz.mcpSteroid.server.projectHash
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

class DevrigProjectRoutingServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `hash is stable, eight alphanumeric chars, and never ends with a dash`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project")).toRealPath()

        val first = projectHash(projectHome, 1234)
        val second = projectHash(projectHome, 1234)

        assertEquals(first, second)
        assertEquals(8, first.length)
        assertEquals(first, first.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' })
        assertNotEquals('-', first.last())
    }

    @Test
    fun `hash changes for different ide pids on the same project home`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project")).toRealPath()

        assertNotEquals(
            projectHash(projectHome, 1234),
            projectHash(projectHome, 5678),
        )
    }

    @Test
    fun `hash changes for different canonical project homes`() {
        val projectA = Files.createDirectories(tempDir.resolve("project-a")).toRealPath()
        val projectB = Files.createDirectories(tempDir.resolve("project-b")).toRealPath()

        assertNotEquals(
            projectHash(projectA, 1234),
            projectHash(projectB, 1234),
        )
    }

    @Test
    fun `canonical path collapses symbolic link variants`() {
        val realProject = Files.createDirectories(tempDir.resolve("real").resolve("project"))
        val symlink = tempDir.resolve("link-project")
        Files.createSymbolicLink(symlink, realProject)

        assertEquals(
            realProject.toRealPath(),
            canonicalProjectHome(symlink.toString()),
        )
    }

    @Test
    fun `canonical path degrades to normalized absolute path when the directory vanished`() {
        // A deleted project (e.g. a test project removed while its IDE snapshot is still cached)
        // must not throw — toRealPath() would — or one vanished path breaks routing for everyone.
        val vanished = tempDir.resolve("gone").resolve("..").resolve("gone-project")
        assertEquals(
            vanished.toAbsolutePath().normalize(),
            canonicalProjectHome(vanished.toString()),
        )
    }

    @Test
    fun `routes survive a project whose directory no longer exists`() {
        val existing = Files.createDirectories(tempDir.resolve("alive"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(
                    ProjectInfo("alive", existing.toString()),
                    ProjectInfo("vanished", tempDir.resolve("deleted-project").toString()),
                ),
            )
        )

        val routes = service.routes().values
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
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
            )
        )

        val route = service.routes().values.single()

        assertEquals("mcp-steroid-${route.projectHash}", route.exposedProjectName)
        assertEquals("mcp-steroid", route.originalProjectName)
        assertEquals(testDevrigEndpoint("http://127.0.0.1:4343/mcp").rpcBaseUrl, route.bridgeBaseUrl)
        assertEquals(mapOf("Authorization" to "Bearer secret-42"), route.headers)
        assertEquals(route, service.requireProject(route.exposedProjectName))
    }

    @Test
    fun `duplicate original project names in different ides expose distinct names`() {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val service = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectA.toString())),
            ),
            state(
                pid = 43,
                projects = listOf(ProjectInfo("mcp-steroid", projectB.toString())),
            ),
        )

        val routes = service.routes().values.toList()

        assertEquals(2, routes.size)
        assertEquals(2, routes.map { it.exposedProjectName }.distinct().size)
        assertEquals(setOf("mcp-steroid"), routes.map { it.originalProjectName }.toSet())
        assertEquals(setOf(42L, 43L), routes.map { it.idePid }.toSet())
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
        val service = routingService()

        assertEquals(null, service.newestIdeOrNull())
    }

    @Test
    fun `newest ide returns the only discovered ide`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val service = routingService(
            state(pid = 42, projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString()))),
        )

        assertNotNull(service.newestIdeOrNull())
        assertEquals(42, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `newest ide prefers the highest build regardless of start order or pid`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            // Higher pid and later start time, but the older build must not win.
            state(
                pid = 99,
                projects = listOf(ProjectInfo("a", projectA.toString())),
                build = "IU-253.24374.151",
                createdAt = "2026-05-20T00:00:00Z",
            ),
            state(
                pid = 1,
                projects = listOf(ProjectInfo("b", projectB.toString())),
                build = "IU-261.1",
                createdAt = "2026-05-10T00:00:00Z",
            ),
        )

        assertEquals(1, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `newest ide breaks build ties by the most recently started ide`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            state(
                pid = 1,
                projects = listOf(ProjectInfo("a", projectA.toString())),
                build = "IU-261.24374.151",
                createdAt = "2026-05-10T00:00:00Z",
            ),
            state(
                pid = 2,
                projects = listOf(ProjectInfo("b", projectB.toString())),
                build = "IU-261.24374.151",
                createdAt = "2026-05-20T00:00:00Z",
            ),
        )

        assertEquals(2, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `newest ide compares builds numerically across product codes`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        // "IU" sorts after "GO" lexically; numeric build comparison must ignore the product code.
        val service = routingService(
            state(
                pid = 1,
                projects = listOf(ProjectInfo("a", projectA.toString())),
                build = "IU-253.1",
                createdAt = "2026-05-20T00:00:00Z",
            ),
            state(
                pid = 2,
                projects = listOf(ProjectInfo("b", projectB.toString())),
                build = "GO-261.1",
                createdAt = "2026-05-10T00:00:00Z",
            ),
        )

        assertEquals(2, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `newest ide considers an ide that has no project open`() {
        val service = routingService(
            state(pid = 7, projects = emptyList(), build = "IU-261.1"),
        )

        assertEquals(7, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `open_project target prefers a running managed backend over a newer user ide`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            managedPids = setOf(2L),
            state(pid = 1, projects = listOf(ProjectInfo("a", projectA.toString())), build = "IU-261.1"),
            state(pid = 2, projects = listOf(ProjectInfo("b", projectB.toString())), build = "IU-253.9"),
        )

        // pid 1 is the newer build, but pid 2 is the agent's managed backend — it must win.
        assertEquals(2, service.openProjectTargetIde()?.pid)
        // newestIdeOrNull is unaffected and still picks the newest build.
        assertEquals(1, service.newestIdeOrNull()?.pid)
    }

    @Test
    fun `open_project target falls back to newest when no managed backend runs`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            managedPids = emptySet(),
            state(pid = 1, projects = listOf(ProjectInfo("a", projectA.toString())), build = "IU-253.9"),
            state(pid = 2, projects = listOf(ProjectInfo("b", projectB.toString())), build = "IU-261.1"),
        )

        assertEquals(2, service.openProjectTargetIde()?.pid)
    }

    @Test
    fun `open_project target ignores a managed pid that is not yet discovered`() {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        // The managed backend was started (pid 99) but its marker has not appeared yet, so it is
        // not among discovered IDEs. Selection must fall back to the discovered newest, not error.
        val service = routingService(
            managedPids = setOf(99L),
            state(pid = 1, projects = listOf(ProjectInfo("a", projectHome.toString())), build = "IU-261.1"),
        )

        assertEquals(1, service.openProjectTargetIde()?.pid)
    }

    @Test
    fun `open_project target picks the newest among several managed backends`() {
        val projectA = Files.createDirectories(tempDir.resolve("a"))
        val projectB = Files.createDirectories(tempDir.resolve("b"))
        val service = routingService(
            managedPids = setOf(1L, 2L),
            state(pid = 1, projects = listOf(ProjectInfo("a", projectA.toString())), build = "IU-261.1"),
            state(pid = 2, projects = listOf(ProjectInfo("b", projectB.toString())), build = "IU-253.9"),
        )

        assertEquals(1, service.openProjectTargetIde()?.pid)
    }

    @Test
    fun `open_project target returns null when no ide is discovered`() {
        assertEquals(null, routingService().openProjectTargetIde())
    }

    @Test
    fun `prompt context is parsed from routed IDE build number`() = runTest {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            state(
                pid = 42,
                projects = listOf(ProjectInfo("mcp-steroid", projectHome.toString())),
                build = "IU-261.24374.151",
            ),
        )
        val route = routing.routes().values.single()

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

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun routingService(
        managedPids: Set<Long>,
        vararg states: IdeMonitorState,
    ): DevrigProjectRoutingService =
        DevrigProjectRoutingService({ states.associateBy { it.ide.pid } }, { managedPids })

    private fun state(
        pid: Long,
        projects: List<ProjectInfo>,
        build: String = "IU-261.1",
        createdAt: String = "2026-05-17T00:00:00Z",
    ): IdeMonitorState {
        val ide = discoveredIde(pid, build, createdAt)
        return IdeMonitorState(
            ide = ide,
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = projects,
        )
    }

    private fun discoveredIde(pid: Long, build: String, createdAt: String = "2026-05-17T00:00:00Z"): DiscoveredIde =
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
                createdAt = createdAt,
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )
}
