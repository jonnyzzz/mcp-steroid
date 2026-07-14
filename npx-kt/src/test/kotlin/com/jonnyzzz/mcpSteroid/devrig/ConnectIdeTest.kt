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
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = "IDEA",
        productFullName = "IntelliJ IDEA",
        edition = "IU",
        baselineVersion = 261,
        buildNumber = "261.1",
    )

    private fun capture(): Pair<PrintStream, ByteArrayOutputStream> {
        val buf = ByteArrayOutputStream()
        return PrintStream(buf, true, "UTF-8") to buf
    }

    @Test
    fun `only built-in-server ports are candidates`() {
        val candidates = builtInServerCandidates(setOf(ide(63342), ide(64342), ide(63350), ide(70000)))
        assertEquals(listOf(63342, 63350), candidates.map { it.port })
    }

    @Test
    fun `install url is well formed`() {
        assertEquals(
            "http://127.0.0.1:63342/api/installPlugin?action=install&pluginId=com.jonnyzzz.mcpSteroid",
            installPluginUrl("http://127.0.0.1:63342/", "install", MCP_STEROID_PLUGIN_ID),
        )
    }

    @Test
    fun `run issues checkCompatibility then install per candidate and prints the nudge`() {
        val urls = mutableListOf<String>()
        val client = InstallPluginClient { url -> urls += url; InstallPluginResponse(200, "{\"compatible\":true}") }
        val (out, outBuf) = capture()
        val (err, _) = capture()

        val code = runBlocking { runConnectIde(setOf(ide(63342)), client, out, err, MCP_STEROID_PLUGIN_ID) }

        assertEquals(0, code)
        assertEquals(2, urls.size)
        assertContains(urls[0], "action=checkCompatibility")
        assertContains(urls[1], "action=install")
        assertContains(outBuf.toString("UTF-8"), "approve")
    }

    @Test
    fun `run reports when no IDE is reachable`() {
        val client = InstallPluginClient { error("must not be called") }
        val (out, outBuf) = capture()
        val (err, _) = capture()

        val code = runBlocking { runConnectIde(emptySet(), client, out, err, MCP_STEROID_PLUGIN_ID) }

        assertEquals(1, code)
        assertTrue(outBuf.toString("UTF-8").contains("No running JetBrains IDE"))
    }
}
