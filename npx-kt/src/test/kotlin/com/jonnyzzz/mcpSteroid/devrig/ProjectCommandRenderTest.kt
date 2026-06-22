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
 * Pure-text tests for the `project` subcommand renderer. The network-fetching
 * path is shared with `backend`; this class pins only the flattened project
 * presentation contract.
 */
class ProjectCommandRenderTest {

    private fun render(listing: ProjectListing): String {
        val buf = ByteArrayOutputStream()
        renderProjectOutput(listing, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
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
     * A single routed project for a marker row. The renderer only reads
     * [ProjectRoute.originalProjectName] (= [IdeProjectState.ideProjectName])
     * and [ProjectRoute.projectPath]; the embedded [DiscoveredIde] is never
     * inspected here, so a throwaway one keeps the fixture minimal.
     */
    private fun route(name: String, path: String): ProjectRoute =
        ProjectRoute(
            route = markerIde(name = name),
            projectInfo = IdeProjectState(name = name, projectPath = path),
            exposedProjectName = "$name-rendertst",
            projectPath = path,
        )

    // ------------------------------ shape ---------------------------------

    @Test
    fun `empty output starts with the no-backends message`() {
        val text = render(ProjectListing(emptyList(), emptyList()))
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    @Test
    fun `output ends with a trailing blank line so shells separate the prompt cleanly`() {
        val listing = ProjectListing(
            markerRows = listOf(BackendRow.FromMarker(markerIde(), listOf(route("p", "/p")))),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.endsWith("\n\n"),
            "output must end with a blank line; got tail: '${text.takeLast(8).replace("\n", "\\n")}'")
    }

    // -------------------------- empty / no-backend branch ----------------------

    @Test
    fun `empty listing prints the no-backends message + trailing blank`() {
        val text = render(ProjectListing(emptyList(), emptyList()))
        assertTrue(text.contains("No backends detected."), "missing message; got:\n$text")
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    // ----------------------------- projects --------------------------------

    @Test
    fun `single IDE with one project shows project mapping and owning IDE`() {
        val listing = ProjectListing(
            markerRows = listOf(
                BackendRow.FromMarker(
                    ide = markerIde(name = "IntelliJ IDEA", version = "2025.3.3", pid = 1234L),
                    projects = listOf(route(name = "my-app", path = "/Users/x/Work/my-app")),
                )
            ),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("Listing 1 open project(s) across 1 backend(s):"),
            "expected list header for one project; got:\n$text")
        assertTrue(text.contains("[1] my-app") && text.contains("→") && text.contains("/Users/x/Work/my-app"),
            "expected project to render as name → path; got:\n$text")
        assertTrue(text.contains("        IntelliJ IDEA 2025.3.3 (build IU-253.21581.142, pid 1234)"),
            "expected owning IDE line to reuse backend identity formatting; got:\n$text")
        assertTrue(text.contains("        MCP Steroid: 0.0.0-test"),
            "expected plugin status line; got:\n$text")
    }

    @Test
    fun `single IDE with multiple projects renders aligned project arrows`() {
        val listing = ProjectListing(
            markerRows = listOf(
                BackendRow.FromMarker(
                    ide = markerIde(name = "PyCharm", version = "2025.3.1", pid = 4242L),
                    projects = listOf(
                        route(name = "alpha", path = "/p/alpha"),
                        route(name = "bravo-long", path = "/p/bravo"),
                    ),
                )
            ),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("alpha") && text.contains("/p/alpha"), text)
        assertTrue(text.contains("bravo-long") && text.contains("/p/bravo"), text)
        val arrowColumns = text.lines().filter { it.contains("→") }.map { it.indexOf('→') }
        assertEquals(arrowColumns.toSet().size, 1,
            "all project-list arrows must be in the same column; got columns: $arrowColumns in:\n$text")
    }

    @Test
    fun `multiple IDEs with mixed open projects render one entry per project in input order`() {
        val listing = ProjectListing(
            markerRows = listOf(
                BackendRow.FromMarker(
                    markerIde(name = "IntelliJ IDEA", version = "2025.3.3", pid = 1L),
                    listOf(route("a", "/a"), route("b", "/b")),
                ),
                BackendRow.FromMarker(
                    markerIde(name = "PyCharm", version = "2025.3.1", pid = 2L),
                    listOf(route("c", "/c")),
                ),
            ),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("Listing 3 open project(s) across 2 backend(s):"),
            "expected project+IDE count header; got:\n$text")
        val aIndex = text.indexOf("[1] a")
        val bIndex = text.indexOf("[2] b")
        val cIndex = text.indexOf("[3] c")
        assertTrue(aIndex >= 0 && bIndex > aIndex && cIndex > bIndex,
            "project entries must keep input order; got:\n$text")
    }

    @Test
    fun `IDE with no open projects is dropped from list but counted in summary`() {
        val listing = ProjectListing(
            markerRows = listOf(
                BackendRow.FromMarker(markerIde(name = "IntelliJ IDEA", pid = 1L), listOf(route("a", "/a"))),
                BackendRow.FromMarker(markerIde(name = "GoLand", pid = 2L), emptyList()),
            ),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("Listing 1 open project(s) across 2 backend(s):"),
            "empty-project IDE should still count as queried; got:\n$text")
        assertTrue(!text.contains("GoLand 2025.3.3 (build IU-253.21581.142, pid 2)"),
            "empty-project IDE should not get a project entry; got:\n$text")
    }

    @Test
    fun `all reachable IDEs with no open projects print no-open-projects message`() {
        val listing = ProjectListing(
            markerRows = listOf(BackendRow.FromMarker(markerIde(name = "GoLand", pid = 9L), emptyList())),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("No open projects across 1 backend(s)."),
            "expected explicit empty-projects message; got:\n$text")
        assertTrue(!text.contains("→"), "no project rows should render; got:\n$text")
    }

    // ----------------------------- skipped ---------------------------------

    @Test
    fun `unreachable marker IDE appears in skipped footer, not in projects`() {
        val listing = ProjectListing(
            markerRows = listOf(
                BackendRow.FromMarker(
                    ide = markerIde(name = "WebStorm", version = "2025.3.0", pid = 7L),
                    projects = null,
                    errorMessage = "timed out after 8s",
                )
            ),
            portRows = emptyList(),
        )
        val text = render(listing)
        assertTrue(text.contains("Skipped 1 backend that did not return a project snapshot:"),
            "expected unreachable footer; got:\n$text")
        assertTrue(text.contains("WebStorm 2025.3.0 (build IU-253.21581.142, pid 7): unreachable: timed out after 8s"),
            "expected unreachable identity + reason; got:\n$text")
        assertTrue(text.contains("    MCP Steroid: 0.0.0-test"),
            "expected plugin status line for skipped marker; got:\n$text")
        assertTrue(!text.contains("[1] WebStorm"), "unreachable IDE must not become a project row; got:\n$text")
    }

    @Test
    fun `port-only IDE appears in skipped footer and not in projects list`() {
        val listing = ProjectListing(
            markerRows = listOf(BackendRow.FromMarker(markerIde(pid = 1L), listOf(route("a", "/a")))),
            portRows = listOf(BackendRow.FromPort(portIde(port = 63342))),
        )
        val text = render(listing)
        assertTrue(text.contains("Skipped 1 backend with MCP Steroid not installed:"),
            "expected no-plugin footer; got:\n$text")
        assertTrue(text.contains("IntelliJ IDEA Ultimate (build IU-253.21581.142, port 63342): MCP Steroid: not installed"),
            "expected port IDE identity; got:\n$text")
        assertTrue(!text.contains("[2] IntelliJ IDEA Ultimate"),
            "port-only IDE must not become a project row; got:\n$text")
    }
}
