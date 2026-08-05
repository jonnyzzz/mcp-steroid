/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * Parses a real `<pid>.mcp-steroid` marker captured verbatim from a live machine (a running
 * GoLand 2026.1.3 instance under `~/.mcp-steroid/markers/`). Only the bearer tokens / `_ijt`
 * session token are redacted; every other byte is exactly what the plugin wrote to disk.
 *
 * This is the regression guard that [PidMarkerJson] keeps decoding the markers IDEs actually
 * produce — full schema-1 document with all four transport sub-objects populated (one `null`).
 * The synthetic round-trip in [PidMarkerTest] can drift from the real on-disk shape; this can't.
 */
class PidMarkerRealFileTest {

    private fun loadMarkerResource(name: String): String =
        javaClass.getResourceAsStream(name)?.bufferedReader()?.use { it.readText() }
            ?: fail("missing test resource: $name")

    @Test
    fun `parses a real on-disk marker captured from a live IDE`() {
        val text = loadMarkerResource("/pid-marker/goland-real-example.mcp-steroid")

        val marker = PidMarkerJson.decode(text)

        // Required fields.
        assertEquals(PidMarker.SCHEMA_VERSION, marker.schema)
        assertEquals(13940L, marker.pid)
        assertEquals("GoLand 2026.1.3", marker.ide.name)
        assertEquals("2026.1.3", marker.ide.version)
        assertEquals("GO-261.25134.147", marker.ide.build)
        assertEquals("com.jonnyzzz.mcp-steroid", marker.plugin.id)
        assertEquals("MCP Steroid", marker.plugin.name)
        assertEquals("0.100-409f23a2", marker.plugin.version)
        assertEquals("2026-06-22T11:35:33.211077Z", marker.createdAt)

        // devrig bridge — the only transport devrig actually consumes.
        val devrig = marker.devrigEndpoint ?: fail("a real marker advertises the devrig bridge endpoint")
        assertEquals("http://localhost:6317/api/jonnyzzz/mcp-steroid/v1", devrig.rpcBaseUrl)
        assertTrue(devrig.headers.containsKey("Authorization"), "devrig endpoint carries an auth header")

        // MCP-client transport.
        val mcp = marker.mcpSteroidServer ?: fail("a real marker advertises the /mcp server")
        assertEquals("http://localhost:6317/mcp", mcp.mcpUrl)

        // IntelliJ built-in web server.
        val web = marker.intellijWebServer ?: fail("a real marker advertises the IntelliJ web server")
        assertTrue(web.enabled)
        assertEquals("127.0.0.1", web.host)
        assertEquals(63344, web.port)
        assertEquals("http://127.0.0.1:63344", web.baseUrl)

        // This GoLand build exposes no built-in MCP server.
        assertNull(marker.intellijMcpServer)
    }
}
