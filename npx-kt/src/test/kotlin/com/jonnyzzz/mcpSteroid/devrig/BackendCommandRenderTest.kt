/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.startableBackends
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-text test for the `backend` subcommand's 3-group renderer.
 * Decoupled from `runBackendCommand` so every output branch is exercisable
 * without HTTP / coroutines / marker files on disk.
 *
 * The format is part of the CLI's contract — scripts can grep this output.
 * Pin every visible string here so refactors that silently change wording fail the build.
 */
class BackendCommandRenderTest {

    private fun render(
        s1: List<DiscoveredIde> = emptyList(),
        s2: Set<DiscoveredIdeByPort> = emptySet(),
        s3: List<InstalledBackend> = emptyList(),
    ): String {
        val buf = ByteArrayOutputStream()
        renderBackendOutput3(s1, s2, s3, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun markerIde(
        name: String,
        version: String,
        pid: Long,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://127.0.0.1:65000/mcp",
        ideHome: String? = "/mock/ide/home",
        backendName: String = "mock-backend-name",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = backendName,
            ideHome = ideHome,
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

    private fun installedBackend(
        id: String = "goland-2026.1",
        name: String = "GoLand",
        version: String = "2026.1",
        build: String = "GO-261.1",
        ideHome: String = "/home/user/.mcp-steroid/backends/goland-2026.1/bundle-goland-2026.1",
    ) = InstalledBackend(
        id = id,
        ide = IdeInfo(name = name, version = version, build = build),
        ideHome = ideHome,
        launcher = Path.of("$ideHome/bin/goland.sh"),
    )

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
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "1", 1L)))
        assertTrue(text.endsWith("\n\n"),
            "output must end with a blank line; got tail: '${text.takeLast(8).replace("\n", "\\n")}'")
    }

    @Test
    fun `code-point width treats surrogate pairs as one visual column`() {
        val text = "🚀 foo"
        assertEquals(5, text.codePointWidth())
        assertEquals("🚀 foo  ", text.padEndCodePoints(7))
        assertEquals(7, text.padEndCodePoints(7).codePointWidth())
    }

    @Test
    fun `empty inputs prints the no-backends message + trailing blank`() {
        val text = render()
        assertTrue(text.contains("No backends detected."), "missing message; got:\n$text")
        val lines = text.lines()
        assertEquals("No backends detected.", lines[0])
        assertEquals("", lines[1])
    }

    // --------------------- S1: MCP Steroid backends -----------------------

    @Test
    fun `single MCP Steroid IDE shows section header with count and IDE identity`() {
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L)))
        assertTrue(text.contains("MCP Steroid backends (1):"),
            "expected section header with count; got:\n$text")
        assertTrue(text.contains("[1] IntelliJ IDEA 2025.3.3 (mock-backend-name; build IU-253.21581.142, pid 1234)"),
            "expected numbered entry with backend_name+version+build+locator (#155); got:\n$text")
        assertTrue(text.contains("MCP Steroid: 0.0.0-test"),
            "expected MCP Steroid plugin status; got:\n$text")
    }

    @Test
    fun `S1 IDE with no MCP Steroid plugin name falls back to MCP Steroid label`() {
        val ideInfo = IdeInfo(name = "GoLand", version = "2026.1", build = "GO-261.1")
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "", version = "0.9.0")
        val ide = DiscoveredIde(
            pid = 99L,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:6315/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = "mock-backend-name",
        )
        val text = render(s1 = listOf(ide))
        assertTrue(text.contains("MCP Steroid: 0.9.0"), "expected fallback plugin label; got:\n$text")
    }

    @Test
    fun `S1 section shows (none) when empty`() {
        val text = render(s2 = setOf(portIde()))
        assertTrue(text.contains("MCP Steroid backends (0):"), "expected zero-count header; got:\n$text")
        assertTrue(text.contains("  (none)"), "expected (none) for empty S1; got:\n$text")
    }

    // ---------------------- S2: Other running IDEs -------------------------

    @Test
    fun `S2 port-discovered IDE shows section header and IDE identity with provision hint`() {
        val text = render(s2 = setOf(portIde(port = 63342)))
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (1):"),
            "expected S2 section header; got:\n$text")
        assertTrue(text.contains("[1] IntelliJ IDEA Ultimate (${backendNameForPort(63342, "IU-253.21581.142")}; build IU-253.21581.142, port 63342) (run: devrig backend provision port-63342)"),
            "expected S2 entry with backend_name + provision hint; got:\n$text")
        assertTrue(text.contains("MCP Steroid: not installed"),
            "expected not-installed status; got:\n$text")
    }

    @Test
    fun `S2 section shows (none) when empty`() {
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", 1L)))
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (0):"),
            "expected zero-count header; got:\n$text")
        assertTrue(text.contains("  (none)"), "expected (none) for empty S2; got:\n$text")
    }

    @Test
    fun `group 2 promotes the install command for a plugin-less port IDE`() {
        val text = render(s2 = setOf(portIde(port = 63342)))
        assertTrue(text.contains("Install / update MCP Steroid in the IDE(s) above:  devrig install plugin"),
            "expected the install-command promotion under group 2; got:\n$text")
        assertTrue(text.contains("Choose Plugins to Install or Enable"),
            "promotion must set the user's expectation of a native confirmation dialog; got:\n$text")
    }

    @Test
    fun `group 2 promotes the install command for an incompatible (old plugin) marker`() {
        val incompatible = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1L, ideHome = null)
        val text = render(s1 = listOf(incompatible))
        assertTrue(text.contains("Install / update MCP Steroid in the IDE(s) above:  devrig install plugin"),
            "an incompatible marker must also be offered the install/update command; got:\n$text")
    }

    @Test
    fun `no install-command promotion when group 2 is empty`() {
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", 1L)))
        assertFalse(text.contains("devrig install plugin"),
            "the promotion must not appear when every IDE already has a compatible plugin; got:\n$text")
    }

    @Test
    fun `S2 port IDEs are sorted by port`() {
        val text = render(s2 = setOf(portIde(port = 63350), portIde(port = 63342)))
        val idx342 = text.indexOf("port 63342")
        val idx350 = text.indexOf("port 63350")
        assertTrue(idx342 in 0..<idx350, "port IDEs should be sorted ascending; got:\n$text")
    }

    @Test
    fun `S2 port-discovered IDE falls back to productName when productFullName is null`() {
        val text = render(s2 = setOf(portIde(productFullName = null, productName = "IDEA")))
        assertTrue(text.contains("[1] IDEA "), "expected fallback to productName; got:\n$text")
    }

    @Test
    fun `S2 port-discovered IDE drops build segment when buildNumber is null`() {
        val text = render(s2 = setOf(portIde(buildNumber = null)))
        assertTrue(text.contains("; port 63342) (run: devrig backend provision port-63342)"),
            "no buildNumber → locator should be `port N` only after the backend_name; got:\n$text")
        assertFalse(text.contains("build ,"), "must not produce 'build , port …' when buildNumber is null; got:\n$text")
    }

    @Test
    fun `S2 port-discovered IDE with no productFullName or productName renders fallback`() {
        val text = render(s2 = setOf(portIde(productFullName = null, productName = null)))
        assertTrue(text.contains("(unknown JetBrains IDE)"),
            "expected a defensive fallback name; got:\n$text")
    }

    // ---------------------- S3: Installed, not running ---------------------

    @Test
    fun `S3 installed backend shows section header and IDE identity`() {
        val installed = installedBackend()
        val text = render(s3 = listOf(installed))
        assertTrue(text.contains("Installed, not running (startable) (1):"),
            "expected S3 section header; got:\n$text")
        assertTrue(text.contains("[1] GoLand 2026.1 (${startableBackendName(installed)}; managed: goland-2026.1)"),
            "expected S3 entry with backend_name; got:\n$text")
        assertTrue(text.contains("ideHome: /home/user/.mcp-steroid/backends/goland-2026.1/bundle-goland-2026.1"),
            "expected ideHome line; got:\n$text")
    }

    @Test
    fun `S3 section shows (none) when empty`() {
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", 1L)))
        assertTrue(text.contains("Installed, not running (startable) (0):"),
            "expected zero-count header; got:\n$text")
        assertTrue(text.contains("  (none)"), "expected (none) for empty S3; got:\n$text")
    }

    // ---------------------- footer -----------------------------------------

    @Test
    fun `output includes the install footer pointing at the full-cycle download command`() {
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", 1L)))
        assertTrue(text.contains("To install another IDE: devrig backend download"),
            "expected install footer; got:\n$text")
    }

    // ---------------------- mixed ------------------------------------------

    @Test
    fun `all three sections present in correct order`() {
        val text = render(
            s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", 1L)),
            s2 = setOf(portIde(port = 63342)),
            s3 = listOf(installedBackend()),
        )
        val s1Idx = text.indexOf("MCP Steroid backends")
        val s2Idx = text.indexOf("Other IDEs (incompatible or no MCP Steroid)")
        val s3Idx = text.indexOf("Installed, not running")
        val footerIdx = text.indexOf("To install another IDE")
        assertTrue(s1Idx < s2Idx, "S1 should come before S2; got:\n$text")
        assertTrue(s2Idx < s3Idx, "S2 should come before S3; got:\n$text")
        assertTrue(s3Idx < footerIdx, "S3 should come before footer; got:\n$text")
    }

    @Test
    fun `S1 marker has no duplicate version from name`() {
        val text = render(s1 = listOf(
            markerIde("IntelliJ IDEA 2026.1.4", "2026.1.4", pid = 1234L, build = "IU-261.1")
        ))
        assertTrue(text.contains("[1] IntelliJ IDEA 2026.1.4 (mock-backend-name; build IU-261.1, pid 1234)"), text)
        assertFalse(text.contains("2026.1.4 2026.1.4"), text)
    }

    @Test
    fun `S1 backends are sorted by backend_name`() {
        // Same ordering as the MCP backends[] lookup (#155), whatever the discovery order.
        val late = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1L, backendName = "zz-late")
        val early = markerIde("GoLand", "2025.3.3", pid = 2L, backendName = "aa-early")
        val text = render(s1 = listOf(late, early))
        val i1 = text.indexOf("[1] GoLand 2025.3.3 (aa-early;")
        val i2 = text.indexOf("[2] IntelliJ IDEA 2025.3.3 (zz-late;")
        assertTrue(i1 in 0 until i2, "S1 must sort by backend_name; got:\n$text")
    }
    // -------------------- Finding C: compatibility by ideHome ----------------

    @Test
    fun `incompatible marker (no ideHome) renders in group 2 not group 1`() {
        val incompatible = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L, ideHome = null)
        val text = render(s1 = listOf(incompatible))
        assertTrue(text.contains("MCP Steroid backends (0):"),
            "group 1 must be empty for incompatible marker; got:\n$text")
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (1):"),
            "group 2 must have the incompatible marker; got:\n$text")
        assertTrue(text.contains("incompatible plugin, old version"),
            "incompatible entry must be labeled; got:\n$text")
        assertTrue(text.contains("(incompatible)"),
            "plugin status must append (incompatible); got:\n$text")
    }

    @Test
    fun `compatible marker (has ideHome) renders in group 1 not group 2`() {
        val compatible = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L, ideHome = "/home/idea")
        val text = render(s1 = listOf(compatible))
        assertTrue(text.contains("MCP Steroid backends (1):"),
            "group 1 must have 1 entry for compatible marker; got:\n$text")
        assertFalse(text.contains("incompatible plugin"),
            "compatible IDE must not be labeled incompatible; got:\n$text")
    }

    // -------------------- Finding B: no duplicates in group 2 ----------------

    @Test
    fun `port IDE matching a compatible marker build is deduplicated out of group 2`() {
        val build = "IU-253.21581.142"
        val marker = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L, build = build, ideHome = "/home/idea")
        val portWithSameBuild = portIde(port = 63342, buildNumber = build)
        val text = render(s1 = listOf(marker), s2 = setOf(portWithSameBuild))
        // Group 2 should have 0 entries since port IDE matches marker build
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (0):"),
            "port IDE matching marker build must be deduped; got:\n$text")
    }

    @Test
    fun `port IDE matching an incompatible marker build is also deduplicated`() {
        val build = "IU-253.21581.142"
        val incompatible = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L, build = build, ideHome = null)
        val portWithSameBuild = portIde(port = 63342, buildNumber = build)
        val text = render(s1 = listOf(incompatible), s2 = setOf(portWithSameBuild))
        // Group 2: 1 (incompatible marker), port IDE with same build is deduped out
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (1):"),
            "incompatible marker should be present but port IDE with same build deduped; got:\n$text")
        assertFalse(text.contains("[2]"),
            "only one entry expected, not two; got:\n$text")
    }

    @Test
    fun `port IDE with different build is NOT deduplicated`() {
        val marker = markerIde("IntelliJ IDEA", "2025.3.3", pid = 1234L, build = "IU-253.21581.142", ideHome = "/home/idea")
        val portWithDifferentBuild = portIde(port = 63342, buildNumber = "PC-253.999")
        val text = render(s1 = listOf(marker), s2 = setOf(portWithDifferentBuild))
        // Group 2 must have 1 entry (the port IDE with a different build)
        assertTrue(text.contains("Other IDEs (incompatible or no MCP Steroid) (1):"),
            "port IDE with different build must NOT be deduped; got:\n$text")
    }

    // -------------------- Finding B: runningManagedIds exclusion ----------------

    @Test
    fun `managed backend whose id is in runningManagedIds is NOT rendered in group 3`() {
        val installed = installedBackend(id = "goland-2026.1")
        // With the managed id in runningManagedIds, startableBackends() should exclude it
        val s3WithExclusion = startableBackends(
            installed = listOf(installed),
            running = emptyList(),
            runningManagedIds = setOf("goland-2026.1"),
        )
        // Provide a running s1 IDE so we get the full render (not the "No backends detected." shortcut)
        val text = render(s1 = listOf(markerIde("IntelliJ IDEA", "2026.1", pid = 1L)), s3 = s3WithExclusion)
        assertTrue(text.contains("Installed, not running (startable) (0):"),
            "managed running backend must be excluded from group 3; got:\n$text")
        assertFalse(text.contains("goland-2026.1"),
            "excluded managed backend must not appear in output; got:\n$text")
    }

    @Test
    fun `managed backend whose id is NOT in runningManagedIds IS rendered in group 3`() {
        val installed = installedBackend(id = "goland-2026.1")
        val s3 = startableBackends(
            installed = listOf(installed),
            running = emptyList(),
            runningManagedIds = emptySet(),
        )
        val text = render(s3 = s3)
        assertTrue(text.contains("Installed, not running (startable) (1):"),
            "non-running managed backend must appear in group 3; got:\n$text")
        assertTrue(text.contains("GoLand"),
            "non-running managed backend must be rendered; got:\n$text")
    }

}
