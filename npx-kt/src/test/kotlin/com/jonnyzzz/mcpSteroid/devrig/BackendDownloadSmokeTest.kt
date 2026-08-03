/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The real thing: download an IDE off its live feed, unpack it, and let [BackendManager] validate the
 * installed `product-info.json`. This is the only test that proves the #423 fix on a genuine artifact
 * — the bug was that the build comparison rejected the install AFTER the whole transfer, so nothing
 * short of a real download reproduces it.
 *
 * Covers the two products served by a baseline-only feed, which is exactly where the defect lived:
 * `idea-community` (GitHub releases) and `android-studio` (Google's preview page).
 *
 * Opt-in — `./gradlew :npx-kt:liveDownloadSmokeTest`. Each case pulls roughly 1-2 GB into the
 * JUnit temp dir and deletes it again, so it is deliberately out of every default run.
 *
 * Not on Windows: there the IDE ships as an NSIS `.exe`, whose unpack path needs the bundled
 * `7z.exe` from a real installDist tree. That exclusion lives on the `liveDownloadSmokeTest`
 * task (`enabled = !isWindows` in `npx-kt/build.gradle.kts`) — the task level is the only
 * acceptable skip per the root CLAUDE.md; the test methods themselves are unconditional.
 */
@Tag("live-download")
class BackendDownloadSmokeTest {

    @BeforeEach
    fun overrideDevrigRoot(@TempDir rootDir: Path) {
        // DefaultManagedBackendDownloader asks DevrigRoot for the bundled 7-Zip, which only resolves
        // inside an installDist tree. A stand-in root is enough: 7z.exe is a Windows-only code path.
        Files.createDirectories(rootDir.resolve("devrig/lib"))
        Files.writeString(rootDir.resolve("devrig/ij-plugin.zip"), "stand-in")
        DevrigRootTestSupport.overrideCodeSource(rootDir.resolve("devrig/lib/devrig.jar"))
    }

    @AfterEach
    fun restoreDevrigRoot() = DevrigRootTestSupport.reset()

    @Test
    fun `idea-community downloads, unpacks and validates`(@TempDir tempDir: Path) =
        smokeDownload(IdeProduct.IntelliJIdeaCommunity, tempDir)

    @Test
    fun `android-studio downloads, unpacks and validates`(@TempDir tempDir: Path) =
        smokeDownload(IdeProduct.AndroidStudio, tempDir)

    private fun smokeDownload(product: IdeProduct, tempDir: Path) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = DefaultManagedBackendDownloader(archiveDownloadDir = homePaths.downloadsDir),
            bundledPluginResolver = FixtureBundledPluginResolver(tempDir.resolve("dist/ij-plugin.zip")),
            pluginBuildRange = PluginBuildRange(sinceBaseline = 261, untilBaseline = null),
        )

        val result = manager.download(BackendId(product, version = null))

        assertEquals(product.installedProductCode, result.descriptor.productCode)
        val installedBuild = result.descriptor.buildNumber
        assertTrue(installedBuild != null && installedBuild.isNotBlank(), "${product.id}: no build in product-info.json")
        // Assert through the descriptor's own launcher path — a macOS .dmg unpacks to an .app bundle
        // whose product-info.json sits under Contents/Resources, a .tar.gz puts it at the root.
        val bundleDir = result.backendDir.resolve(result.descriptor.bundleDirName)
        assertTrue(
            bundleDir.resolve(result.descriptor.launcherPath).exists(),
            "${product.id}: the validated bundle must still be on disk with its launcher",
        )
        // The install survived validateInstalledBuildNumber; assert the baseline relation it checked,
        // so a feed that starts publishing full builds shows up here rather than as a silent no-op.
        val resolution = resolveBackendArchive(product = product)
        println(
            "[live-download] ${product.id} ${result.descriptor.version}: resolved build ${resolution.build} " +
                "(baseline=${resolution.buildIsBaseline}) -> installed $installedBuild",
        )
        assertTrue(
            ideBuildMatches(installedBuild, resolution.build, resolution.buildIsBaseline),
            "${product.id}: installed $installedBuild does not match resolved ${resolution.build} " +
                "(baseline=${resolution.buildIsBaseline})",
        )
    }
}
