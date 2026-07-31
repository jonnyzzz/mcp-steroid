/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackendManagerDownloadValidationTest {

    @Test
    fun `download accepts requested product-info productCode`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val archivePath = fakeArchive(tempDir, "ideaIC-2025.3.3-aarch64.dmg")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(productCode = "IC", archivePath = archivePath),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val result = manager.download(parseBackendId("idea-community-2025.3.3"))

        assertEquals("idea-community-2025.3.3", result.id)
        assertEquals("IC", result.descriptor.productCode)
        assertEquals("sha-IC", result.descriptor.sourceArchiveSha256)
        assertTrue(descriptorPath(result.backendDir).exists())
        val bundleDir = result.backendDir.resolve(result.descriptor.bundleDirName)
        assertTrue(bundleDir.resolve("product-info.json").exists())
        assertFalse(bundleDir.resolve("bin/remote-dev-server").exists())
    }

    @Test
    fun `download accepts matching unqualified resolver and product-info build numbers`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                productCode = "IC",
                buildNumber = "253.1",
                resolvedBuildNumber = "253.1",
                archivePath = fakeArchive(tempDir, "ideaIC-2025.3.3-aarch64.dmg"),
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val result = manager.download(parseBackendId("idea-community-2025.3.3"))

        assertEquals("253.1", result.descriptor.buildNumber)
        assertTrue(result.backendDir.resolve(result.descriptor.bundleDirName).exists())
    }

    @Test
    fun `download rejects bundle without remote development assets and cleans partial install`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-ultimate-2026.2.0.1"
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                product = IdeProduct.IntelliJIdea,
                productCode = "IU",
                buildNumber = "IU-262.8665.337",
                archivePath = fakeArchive(tempDir, "ideaIU-2026.2.0.1-aarch64.dmg"),
                includeRemoteDevelopmentAssets = false,
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        assertTrue(error.message!!.contains("native Remote Development launcher is missing"), error.message)
        assertFalse(homePaths.backendDir(backendId).exists(), "invalid install must not be published")
        assertFalse(homePaths.backendsDir.resolve("$backendId.partial").exists(), "invalid partial install must be removed")
    }

    @Test
    fun `download replaces a corrupt reusable remote development install`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-ultimate-2026.2.0.1"
        val downloader = InstallingDownloader(
            product = IdeProduct.IntelliJIdea,
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            archivePath = fakeArchive(tempDir, "ideaIU-2026.2.0.1-aarch64.dmg"),
            includeRemoteDevelopmentAssets = true,
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = downloader,
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val first = manager.download(parseBackendId(backendId))
        val remoteLauncher = first.backendDir.resolve(first.descriptor.bundleDirName).resolve("bin/remote-dev-server")
        assertTrue(remoteLauncher.exists())
        Files.delete(remoteLauncher)

        var repaired = manager.download(parseBackendId(backendId))

        assertEquals(2, downloader.downloadCount, "the corrupt published install must be downloaded again")
        assertTrue(
            repaired.backendDir.resolve(repaired.descriptor.bundleDirName).resolve("bin/remote-dev-server").exists(),
            "the replacement install must restore the required native launcher",
        )

        val repairedLauncher = repaired.backendDir.resolve(repaired.descriptor.bundleDirName).resolve("bin/remote-dev-server")
        Files.writeString(repairedLauncher, "")
        repaired = manager.download(parseBackendId(backendId))
        assertEquals(3, downloader.downloadCount, "a zero-byte native launcher must not be reused")

        val nonExecutableLauncher = repaired.backendDir.resolve(repaired.descriptor.bundleDirName).resolve("bin/remote-dev-server")
        nonExecutableLauncher.toFile().setExecutable(false)
        repaired = manager.download(parseBackendId(backendId))
        assertEquals(4, downloader.downloadCount, "a non-executable Unix native launcher must not be reused")

        val pluginJar = repaired.backendDir.resolve(repaired.descriptor.bundleDirName)
            .resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
        Files.writeString(pluginJar, "")
        repaired = manager.download(parseBackendId(backendId))
        assertEquals(5, downloader.downloadCount, "a zero-byte Remote Development plugin jar must not be reused")
        assertTrue(Files.size(
            repaired.backendDir.resolve(repaired.descriptor.bundleDirName)
                .resolve("plugins/remote-dev-server/lib/remote-dev-server.jar"),
        ) > 0L)
        assertFalse(homePaths.backendsDir.resolve("$backendId.partial").exists())
    }

    @Test
    fun `download replaces a reusable install whose product-info build differs from the resolved build`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-ultimate-2026.2.0.1"
        val expectedBuild = "IU-262.8665.337"
        val downloader = InstallingDownloader(
            product = IdeProduct.IntelliJIdea,
            productCode = "IU",
            buildNumber = expectedBuild,
            archivePath = fakeArchive(tempDir, "ideaIU-2026.2.0.1-aarch64.dmg"),
            includeRemoteDevelopmentAssets = true,
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = downloader,
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val first = manager.download(parseBackendId(backendId))
        val productInfo = first.backendDir.resolve(first.descriptor.bundleDirName).resolve("product-info.json")
        Files.writeString(productInfo, Files.readString(productInfo).replace(expectedBuild, "IU-263.1"))

        val repaired = manager.download(parseBackendId(backendId))

        assertEquals(2, downloader.downloadCount)
        assertEquals(expectedBuild, repaired.descriptor.buildNumber)
        assertTrue(Files.readString(productInfo).contains(expectedBuild))
    }

    @Test
    fun `download rejects a freshly unpacked IDE whose build differs from the resolved artifact`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-ultimate-2026.2.0.1"
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                product = IdeProduct.IntelliJIdea,
                productCode = "IU",
                buildNumber = "IU-263.1",
                resolvedBuildNumber = "IU-262.8665.337",
                archivePath = fakeArchive(tempDir, "ideaIU-2026.2.0.1-aarch64.dmg"),
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        assertTrue(error.message!!.contains("IU-263.1"), error.message)
        assertTrue(error.message!!.contains("IU-262.8665.337"), error.message)
        assertFalse(homePaths.backendDir(backendId).exists())
        assertFalse(homePaths.backendsDir.resolve("$backendId.partial").exists())
    }

    @Test
    fun `download rejects product-info productCode mismatch and cleans partial install`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-community-2025.3.3"
        val backendDir = homePaths.backendDir(backendId)
        Files.createDirectories(backendDir)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = backendId,
                productKey = "idea-community",
                productCode = "IC",
                version = "2025.3.3",
                buildNumber = "IC-253.1",
                bundleDirName = "stale-missing-bundle",
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-15T00:00:00Z",
            ),
        )
        val archivePath = fakeArchive(tempDir, "idea-2025.3-aarch64.dmg")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(productCode = "IU", archivePath = archivePath),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        val partialDir = homePaths.backendsDir.resolve("$backendId.partial")
        val unpackedBundle = partialDir.resolve("idea-IU-253.1")
        assertFalse(unpackedBundle.exists(), "mismatched bundle must be removed")
        assertFalse(partialDir.exists(), "partial backend dir must be removed on validation failure")
        assertFalse(descriptorPath(backendDir).exists(), "backend.json must be removed on validation failure")
        assertTrue(error.message!!.contains("idea-community (IIC)"), error.message)
        assertTrue(error.message!!.contains("Expected product-info.json productCode 'IC'"), error.message)
        assertTrue(error.message!!.contains("actual 'IU'"), error.message)
        assertTrue(error.message!!.contains("https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg"), error.message)
        assertTrue(error.message!!.contains(archivePath.toString()), error.message)
        assertTrue(error.message!!.contains(unpackedBundle.toString()), error.message)
    }

    @Test
    fun `download accepts a full build under a baseline-only resolution`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // #423: GitHub Community releases resolve to baseline "262" while the downloaded artifact
        // reports "262.8665.258". Comparing those for equality failed every idea-community download.
        val homePaths = HomePaths(tempDir.resolve("home"))
        val archivePath = fakeArchive(tempDir, "idea-2026.2-aarch64.tar.gz")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                productCode = "IC",
                buildNumber = "262.8665.258",
                resolvedBuildNumber = "262",
                buildIsBaseline = true,
                archivePath = archivePath,
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val result = manager.download(parseBackendId("idea-community-2026.2"))

        assertEquals("262.8665.258", result.descriptor.buildNumber)
        assertTrue(result.backendDir.resolve(result.descriptor.bundleDirName).resolve("product-info.json").exists())
    }

    @Test
    fun `download rejects a build from another baseline and cleans partial install`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-community-2026.2"
        val archivePath = fakeArchive(tempDir, "idea-2026.2-aarch64.tar.gz")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                productCode = "IC",
                buildNumber = "261.24374.151",
                resolvedBuildNumber = "262",
                buildIsBaseline = true,
                archivePath = archivePath,
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        assertFalse(homePaths.backendsDir.resolve("$backendId.partial").exists(), "partial dir must be removed")
        assertTrue(error.message!!.contains("platform baseline '262'"), error.message)
        assertTrue(error.message!!.contains("actual '261.24374.151'"), error.message)
    }

    @Test
    fun `download rejects a product-info build that is not the resolved build`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-community-2025.3.3"
        val archivePath = fakeArchive(tempDir, "ideaIC-2025.3.3.tar.gz")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = InstallingDownloader(
                productCode = "IC",
                buildNumber = "253.28294.999",
                resolvedBuildNumber = "253.28294.334",
                archivePath = archivePath,
            ),
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.download(parseBackendId(backendId))
        }

        assertFalse(homePaths.backendsDir.resolve("$backendId.partial").exists(), "partial dir must be removed")
        assertTrue(error.message!!.contains("Expected product-info.json build '253.28294.334'"), error.message)
        assertTrue(error.message!!.contains("actual '253.28294.999'"), error.message)
    }

    @Test
    fun `failed partial extraction is cleaned before retry succeeds`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val backendId = "idea-community-2025.3.3"
        val partialDir = homePaths.backendsDir.resolve("$backendId.partial")
        val finalDir = homePaths.backendDir(backendId)
        val downloader = FailingOnceDownloader(fakeArchive(tempDir, "ideaIC-2025.3.3.tar.gz"))
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = downloader,
            bundledPluginResolver = FixedPluginResolver(pluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"))),
        )

        assertFailsWith<EOFException> {
            manager.download(parseBackendId(backendId))
        }

        assertFalse(finalDir.exists(), "failed extraction must not publish a final backend dir")
        assertFalse(partialDir.exists(), "failed extraction must remove the partial backend dir")

        val result = manager.download(parseBackendId(backendId))

        assertEquals(backendId, result.id)
        assertTrue(finalDir.exists(), "successful retry must publish the final backend dir")
        assertTrue(finalDir.resolve(result.descriptor.bundleDirName).resolve("product-info.json").exists())
        assertFalse(partialDir.exists(), "successful retry must leave no partial backend dir")
        assertEquals(listOf(partialDir, partialDir), downloader.targetDirs)
    }

    @Test
    fun `validation accepts installed product code for each known product`(
        @TempDir tempDir: Path,
    ) {
        val expectedCodes = mapOf(
            IdeProduct.IntelliJIdeaCommunity to "IC",
            IdeProduct.IntelliJIdea to "IU",
            IdeProduct.PyCharmCommunity to "PC",
            IdeProduct.PyCharm to "PY",
            IdeProduct.GoLand to "GO",
            IdeProduct.WebStorm to "WS",
            IdeProduct.Rider to "RD",
            IdeProduct.CLion to "CL",
            IdeProduct.RustRover to "RR",
            IdeProduct.PhpStorm to "PS",
            IdeProduct.RubyMine to "RM",
            // DataGrip is queried as DG but a real install reports DB (feed intellijProductCode) —
            // the same code split as IIU→IU and PCP→PY.
            IdeProduct.DataGrip to "DB",
            IdeProduct.Mps to "MPS",
            IdeProduct.AndroidStudio to "AI",
        )
        assertEquals(expectedCodes.keys, IdeProduct.knownProducts.toSet())

        for ((product, expectedCode) in expectedCodes) {
            assertEquals(expectedCode, product.installedProductCode)
            val bundleDir = tempDir.resolve(product.id)
            Files.createDirectories(bundleDir)

            validateInstalledProductCode(
                product = product,
                actualProductCode = expectedCode,
                downloadedUrl = "https://example.invalid/${product.id}.tar.gz",
                archivePath = null,
                bundleDir = bundleDir,
                descriptorPath = tempDir.resolve("${product.id}.json"),
            )

            assertTrue(bundleDir.exists(), "matching validation must keep ${product.id} bundle")
        }
    }

    @Test
    fun `custom product installed product code falls back to products API code`() {
        val custom = IdeProduct.Custom(
            id = "rubymine",
            displayName = "RubyMine",
            code = "RM",
            launcherExecutable = "rubymine",
            licenseTier = com.jonnyzzz.mcpSteroid.ideDownloader.LicenseTier.FreeForNonCommercial,
        )

        assertEquals("RM", custom.installedProductCode)
    }

    private fun fakeArchive(tempDir: Path, fileName: String): Path {
        val archive = tempDir.resolve("downloads").resolve(fileName)
        Files.createDirectories(archive.parent)
        Files.writeString(archive, "fake archive")
        return archive
    }

    private fun pluginZipFixture(zip: Path): Path {
        Files.createDirectories(zip.parent)
        ZipArchiveOutputStream(Files.newOutputStream(zip)).use { out ->
            val bytes = "plugin".toByteArray(Charsets.UTF_8)
            val entry = ZipArchiveEntry("mcp-steroid/lib/plugin.txt").apply {
                size = bytes.size.toLong()
                unixMode = 0b110_100_100
            }
            out.putArchiveEntry(entry)
            out.write(bytes)
            out.closeArchiveEntry()
        }
        return zip
    }

    private class FixedPluginResolver(private val zip: Path) : BundledPluginResolver {
        override fun resolveBundledPluginZip(): Path = zip
    }

    private class FailingOnceDownloader(
        private val archivePath: Path,
    ) : ManagedBackendDownloader {
        val targetDirs = mutableListOf<Path>()
        private var attempts = 0

        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "https://download.jetbrains.com/idea/${archivePath.fileName}",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = withContext(Dispatchers.IO) {
            targetDirs.add(targetDir)
            attempts++
            val bundleDir = targetDir.resolve("idea-IC-253.1")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(bundleDir.resolve("partial-entry.txt"), "created before EOF")
            if (attempts == 1) {
                throw EOFException("truncated fake archive")
            }
            Files.deleteIfExists(bundleDir.resolve("partial-entry.txt"))
            Files.writeString(bundleDir.resolve("product-info.json"), productInfo())
            Files.writeString(bundleDir.resolve("bin/idea.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/idea.bat"), "@echo off\r\n")
            BackendDownloadArtifact(
                sourceArchiveSha256 = "sha-retry",
                archivePath = archivePath,
            )
        }

        private fun productInfo(): String =
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
            """.trimIndent()
    }

    private class InstallingDownloader(
        private val product: IdeProduct = IdeProduct.IntelliJIdeaCommunity,
        private val productCode: String,
        /** What the unpacked `product-info.json` reports (also names the bundle dir). */
        private val buildNumber: String = "$productCode-253.1",
        /** What the release feed resolved to — a full build, or a baseline when [buildIsBaseline]. */
        private val resolvedBuildNumber: String = buildNumber,
        private val buildIsBaseline: Boolean = false,
        private val archivePath: Path,
        private val includeRemoteDevelopmentAssets: Boolean = false,
    ) : ManagedBackendDownloader {
        var downloadCount: Int = 0
            private set

        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = product,
                version = id.version ?: "2025.3.3",
                build = resolvedBuildNumber,
                buildIsBaseline = buildIsBaseline,
                url = "https://download.jetbrains.com/idea/${archivePath.fileName}",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = withContext(Dispatchers.IO) {
            downloadCount++
            val bundleDir = targetDir.resolve("idea-$buildNumber")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(
                bundleDir.resolve("product-info.json"),
                productInfo(productCode, buildNumber),
            )
            Files.writeString(bundleDir.resolve("bin/idea.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/idea.bat"), "@echo off\r\n")
            if (includeRemoteDevelopmentAssets) writeRemoteDevelopmentAssets(bundleDir)
            BackendDownloadArtifact(
                sourceArchiveSha256 = "sha-$productCode",
                archivePath = archivePath,
            )
        }

        private fun productInfo(productCode: String, buildNumber: String): String =
            """
            {
              "productCode": "$productCode",
              "buildNumber": "$buildNumber",
              "launch": [
                { "os": "Linux", "launcherPath": "bin/idea.sh" },
                { "os": "macOS", "launcherPath": "bin/idea.sh" },
                { "os": "Windows", "launcherPath": "bin/idea.bat" }
              ]
            }
            """.trimIndent()
    }

    companion object {
        private fun writeRemoteDevelopmentAssets(bundleDir: Path) {
            Files.writeString(bundleDir.resolve("bin/remote-dev-server"), "#!/usr/bin/env sh\n")
                .toFile()
                .setExecutable(true)
            Files.writeString(bundleDir.resolve("bin/remote-dev-server.exe"), "remote development launcher")
            val pluginJar = bundleDir.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
            Files.createDirectories(pluginJar.parent)
            Files.writeString(pluginJar, "remote development plugin")
        }
    }
}
