/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Pure-text tests for the `project` subcommand renderer. Uses [List<ProjectRoute>] as
 * the primary input, testing the new 3-source approach.
 */
class ProjectCommandRenderTest {

    private fun render(
        routes: List<ProjectRoute> = emptyList(),
        portIdes: Set<DiscoveredIdeByPort> = emptySet(),
    ): String {
        val buf = ByteArrayOutputStream()
        renderProjectOutput3(routes, portIdes, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://127.0.0.1:65000/mcp",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = "mock-backend-name",
        )
    }

    private fun portIde(
        port: Int = 63342,
        productFullName: String? = "IntelliJ IDEA Ultimate",
        productName: String? = "IDEA",
        buildNumber: String? = "IU-253.21581.142",
        baselineVersion: Int? = 253,
        edition: String? = "IU",
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    /**
     * A single routed project for a marker row.
     */
    private fun route(
        name: String,
        path: String,
        ide: DiscoveredIde = markerIde(name = name),
    ): ProjectRoute =
        ProjectRoute(
            route = ide,
            projectInfo = IdeProjectState(name = name, projectPath = path),
            exposedProjectName = "$name-rendertst",
            projectPath = path,
        )

    @Test
    fun `projects are sorted by project_name`() {
        // Same ordering as the MCP steroid_list_projects response (#155), whatever the input order.
        val text = render(routes = listOf(route("zulu", "/p/zulu"), route("alpha", "/p/alpha")))
        val i1 = text.indexOf("[1] alpha-rendertst")
        val i2 = text.indexOf("[2] zulu-rendertst")
        assertTrue(i1 in 0 until i2, "projects must render sorted by project_name; got:\n$text")
    }

    // ------------------------------ shape ---------------------------------

    @Test
    fun `empty output starts with the no-backends message`() {
        val text = render()
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    @Test
    fun `output ends with a trailing blank line so shells separate the prompt cleanly`() {
        val text = render(routes = listOf(route("p", "/p")))
        assertTrue(text.endsWith("\n\n"),
            "output must end with a blank line; got tail: '${text.takeLast(8).replace("\n", "\\n")}'")
    }

    @Test
    fun `empty listing prints the no-backends message + trailing blank`() {
        val text = render()
        assertTrue(text.contains("No backends detected."), "missing message; got:\n$text")
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    // ----------------------------- projects --------------------------------

    @Test
    fun `single IDE with one project shows project mapping and owning IDE`() {
        val ide = markerIde(name = "IntelliJ IDEA", version = "2025.3.3", pid = 1234L)
        val text = render(routes = listOf(route(name = "my-app", path = "/Users/x/Work/my-app", ide = ide)))
        assertTrue(text.contains("Listing 1 open project(s) across 1 backend(s):"),
            "expected list header for one project; got:\n$text")
        assertTrue(text.contains("[1] my-app") && text.contains("→") && text.contains("/Users/x/Work/my-app"),
            "expected project to render as name → path; got:\n$text")
        assertTrue(text.contains("        IntelliJ IDEA 2025.3.3 (mock-backend-name; build IU-253.21581.142, pid 1234)"),
            "expected owning IDE line to carry backend_name + identity formatting (#155); got:\n$text")
        assertTrue(text.contains("        MCP Steroid: 0.0.0-test"),
            "expected plugin status line; got:\n$text")
    }

    @Test
    fun `single IDE with multiple projects renders aligned project arrows`() {
        val ide = markerIde(name = "PyCharm", version = "2025.3.1", pid = 4242L)
        val text = render(
            routes = listOf(
                route(name = "alpha", path = "/p/alpha", ide = ide),
                route(name = "bravo-long", path = "/p/bravo", ide = ide),
            )
        )
        assertTrue(text.contains("alpha") && text.contains("/p/alpha"), text)
        assertTrue(text.contains("bravo-long") && text.contains("/p/bravo"), text)
        val arrowColumns = text.lines().filter { it.contains("→") }.map { it.indexOf('→') }
        assertEquals(arrowColumns.toSet().size, 1,
            "all project-list arrows must be in the same column; got columns: $arrowColumns in:\n$text")
    }

    @Test
    fun `multiple IDEs with mixed open projects render one entry per project in input order`() {
        val ide1 = markerIde(name = "IntelliJ IDEA", version = "2025.3.3", pid = 1L)
        val ide2 = markerIde(name = "PyCharm", version = "2025.3.1", pid = 2L)
        val text = render(
            routes = listOf(
                route("a", "/a", ide1),
                route("b", "/b", ide1),
                route("c", "/c", ide2),
            )
        )
        assertTrue(text.contains("Listing 3 open project(s) across 2 backend(s):"),
            "expected project+IDE count header; got:\n$text")
        val aIndex = text.indexOf("[1] a")
        val bIndex = text.indexOf("[2] b")
        val cIndex = text.indexOf("[3] c")
        assertTrue(aIndex >= 0 && bIndex > aIndex && cIndex > bIndex,
            "project entries must keep input order; got:\n$text")
    }

    @Test
    fun `no open projects when routes is empty prints message`() {
        val text = render(routes = emptyList())
        assertTrue(text.contains("No backends detected."),
            "expected no-backends message when no routes; got:\n$text")
    }

    // ----------------------------- skipped (port IDEs) ---------------------

    @Test
    fun `port-only IDE appears in skipped footer and not in projects list`() {
        val ide = markerIde(pid = 1L)
        val text = render(
            routes = listOf(route("a", "/a", ide)),
            portIdes = setOf(portIde(port = 63342)),
        )
        assertTrue(text.contains("Skipped 1 backend with MCP Steroid not installed:"),
            "expected no-plugin footer; got:\n$text")
        assertTrue(text.contains("IntelliJ IDEA Ultimate (build IU-253.21581.142, port 63342): MCP Steroid: not installed"),
            "expected port IDE identity; got:\n$text")
        assertTrue(text.contains("Install MCP Steroid into the IDE(s) above (each shows a confirmation dialog): devrig install plugin"),
            "skipped footer must promote the one-shot REST installer; got:\n$text")
    }

    @Test
    fun `no skipped footer when portIdes is empty`() {
        val ide = markerIde(pid = 1L)
        val text = render(routes = listOf(route("a", "/a", ide)))
        assertTrue(!text.contains("Skipped"), "no skipped footer expected; got:\n$text")
    }
}
