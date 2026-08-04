/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDeployTest {

    @Test
    fun `deploy copies bundled plugin tree and clears stale files`(
        @TempDir tempDir: Path,
    ) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val source = bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), version = "one")
        val stale = homePaths.cacheDir("idea-community-2025.3.3")
            .resolve("plugins/mcp-steroid/stale/old.txt")
        Files.createDirectories(stale.parent)
        Files.writeString(stale, "old")

        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(source),
        )

        val deployed = manager.deployMcpSteroidPlugin("idea-community-2025.3.3")

        assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("plugins/mcp-steroid"), deployed)
        assertEquals("one", Files.readString(deployed.resolve("lib/plugin.txt")))
        assertTrue(deployed.resolve("kotlinc/bin/kotlinc").toFile().canExecute(),
            "executable bit from bundled plugin fixture must survive deployment")
        assertFalse(stale.exists(), "deploy must clear leftover plugin pieces before copying")
    }

    @Test
    fun `download redeploys bundled plugin even when backend archive is already installed`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val resolver = MutablePluginResolver(bundledPluginZipFixture(tempDir.resolve("dist-v1/ij-plugin.zip"), version = "one"))
        val downloader = InstallingDownloader()
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = downloader,
            bundledPluginResolver = resolver,
        )

        manager.download(parseBackendId("idea-community-2025.3.3"))
        val deployedFile = homePaths.cacheDir("idea-community-2025.3.3")
            .resolve("plugins/mcp-steroid/lib/plugin.txt")
        assertEquals("one", Files.readString(deployedFile))
        assertEquals(1, downloader.unpackCount)

        resolver.zip = bundledPluginZipFixture(tempDir.resolve("dist-v2/ij-plugin.zip"), version = "two")
        val stale = homePaths.cacheDir("idea-community-2025.3.3")
            .resolve("plugins/mcp-steroid/stale.txt")
        Files.writeString(stale, "stale")

        manager.download(parseBackendId("idea-community-2025.3.3"))

        assertEquals("two", Files.readString(deployedFile))
        assertFalse(stale.exists(), "re-download must remove stale plugin files before redeploy")
        assertEquals(1, downloader.unpackCount, "existing IDE archive should be reused on the second download")
    }

    private class MutablePluginResolver(var zip: Path) : BundledPluginResolver {
        override fun resolveBundledPluginZip(): Path = zip
    }

    private class InstallingDownloader : ManagedBackendDownloader {
        var unpackCount: Int = 0

        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "file:///unused",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact {
            unpackCount++
            val bundleDir = targetDir.resolve("idea-IC-253.1")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(
                bundleDir.resolve("product-info.json"),
                """
                {
                  "productCode": "IC",
                  "buildNumber": "IC-253.1",
                  "launch": [
                    { "os": "Linux", "launcherPath": "bin/idea.sh" },
                    { "os": "macOS", "launcherPath": "bin/idea.sh" },
                    { "os": "Windows", "launcherPath": "bin/idea.bat" }
                  ]
                }
                """.trimIndent(),
            )
            Files.writeString(bundleDir.resolve("bin/idea.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/idea.bat"), "@echo off\r\n")
            return BackendDownloadArtifact(sourceArchiveSha256 = "sha-$unpackCount")
        }
    }

    private object StaticDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "file:///unused",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by deploy-only tests")
    }
}
