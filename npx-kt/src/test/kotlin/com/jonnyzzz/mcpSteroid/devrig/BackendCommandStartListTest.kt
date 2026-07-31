/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class BackendCommandStartListTest {

    @Test
    fun `text lists installed backends with running and installed states`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        backendFixture(homePaths, "idea-community-2025.3.3", productKey = "idea-community", productCode = "IC", version = "2025.3.3")
        backendFixture(homePaths, "pycharm-community-2025.3.3", productKey = "pycharm-community", productCode = "PC", version = "2025.3.3")
        backendFixture(homePaths, "android-studio-2025.3.4.7", productKey = "android-studio", productCode = "AI", version = "2025.3.4.7")
        Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "12345\n")
        Files.writeString(homePaths.pidFile("pycharm-community-2025.3.3"), "54321\n")

        val rows = collectInstalledBackendListRows(homePaths, ownedLegacyProcessInspector(homePaths, "idea-community-2025.3.3"))
        val text = renderStartText(rows)

        assertTrue(text.startsWith("Installed backends:"), text)
        assertTrue(text.contains("[1] idea-community-2025.3.3"), text)
        assertTrue(text.contains("IntelliJ IDEA Community 2025.3.3"), text)
        assertTrue(text.contains("running (pid 12345)"), text)
        assertTrue(text.contains("PyCharm Community 2025.3.3"), text)
        assertTrue(text.contains("installed"), text)
        assertTrue(text.contains("Android Studio 2025.3.4.7"), text)
        assertTrue(text.contains("Run:  devrig backend start <id>"), text)

        val rowLines = text.lines().filter { it.trimStart().startsWith("[") }
        val stateColumns = listOf(
            rowLines.single { it.contains("idea-community") }.indexOf("running"),
            rowLines.single { it.contains("pycharm-community") }.indexOf("installed"),
            rowLines.single { it.contains("android-studio") }.indexOf("installed"),
        ).toSet()
        assertEquals(1, stateColumns.size, "state column must align in:\n$text")
    }

    @Test
    fun `empty installed dir shows available downloads in text`(@TempDir tempDir: Path) {
        val text = runStartListText(
            homePaths = HomePaths(tempDir),
            availableDownloads = sampleAvailableDownloads(),
        )

        assertTrue(text.startsWith("No managed backends are installed yet."), text)
        assertTrue(text.contains("No managed backends are installed yet."), text)
        assertTrue(text.contains("Available IDEs (defaults to latest stable):"), text)
        assertTrue(text.contains("[1] idea-community"), text)
        assertTrue(text.contains("2025.3"), text)
        assertTrue(!text.contains("2025-12-08"), text)
        assertTrue(text.contains("  *  Includes the Community feature set; free in Community mode, a paid license unlocks the full edition."), text)
        assertTrue(!text.contains("Free for non-commercial use; JetBrains license required for commercial use."), text)
        assertTrue(text.contains("Run:  devrig backend download <id> [--version <v>]"), text)
        assertTrue(text.contains("Then: devrig backend start <id>"), text)
    }

    @Test
    fun `json lists installed schema with null pid for stopped entries`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        backendFixture(homePaths, "idea-community-2025.3.3", productKey = "idea-community", productCode = "IC", version = "2025.3.3")
        backendFixture(homePaths, "pycharm-community-2025.3.3", productKey = "pycharm-community", productCode = "PC", version = "2025.3.3")
        Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "12345\n")
        Files.writeString(homePaths.pidFile("pycharm-community-2025.3.3"), "54321\n")

        val root = renderStartJson(
            collectInstalledBackendListRows(homePaths, ownedLegacyProcessInspector(homePaths, "idea-community-2025.3.3")),
        )

        assertEquals(setOf("tool", "installed"), root.keys)
        val installed = root["installed"]!!.jsonArray.map { it.jsonObject }
        val running = installed.single { it["id"]!!.jsonPrimitive.content == "idea-community-2025.3.3" }
        assertEquals(setOf("id", "productKey", "version", "displayName", "state", "pid", "installPath", "cachePath"), running.keys)
        assertEquals("idea-community", running["productKey"]!!.jsonPrimitive.content)
        assertEquals("2025.3.3", running["version"]!!.jsonPrimitive.content)
        assertEquals("IntelliJ IDEA Community 2025.3.3", running["displayName"]!!.jsonPrimitive.content)
        assertEquals("running", running["state"]!!.jsonPrimitive.content)
        assertEquals(12345L, running["pid"]!!.jsonPrimitive.long)
        assertEquals(homePaths.backendDir("idea-community-2025.3.3").toString(), running["installPath"]!!.jsonPrimitive.content)
        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").toString(), running["cachePath"]!!.jsonPrimitive.content)

        val stopped = installed.single { it["id"]!!.jsonPrimitive.content == "pycharm-community-2025.3.3" }
        assertEquals(setOf("id", "productKey", "version", "displayName", "state", "pid", "installPath", "cachePath"), stopped.keys)
        assertEquals("installed", stopped["state"]!!.jsonPrimitive.content)
        assertNull(stopped["pid"]!!.jsonPrimitive.contentOrNull)
    }

    @Test
    fun `reused pid with a different process start time is not listed as running`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        val id = "idea-community-2025.3.3"
        backendFixture(homePaths, id, productKey = "idea-community", productCode = "IC", version = "2025.3.3")
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

        val row = collectInstalledBackendListRows(homePaths, processInspector).single()

        assertEquals(LocalBackendState.INSTALLED, row.state)
        assertNull(row.pid)
    }

    @Test
    fun `legacy pid-only state rejects a launcher in a non-executable shell argument`(@TempDir tempDir: Path) {
        val homePaths = HomePaths(tempDir)
        homePaths.mkdirsAll()
        val id = "idea-community-2025.3.3"
        backendFixture(homePaths, id, productKey = "idea-community", productCode = "IC", version = "2025.3.3")
        Files.writeString(homePaths.pidFile(id), "12345\n")
        val launcher = homePaths.backendDir(id).resolve("bundle-$id/bin/idea.sh").toString()
        val processInspector = FakeProcessInspector(
            alivePids = setOf(12345L),
            snapshots = mapOf(
                12345L to ProcessSnapshot(
                    pid = 12345L,
                    command = "/bin/bash",
                    arguments = listOf("/tmp/unrelated-script", launcher),
                ),
            ),
        )

        val row = collectInstalledBackendListRows(homePaths, processInspector).single()

        assertEquals(LocalBackendState.INSTALLED, row.state)
        assertNull(row.pid)
    }

    @Test
    fun `empty installed dir shows available downloads in json`(@TempDir tempDir: Path) {
        val root = runStartListJson(
            homePaths = HomePaths(tempDir),
            availableDownloads = sampleAvailableDownloads(),
        )

        assertEquals(setOf("tool", "installed", "available", "hint"), root.keys)
        assertTrue(root["installed"]!!.jsonArray.isEmpty())
        assertEquals("no managed backends installed; run 'devrig backend download <id>' first", root["hint"]!!.jsonPrimitive.content)
        val available = root["available"]!!.jsonArray.map { it.jsonObject }
        val idea = available.single { it["id"]!!.jsonPrimitive.content == "idea-community" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate"), idea.keys)
        assertEquals("2025.3", idea["version"]!!.jsonPrimitive.content)
        assertEquals("2025-12-08", idea["releaseDate"]!!.jsonPrimitive.content)
        assertEquals("", idea["licenseSymbol"]!!.jsonPrimitive.content)
        assertEquals("", idea["licenseNote"]!!.jsonPrimitive.content)
        val ultimate = available.single { it["id"]!!.jsonPrimitive.content == "idea-ultimate" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate"), ultimate.keys)
        assertEquals("*", ultimate["licenseSymbol"]!!.jsonPrimitive.content)
        assertEquals("Includes the Community feature set; free in Community mode, a paid license unlocks the full edition.", ultimate["licenseNote"]!!.jsonPrimitive.content)
    }

    private fun renderStartText(rows: List<InstalledBackendListRow>): String {
        val buf = ByteArrayOutputStream()
        renderBackendStartListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderStartJson(rows: List<InstalledBackendListRow>) = Json.parseToJsonElement(
        ByteArrayOutputStream().also { buf ->
            renderBackendStartListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        }.toString(Charsets.UTF_8),
    ).jsonObject

    private fun runStartListText(
        homePaths: HomePaths,
        availableDownloads: List<AvailableBackendDownload>,
    ): String {
        val buf = ByteArrayOutputStream()
        runBackendStartListCommand(
            out = PrintStream(buf, true, Charsets.UTF_8),
            homePaths = homePaths,
            json = false,
            processInspector = FakeProcessInspector(),
            availableDownloads = { availableDownloads },
        )
        return buf.toString(Charsets.UTF_8)
    }

    private fun runStartListJson(
        homePaths: HomePaths,
        availableDownloads: List<AvailableBackendDownload>,
    ) = Json.parseToJsonElement(
        ByteArrayOutputStream().also { buf ->
            runBackendStartListCommand(
                out = PrintStream(buf, true, Charsets.UTF_8),
                homePaths = homePaths,
                json = true,
                processInspector = FakeProcessInspector(),
                availableDownloads = { availableDownloads },
            )
        }.toString(Charsets.UTF_8),
    ).jsonObject

    private fun sampleAvailableDownloads(): List<AvailableBackendDownload> = listOf(
        AvailableBackendDownload(
            product = IdeProduct.IntelliJIdeaCommunity,
            version = "2025.3",
            releaseDate = "2025-12-08",
        ),
        AvailableBackendDownload(
            product = IdeProduct.IntelliJIdea,
            version = "2026.1.1",
            releaseDate = "2026-04-23",
        ),
    )

    private fun ownedLegacyProcessInspector(homePaths: HomePaths, id: String): FakeProcessInspector =
        FakeProcessInspector(
            alivePids = setOf(12345L),
            snapshots = mapOf(
                12345L to ProcessSnapshot(
                    pid = 12345L,
                    command = homePaths.backendDir(id).resolve("bundle-$id/bin/idea.sh").toString(),
                ),
            ),
        )
}
