/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectIdeTest {
    private fun ide(port: Int) = DiscoveredIdeByPort(
        port = port, baseUrl = "http://127.0.0.1:$port", productName = "IDEA",
        productFullName = "IntelliJ IDEA", edition = "IU", baselineVersion = 261, buildNumber = "261.1",
    )
    private fun capture(): Pair<PrintStream, ByteArrayOutputStream> {
        val buf = ByteArrayOutputStream()
        return PrintStream(buf, true, "UTF-8") to buf
    }
    private val okClient = InstallPluginClient { InstallPluginResponse(200, "{\"compatible\":true}") }
    private val failClient = InstallPluginClient { InstallPluginResponse(403, "nope") }
    private val browserOk = BrowserLauncher { true }
    private val browserFail = BrowserLauncher { false }

    @Test fun `only built-in-server ports are candidates`() {
        assertEquals(listOf(63342, 63350), builtInServerCandidates(setOf(ide(63342), ide(64342), ide(63350), ide(70000))).map { it.port })
    }

    @Test fun `install url is well formed`() {
        assertEquals(
            "http://127.0.0.1:63342/api/installPlugin?action=install&pluginId=com.jonnyzzz.mcp-steroid",
            installPluginUrl("http://127.0.0.1:63342/", "install", MCP_STEROID_PLUGIN_ID),
        )
    }

    @Test fun `browser open argv is per-OS`() {
        assertEquals(listOf("open", "u"), browserOpenArgv(HostOs.MAC, "u"))
        assertEquals(listOf("xdg-open", "u"), browserOpenArgv(HostOs.LINUX, "u"))
        assertEquals(listOf("cmd", "/c", "start", "", "u"), browserOpenArgv(HostOs.WINDOWS, "u"))
    }

    @Test fun `detectHostOs maps os name`() {
        assertEquals(HostOs.MAC, detectHostOs("Mac OS X"))
        assertEquals(HostOs.WINDOWS, detectHostOs("Windows 11"))
        assertEquals(HostOs.LINUX, detectHostOs("Linux"))
    }

    @Test fun `already-connected when a plugin marker exists — no offer`() {
        val (out, buf) = capture(); val (err, _) = capture()
        val client = InstallPluginClient { error("must not be called") }
        val browser = BrowserLauncher { error("must not be called") }
        val outcome = runBlocking { runConnectIde(setOf(ide(63342)), pluginMarkerCount = 1, client, browser, out, err) }
        assertEquals(ConnectIdeOutcome.ALREADY_CONNECTED, outcome)
        assertContains(buf.toString("UTF-8"), "already")
    }

    @Test fun `no IDE reachable`() {
        val (out, buf) = capture(); val (err, _) = capture()
        val outcome = runBlocking { runConnectIde(emptySet(), 0, okClient, browserOk, out, err) }
        assertEquals(ConnectIdeOutcome.NO_IDE, outcome)
        assertTrue(buf.toString("UTF-8").contains("No running JetBrains IDE"))
    }

    @Test fun `B succeeds — offered via http, browser untouched`() {
        val (out, buf) = capture(); val (err, _) = capture()
        val browser = BrowserLauncher { error("must not be called") }
        val outcome = runBlocking { runConnectIde(setOf(ide(63342)), 0, okClient, browser, out, err) }
        assertEquals(ConnectIdeOutcome.OFFERED_VIA_HTTP, outcome)
        assertContains(buf.toString("UTF-8"), "approve")
    }

    @Test fun `B fails then browser opens — offered via browser`() {
        val (out, buf) = capture(); val (err, _) = capture()
        val outcome = runBlocking { runConnectIde(setOf(ide(63342)), 0, failClient, browserOk, out, err) }
        assertEquals(ConnectIdeOutcome.OFFERED_VIA_BROWSER, outcome)
        assertContains(buf.toString("UTF-8"), "browser")
    }

    @Test fun `B fails and browser fails — manual instructions`() {
        val (out, buf) = capture(); val (err, _) = capture()
        val outcome = runBlocking { runConnectIde(setOf(ide(63342)), 0, failClient, browserFail, out, err) }
        assertEquals(ConnectIdeOutcome.MANUAL_INSTRUCTIONS, outcome)
        val s = buf.toString("UTF-8")
        assertContains(s, "Settings")
        assertContains(s, CUSTOM_REPO_URL)
    }

    @Test fun `exit code maps outcome to process exit status`() {
        assertEquals(1, connectIdeExitCode(ConnectIdeOutcome.NO_IDE))
        assertEquals(1, connectIdeExitCode(ConnectIdeOutcome.MANUAL_INSTRUCTIONS))
        assertEquals(0, connectIdeExitCode(ConnectIdeOutcome.ALREADY_CONNECTED))
        assertEquals(0, connectIdeExitCode(ConnectIdeOutcome.OFFERED_VIA_HTTP))
        assertEquals(0, connectIdeExitCode(ConnectIdeOutcome.OFFERED_VIA_BROWSER))
    }
}
