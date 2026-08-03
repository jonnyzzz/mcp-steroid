/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.runBlocking
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
        assertTrue(result.backendDir.resolve(result.descriptor.bundleDirName).resolve("product-info.json").exists())
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
                archivePath = archivePath,
                resolvedBuild = "262",
                buildIsBaseline = true,
                installedBuild = "262.8665.258",
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
                archivePath = archivePath,
                resolvedBuild = "262",
                buildIsBaseline = true,
                installedBuild = "261.24374.151",
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
                archivePath = archivePath,
                resolvedBuild = "253.28294.334",
                installedBuild = "253.28294.999",
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
        ): BackendDownloadArtifact {
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
            return BackendDownloadArtifact(
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
        private val productCode: String,
        private val archivePath: Path,
        /** What the release feed resolved to — a full build, or a baseline when [buildIsBaseline]. */
        private val resolvedBuild: String = "$productCode-253.1",
        private val buildIsBaseline: Boolean = false,
        /** What the unpacked `product-info.json` reports. */
        private val installedBuild: String = "$productCode-253.1",
    ) : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = resolvedBuild,
                buildIsBaseline = buildIsBaseline,
                url = "https://download.jetbrains.com/idea/${archivePath.fileName}",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact {
            val bundleDir = targetDir.resolve("idea-$productCode-253.1")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(
                bundleDir.resolve("product-info.json"),
                productInfo(productCode),
            )
            Files.writeString(bundleDir.resolve("bin/idea.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/idea.bat"), "@echo off\r\n")
            return BackendDownloadArtifact(
                sourceArchiveSha256 = "sha-$productCode",
                archivePath = archivePath,
            )
        }

        private fun productInfo(productCode: String): String =
            """
            {
              "productCode": "$productCode",
              "buildNumber": "$installedBuild",
              "launch": [
                { "os": "Linux", "launcherPath": "bin/idea.sh" },
                { "os": "macOS", "launcherPath": "bin/idea.sh" },
                { "os": "Windows", "launcherPath": "bin/idea.bat" }
              ]
            }
            """.trimIndent()
    }
}
