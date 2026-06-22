/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Pure-text test for the `backend` subcommand's renderer. Decoupled from
 * `runBackendCommand` so every output branch is exercisable without HTTP /
 * coroutines / marker files on disk.
 *
 * The format is part of the CLI's contract — scripts can grep this output.
 * Pin every visible string here so refactors that silently change wording
 * fail the build.
 */
class BackendCommandRenderTest {

    private fun render(rows: List<BackendRow>): String {
        val buf = ByteArrayOutputStream()
        renderBackendOutput(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun markerIde(
        name: String,
        version: String,
        pid: Long,
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
     * and [ProjectRoute.projectPath], plus [ProjectRoute.exposedProjectName]
     * in the JSON form; the embedded [DiscoveredIde] is never inspected here,
     * so a throwaway one keeps the fixture minimal.
     */
    private fun route(name: String, path: String): ProjectRoute =
        ProjectRoute(
            route = markerIde(name = name, version = "1", pid = 0L),
            projectInfo = IdeProjectState(name = name, projectPath = path),
            exposedProjectName = "$name-rendertst",
            projectPath = path,
        )

    // ------------------------------ shape ---------------------------------

    @Test
    fun `empty output starts with the no-backends message`() {
        val text = render(emptyList())
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    @Test
    fun `output ends with a trailing blank line so shells separate the prompt cleanly`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("IntelliJ IDEA", "1", 1L), listOf(route("p", "/p")))
        )
        val text = render(rows)
        assertTrue(text.endsWith("\n\n"),
            "output must end with a blank line; got tail: '${text.takeLast(8).replace("\n", "\\n")}'")
    }

    // -------------------------- empty / no-backend branch ----------------------

    @Test
    fun `code-point width treats surrogate pairs as one visual column`() {
        val text = "🚀 foo"

        assertEquals(5, text.codePointWidth())
        assertEquals("🚀 foo  ", text.padEndCodePoints(7))
        assertEquals(7, text.padEndCodePoints(7).codePointWidth())
    }

    @Test
    fun `empty row list prints the no-backends message + trailing blank`() {
        val text = render(emptyList())
        assertTrue(text.contains("No backends detected."), "missing message; got:\n$text")
        val lines = text.lines()
        // Layout: [0]="No backends detected.", [1]="", [2]="".
        // (the [2] is the empty tail after the final \n).
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    // --------------------- marker rows: happy paths ------------------------

    @Test
    fun `single marker IDE shows index, name, version, locator, and project mapping`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L),
                projects = listOf(route(name = "my-app", path = "/Users/x/Work/my-app")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("Discovered 1 backend:"),
            "expected list header for one IDE (singular); got:\n$text")
        assertTrue(text.contains("[1] IntelliJ IDEA 2025.3.3 (build IU-253.21581.142, pid 1234)"),
            "expected numbered list entry with version+build+locator; got:\n$text")
        assertTrue(text.contains("MCP Steroid: 0.0.0-test"),
            "expected MCP Steroid plugin status; got:\n$text")
        assertTrue(text.contains("my-app") && text.contains("→") && text.contains("/Users/x/Work/my-app"),
            "expected project to render as name → path; got:\n$text")
    }

    @Test
    fun `marker IDE does not duplicate version already present in name`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA 2026.1.4", "2026.1.4", pid = 1234L, build = "IU-261.1"),
                projects = listOf(route(name = "my-app", path = "/Users/x/Work/my-app")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("[1] IntelliJ IDEA 2026.1.4 (build IU-261.1, pid 1234)"), text)
        assertFalse(text.contains("2026.1.4 2026.1.4"), text)
    }

    @Test
    fun `marker IDE with multiple projects renders an aligned name to path table`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("PyCharm", "2025.3.1", pid = 4242L),
                projects = listOf(
                    route(name = "alpha", path = "/p/alpha"),
                    route(name = "bravo", path = "/p/bravo"),
                ),
            )
        )
        val text = render(rows)
        // Both projects MUST appear.
        assertTrue(text.contains("alpha") && text.contains("/p/alpha"), text)
        assertTrue(text.contains("bravo") && text.contains("/p/bravo"), text)
        // Arrows MUST line up: pad both names to the same width.
        val arrowColumns = text.lines().filter { it.contains("→") }.map { it.indexOf('→') }
        assertEquals(arrowColumns.toSet().size, 1,
            "all project-list arrows must be in the same column; got columns: $arrowColumns in:\n$text")
    }

    @Test
    fun `project name table aligns arrows when a name contains an emoji surrogate pair`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("PyCharm", "2025.3.1", pid = 4242L),
                projects = listOf(
                    route(name = "🚀 app", path = "/p/rocket"),
                    route(name = "plain", path = "/p/plain"),
                ),
            )
        )
        val text = render(rows)

        assertTrue(text.contains("🚀 app") && text.contains("/p/rocket"), text)
        assertTrue(text.contains("plain") && text.contains("/p/plain"), text)
        val arrowDisplayColumns = text.lines()
            .filter { it.contains("→") }
            .map { it.substringBefore("→").codePointWidth() }
        assertEquals(
            1,
            arrowDisplayColumns.toSet().size,
            "all project-list arrows must be in the same visual column; got columns: $arrowDisplayColumns in:\n$text",
        )
    }

    @Test
    fun `multiple IDE entries are numbered sequentially and separated by blank lines`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("IntelliJ IDEA", "2025.3.3", 1L), listOf(route("a", "/a"))),
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 2L), listOf(route("b", "/b"))),
        )
        val text = render(rows)
        assertTrue(text.contains("Discovered 2 backends:"),
            "expected plural list header; got:\n$text")
        assertTrue(text.contains("[1] IntelliJ IDEA"), "got:\n$text")
        assertTrue(text.contains("[2] PyCharm"), "got:\n$text")
        val firstIdx = text.indexOf("[1]")
        val secondIdx = text.indexOf("[2]")
        assertTrue(firstIdx < secondIdx, "list items must keep input order; got:\n$text")
    }

    // --------------------- marker rows: edge cases -------------------------

    @Test
    fun `marker IDE with empty projects list prints 'no open projects'`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("GoLand", "2025.3.0", pid = 99L),
                projects = emptyList(),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("GoLand"), text)
        assertTrue(text.contains("(no open projects)"),
            "should signal empty-list case explicitly: $text")
    }

    @Test
    fun `unreachable marker IDE prints the error reason on its own line`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("WebStorm", "2025.3.0", pid = 7L),
                projects = null,
                errorMessage = "timed out after 8.seconds",
            )
        )
        val text = render(rows)
        assertTrue(text.contains("(unreachable: timed out after 8.seconds)"),
            "should surface the error: $text")
    }

    @Test
    fun `unreachable marker IDE with null errorMessage still prints a coherent fallback`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("Rider", "2025.3.0", pid = 8L),
                projects = null,
                errorMessage = null,
            )
        )
        val text = render(rows)
        assertTrue(text.contains("(unreachable: unreachable)"),
            "should not produce 'null' in user-visible output: $text")
    }

    @Test
    fun `marker IDE name and version with spaces are not mangled in the entry line`() {
        val rows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA Ultimate", "2025.3.3 EAP", 1L),
                projects = listOf(route("p", "/p")),
            )
        )
        val text = render(rows)
        assertTrue(text.contains("[1] IntelliJ IDEA Ultimate 2025.3.3 EAP (build IU-253.21581.142, pid 1)"),
            "got: $text")
    }

    // ---------------------- port rows (NEW coverage) -----------------------

    @Test
    fun `port-discovered IDE shows productFullName + buildNumber + port locator`() {
        // This is the case the user hit: an IDE running with the IntelliJ
        // built-in HTTP server reachable, but no `.mcp-steroid` marker.
        val rows = listOf(BackendRow.FromPort(portIde(port = 63342)))
        val text = render(rows)
        // Port-discovered IDEs surface productFullName as the display header
        // (it already carries the marketing version from /api/about). The build
        // number lives in the locator parens, so the line doesn't double up on
        // version-like tokens.
        assertTrue(text.contains("[1] IntelliJ IDEA Ultimate (build IU-253.21581.142, port 63342) (run: devrig backend provision port-63342)"),
            "expected the full IDE header line; got:\n$text")
        assertTrue(text.contains("MCP Steroid: not installed"),
            "must explain why projects are unavailable; got:\n$text")
        assertTrue(text.contains("(project list unavailable)"),
            "must explain why projects are unavailable; got:\n$text")
    }

    @Test
    fun `port-discovered IDE falls back to productName when productFullName is null`() {
        val rows = listOf(BackendRow.FromPort(portIde(productFullName = null, productName = "IDEA")))
        val text = render(rows)
        assertTrue(text.contains("[1] IDEA "), "expected fallback to productName; got:\n$text")
    }

    @Test
    fun `port-discovered IDE drops build segment from the locator when buildNumber is null`() {
        // When the IDE doesn't expose a build number (some older builds), the
        // locator should NOT print an empty `build, port N`. Just port.
        val rows = listOf(BackendRow.FromPort(portIde(buildNumber = null)))
        val text = render(rows)
        assertTrue(text.contains("(port 63342) (run: devrig backend provision port-63342)"),
            "no buildNumber → locator should be `port N` only; got:\n$text")
        assertTrue(!text.contains("build ,"),
            "must not produce 'build , port …' when buildNumber is null; got:\n$text")
    }

    @Test
    fun `port-discovered IDE with no productFullName, no productName -- still renders a header`() {
        // A JSON 200 from some other service that looks vaguely like /api/about
        // should still produce a coherent line (the discovery layer would have
        // filtered it out, but the renderer is defensive).
        val rows = listOf(BackendRow.FromPort(portIde(productFullName = null, productName = null)))
        val text = render(rows)
        assertTrue(text.contains("(unknown JetBrains IDE)"),
            "expected a defensive fallback name; got:\n$text")
    }

    // --------------- mixed list (marker + port) --------------------------

    @Test
    fun `mixed list renders marker rows first, then port rows`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), listOf(route("p", "/p"))),
            BackendRow.FromPort(portIde(port = 63342, productFullName = "GoLand")),
        )
        val text = render(rows)
        val pycharmIndex = text.indexOf("PyCharm")
        val golandIndex = text.indexOf("GoLand")
        assertTrue(pycharmIndex < golandIndex,
            "marker row (PyCharm) should appear before port row (GoLand); got:\n$text")
    }

    @Test
    fun `mixed list separates marker and port rows with the same blank-line rule`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
        )
        val text = render(rows)
        // Numbered entries with an exclusive blank-line separator between them.
        val entry1 = text.indexOf("[1]")
        val entry2 = text.indexOf("[2]")
        assertTrue(entry1 in 0..<entry2, "expected two numbered entries; got:\n$text")
        val between = text.substring(entry1, entry2)
        assertTrue(between.contains("\n\n"),
            "expected at least one blank line between [1] and [2]; got slice:\n$between")
    }

    // ----------------------- deduplication (mergeRows) ---------------------

    @Test
    fun `mergeRows drops a port IDE whose build matches a marker row`() {
        // The same running IDE shows up in BOTH discoveries when it has the
        // mcp-steroid plugin AND its built-in HTTP server is on a scanned port.
        // The marker row carries the project list; the port row is redundant.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63342, buildNumber = "IU-253.21581.142"))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(1, merged.size, "duplicate IDE should collapse to one row; got: $merged")
        assertTrue(merged.single() is BackendRow.FromMarker,
            "should keep the marker row (it has projects); got: ${merged.single()}")
    }

    @Test
    fun `mergeRows drops a port IDE whose unprefixed build matches a prefixed marker build`() {
        // The real-world case: marker carries `IU-261.23567.138`, /api/about
        // returns `261.23567.138` (no product-code prefix). Both refer to the
        // same running IDE; dedup must collapse them.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2026.1.1", 1L, build = "IU-261.23567.138"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63342, buildNumber = "261.23567.138"))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(1, merged.size, "prefix-normalised builds must match; got: $merged")
        assertTrue(merged.single() is BackendRow.FromMarker)
    }

    @Test
    fun `mergeRows keeps a port IDE whose build does NOT match any marker`() {
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63343, buildNumber = "GO-253.99.0"))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(2, merged.size, "different-build IDE should remain; got: $merged")
    }

    @Test
    fun `mergeRows keeps a port IDE with null buildNumber even when markers are present`() {
        // We can't decide "same IDE" without a build identifier. Better to
        // show the user one extra row than to hide a real running IDE.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = "IU-253.21581.142"),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63344, buildNumber = null))

        val merged = mergeRows(markerRows, portIdes)
        assertEquals(2, merged.size, "null-build port row should NOT be deduplicated away; got: $merged")
    }

    @Test
    fun `mergeRows preserves marker order and sorts port rows by port`() {
        val markerRows = listOf(
            BackendRow.FromMarker(markerIde("PyCharm", "2025.3.1", 1L), emptyList()),
            BackendRow.FromMarker(markerIde("IntelliJ IDEA", "2025.3.3", 2L), emptyList()),
        )
        val portIdes = setOf(
            portIde(port = 63350, buildNumber = "X-1"),
            portIde(port = 63345, buildNumber = "Y-2"),
        )

        val merged = mergeRows(markerRows, portIdes)
        val ports = merged.filterIsInstance<BackendRow.FromPort>().map { it.ide.port }
        assertEquals(listOf(63345, 63350), ports, "port rows must sort ascending; got: $merged")
        val markerIndex = merged.indexOfFirst { it is BackendRow.FromMarker }
        val firstPortIndex = merged.indexOfFirst { it is BackendRow.FromPort }
        assertTrue(markerIndex < firstPortIndex, "markers must come before ports; got: $merged")
    }

    @Test
    fun `mergeRows on empty inputs returns empty list`() {
        assertEquals(emptyList<BackendRow>(), mergeRows(emptyList(), emptySet()))
    }

    @Test
    fun `mergeRows tolerates marker rows with null build`() {
        // PidMarker.ide.build is non-nullable on the wire, but be defensive:
        // dedup must not throw when comparing nulls.
        val markerRows = listOf(
            BackendRow.FromMarker(
                ide = markerIde("IntelliJ IDEA", "2025.3.3", 1L, build = ""),
                projects = emptyList(),
            )
        )
        val portIdes = setOf(portIde(port = 63345))

        val merged = mergeRows(markerRows, portIdes)
        assertFalse(merged.isEmpty(), "should not collapse to empty; got: $merged")
    }
}
