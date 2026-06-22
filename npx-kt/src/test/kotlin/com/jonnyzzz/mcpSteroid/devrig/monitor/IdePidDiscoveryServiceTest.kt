/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdePidDiscoveryServiceTest {

    private val ourPid = ProcessHandle.current().pid()

    private fun marker(
        pid: Long,
        url: String,
        ideName: String = "IntelliJ IDEA",
    ): PidMarker =
        PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = url,
                headers = emptyMap(),
            ),
            devrigEndpoint = testDevrigEndpoint(url),
            ide = IdeInfo(name = ideName, version = "x", build = "y"),
            plugin = PluginInfo(id = "x", name = "y", version = "z"),
            createdAt = "2026-05-10T12:34:56Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )

    private fun writeMarker(homeDir: Path, pid: Long, url: String, ideName: String = "IntelliJ IDEA"): java.io.File =
        writeMarker(homeDir, marker(pid, url, ideName))

    private fun writeMarker(homeDir: Path, marker: PidMarker): java.io.File {
        val markerDir = PidMarker.markerDirectory(homeDir)
        Files.createDirectories(markerDir)
        val file = markerDir.resolve(PidMarker.markerFileNameFor(marker.pid)).toFile()
        file.writeText(PidMarkerJson.encode(marker))
        return file
    }

    private fun writeMarkerText(homeDir: Path, pid: Long, text: String): java.io.File {
        val markerDir = PidMarker.markerDirectory(homeDir)
        Files.createDirectories(markerDir)
        return markerDir.resolve(PidMarker.markerFileNameFor(pid)).toFile().also { it.writeText(text) }
    }

    private fun service(homeDir: Path): IdePidDiscoveryService =
        IdePidDiscoveryService(
            markersDir = PidMarker.markerDirectory(homeDir),
            allowHosts = listOf("localhost"),
        )

    @Test
    fun `scanOnce picks up a marker for the live current pid`(@TempDir homeDir: Path) {
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        val service = service(homeDir)

        val ides = service.stateSnapshot()
        assertEquals(1, ides.size)
        val ide = ides.single()
        assertEquals(ourPid, ide.pid)
        assertEquals(testDevrigEndpoint("http://localhost:64531/mcp").rpcBaseUrl, ide.rpcBaseUrl)
        assertEquals("IntelliJ IDEA pid=$ourPid", ide.label)
    }

    @Test
    fun `scanOnce skips markers with disallowed host`(@TempDir homeDir: Path) {
        writeMarker(homeDir, ourPid, "http://malicious.example:8080/mcp")
        val service = service(homeDir)
        assertTrue(service.stateSnapshot().isEmpty())
    }

    @Test
    fun `scanOnce skips markers for processes that no longer exist`(@TempDir homeDir: Path) {
        // Spawn + wait → guaranteed-dead pid we can write a marker for.
        val process = ProcessBuilder("/bin/echo", "monitor-test").start()
        process.waitFor()
        writeMarker(homeDir, process.pid(), "http://localhost:1/mcp")

        val service = service(homeDir)
        assertFalse(service.stateSnapshot().any { it.pid == process.pid() })
    }

    @Test
    fun `scanOnce skips malformed marker files but keeps valid neighbours`(@TempDir homeDir: Path) {
        // Two corrupt markers around one valid one: ensures the scanner doesn't
        // trip on the bad files and silently drop everything else.
        writeMarkerText(homeDir, 101L, "not even close to JSON {{{")
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")
        writeMarkerText(homeDir, 102L, "{ \"schema\": 1 }")

        val service = service(homeDir)
        val ides = service.stateSnapshot()
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }

    @Test
    fun `truncated JSON marker is skipped and does not abort the scan`(@TempDir homeDir: Path) {
        // A file that LOOKS like JSON (starts with '{') but fails to parse is
        // real corruption. It must be skipped (not crash the scanner), and
        // valid neighbours must remain visible.
        writeMarkerText(homeDir, 101L, """{"schema": 1, "pid":""")
        writeMarker(homeDir, ourPid, "http://localhost:64531/mcp")

        val service = service(homeDir)
        val ides = service.stateSnapshot()
        assertEquals(1, ides.size, "expected only the valid marker, got: $ides")
        assertEquals(ourPid, ides.single().pid)
    }
}
