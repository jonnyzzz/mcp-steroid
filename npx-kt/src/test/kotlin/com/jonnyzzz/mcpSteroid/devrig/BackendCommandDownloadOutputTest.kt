/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendCommandDownloadOutputTest {
    @Test
    fun `IU 262 download reports effective native remote development launcher`(
        @TempDir tempDir: Path,
    ) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val id = "idea-ultimate-2026.2.0.1"
        val hostOs = resolveHostOs()
        val bundleDirName = if (hostOs == HostOs.MAC) "IntelliJ IDEA.app" else "idea-IU-262"
        val bundleDir = homePaths.backendDir(id).resolve(bundleDirName)
        val ideHome = if (hostOs == HostOs.MAC) bundleDir.resolve("Contents") else bundleDir
        val remoteLauncherName = if (hostOs == HostOs.WINDOWS) "remote-dev-server.exe" else "remote-dev-server"
        val remoteLauncher = ideHome.resolve("bin").resolve(remoteLauncherName)
        Files.createDirectories(remoteLauncher.parent)
        Files.writeString(remoteLauncher, "remote development launcher")
        if (hostOs != HostOs.WINDOWS) {
            assertTrue(remoteLauncher.toFile().setExecutable(true), "Failed to mark $remoteLauncher executable")
        }
        val remoteDevelopmentPlugin = ideHome.resolve("plugins/remote-dev-server")
        Files.createDirectories(remoteDevelopmentPlugin.resolve("lib"))
        Files.writeString(remoteDevelopmentPlugin.resolve("lib/remote-dev-server.jar"), "plugin jar")

        val productLauncherPath = if (hostOs == HostOs.WINDOWS) "bin/idea64.exe" else "bin/idea.sh"
        val result = DownloadResult(
            id = id,
            descriptor = BackendDescriptor(
                id = id,
                productKey = "idea-ultimate",
                productCode = "IU",
                version = "2026.2.0.1",
                buildNumber = "IU-262.8665.337",
                bundleDirName = bundleDirName,
                launcherPath = productLauncherPath,
                downloadedAt = "2026-07-31T00:00:00Z",
            ),
            backendDir = homePaths.backendDir(id),
            vmOptionsPath = homePaths.backendDir(id).resolve("$bundleDirName.vmoptions"),
        )
        val output = ByteArrayOutputStream()

        val exitCode = runBackendDownloadCommand(
            out = PrintStream(output, true, Charsets.UTF_8),
            homePaths = homePaths,
            command = DevrigCommand.DevrigCommandBackendDownload(id = "idea-ultimate"),
            backendService = DownloadOnlyBackendService(result),
        )

        val stdout = output.toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(0, exitCode)
        assertTrue(stdout.contains("launcher: $remoteLauncher\n"), stdout)
        assertFalse(stdout.contains(bundleDir.resolve(productLauncherPath).toString()), stdout)
    }

    private class DownloadOnlyBackendService(
        private val result: DownloadResult,
    ) : ManagedBackendService {
        override suspend fun download(id: BackendId): DownloadResult = result

        override suspend fun start(id: BackendId): StartResult = error("start is not expected")

        override suspend fun stop(id: BackendId): StopResult = error("stop is not expected")
    }
}
