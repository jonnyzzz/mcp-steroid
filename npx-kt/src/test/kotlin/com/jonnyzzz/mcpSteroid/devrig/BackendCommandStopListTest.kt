/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BackendCommandStopListTest {

    @Test
    fun `text lists only currently running managed backends`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "12345\n")
        Files.writeString(homePaths.pidFile("pycharm-community-2025.3.3"), "54321\n")

        val rows = collectRunningBackendListRows(homePaths, FakeProcessInspector(alivePids = setOf(12345L)))
        val text = renderStopText(rows)

        assertTrue(text.startsWith("Running backends:"), text)
        assertTrue(text.contains("[1] idea-community-2025.3.3"), text)
        assertTrue(text.contains("IntelliJ IDEA Community 2025.3.3"), text)
        assertTrue(text.contains("pid 12345"), text)
        assertTrue(!text.contains("pycharm-community"), text)
        assertTrue(text.contains("Run:  devrig backend stop <id>"), text)
    }

    @Test
    fun `empty text says nothing is running`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "12345\n")

        val text = renderStopText(collectRunningBackendListRows(homePaths, FakeProcessInspector()))

        assertEquals("No managed backends are currently running.\n", text)
    }

    @Test
    fun `json lists running schema`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "12345\n")

        val root = renderStopJson(collectRunningBackendListRows(homePaths, FakeProcessInspector(alivePids = setOf(12345L))))

        assertEquals(setOf("tool", "running"), root.keys)
        val running = root["running"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("id", "pid", "displayName", "logPath"), running.keys)
        assertEquals("idea-community-2025.3.3", running["id"]!!.jsonPrimitive.content)
        assertEquals(12345L, running["pid"]!!.jsonPrimitive.long)
        assertEquals("IntelliJ IDEA Community 2025.3.3", running["displayName"]!!.jsonPrimitive.content)
        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("logs/managed.log").toString(), running["logPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reused pid with a different process start time is omitted`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        val id = "idea-community-2025.3.3"
        Files.writeString(
            homePaths.pidFile(id),
            Json.encodeToString(
                ManagedBackendProcessState(pid = 12345L, startInstant = "2026-07-31T08:00:00Z"),
            ),
        )
        val processInspector = FakeProcessInspector(
            alivePids = setOf(12345L),
            snapshots = mapOf(
                12345L to ProcessSnapshot(
                    pid = 12345L,
                    command = "/usr/bin/java",
                    startInstant = Instant.parse("2026-07-31T09:00:00Z"),
                ),
            ),
        )

        assertTrue(collectRunningBackendListRows(homePaths, processInspector).isEmpty())
    }

    private fun renderStopText(rows: List<RunningBackendListRow>): String {
        val buf = ByteArrayOutputStream()
        renderBackendStopListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun renderStopJson(rows: List<RunningBackendListRow>) = Json.parseToJsonElement(
        ByteArrayOutputStream().also { buf ->
            renderBackendStopListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        }.toString(Charsets.UTF_8),
    ).jsonObject
}
