/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeArchiveResolution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveArchiveFromProductsApiPayload
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `devrig backend download` for EVERY supported IDE, end to end over recorded feed payloads:
 * feed dispatch -> resolution -> [BackendManager.download] -> unpack -> product-info validation ->
 * descriptor.
 *
 * The regression it guards is #423/#429. devrig reads three feeds and only
 * `data.services.jetbrains.com` publishes a full build number; the GitHub Community releases and
 * the Android Studio preview page publish just the platform baseline (`262`) while the artifact
 * itself reports `262.8665.258`. Comparing those for equality rejected the install AFTER the full
 * multi-hundred-MB transfer. The fix landed with an idea-community test only, so this walks every
 * catalog product — Android Studio included, whose canary resolver derives the build the same way.
 *
 * Live-feed coverage of the same contract is [AllIdeProductsLiveFeedTest] (tag `live-network`) and
 * real downloads are [BackendDownloadSmokeTest] (tag `live-download`).
 */
class AllIdeProductsDownloadTest {

    /**
     * One row per supported product, mirroring what its feed served in 2026-08. [installedBuild] is
     * the `product-info.json` build of that very artifact — the value the resolution is validated
     * against, and the whole point of the baseline comparison. `product-info.json` states the build
     * without the product-code prefix that marker files carry.
     *
     * The two baseline-fed rows are transcribed from real downloads (see [BackendDownloadSmokeTest]).
     */
    private data class ProductFeedCase(
        val product: IdeProduct,
        val feed: BackendDownloadFeed,
        val version: String,
        val resolvedBuild: String,
        val installedBuild: String,
        val fileName: String,
    ) {
        val expectedBaseline: Boolean get() = feed.publishesBaselineOnly
    }

    private val cases = listOf(
        ProductFeedCase(
            IdeProduct.IntelliJIdeaCommunity, BackendDownloadFeed.GITHUB_COMMUNITY,
            version = "2026.2", resolvedBuild = "262", installedBuild = "262.8665.258",
            fileName = "idea-2026.2-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.PyCharmCommunity, BackendDownloadFeed.GITHUB_COMMUNITY,
            version = "2026.2.0.1", resolvedBuild = "262", installedBuild = "262.8665.369",
            fileName = "pycharm-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.AndroidStudio, BackendDownloadFeed.ANDROID_STUDIO_PREVIEW,
            version = "2026.1.4.3", resolvedBuild = "261",
            // Android Studio appends its own two segments to the platform build.
            installedBuild = "261.26222.65.2614.15978069",
            fileName = "android-studio-quail4-canary3-mac_arm.dmg",
        ),
        ProductFeedCase(
            IdeProduct.IntelliJIdea, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.337", installedBuild = "262.8665.337",
            fileName = "idea-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.PyCharm, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.369", installedBuild = "262.8665.369",
            fileName = "pycharm-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.GoLand, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.336", installedBuild = "262.8665.336",
            fileName = "goland-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.WebStorm, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.341", installedBuild = "262.8665.341",
            fileName = "WebStorm-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.Rider, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.385", installedBuild = "262.8665.385",
            fileName = "JetBrains.Rider-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.CLion, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.321", installedBuild = "262.8665.321",
            fileName = "CLion-2026.2.0.1-aarch64.dmg",
        ),
        // The #430 additions, mirroring intellij-downloader's recorded fixtures (products-*.json).
        ProductFeedCase(
            IdeProduct.RustRover, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2", resolvedBuild = "262.8665.323", installedBuild = "262.8665.323",
            fileName = "RustRover-2026.2-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.PhpStorm, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.0.1", resolvedBuild = "262.8665.325", installedBuild = "262.8665.325",
            fileName = "PhpStorm-2026.2.0.1-aarch64.dmg",
        ),
        ProductFeedCase(
            IdeProduct.RubyMine, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2", resolvedBuild = "262.8665.308", installedBuild = "262.8665.308",
            fileName = "RubyMine-2026.2-aarch64.dmg",
        ),
        // DataGrip is queried as DG but a real install reports productCode DB — installedProductCode
        // carries the split, and the download validation must accept it.
        ProductFeedCase(
            IdeProduct.DataGrip, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.2.2", resolvedBuild = "262.9437.70", installedBuild = "262.9437.70",
            fileName = "datagrip-2026.2.2-aarch64.dmg",
        ),
        // MPS lags the platform by one baseline (261) — still inside the bundled plugin's range.
        ProductFeedCase(
            IdeProduct.Mps, BackendDownloadFeed.JETBRAINS_PRODUCTS_API,
            version = "2026.1", resolvedBuild = "261.25134.779", installedBuild = "261.25134.779",
            fileName = "MPS-2026.1-macos-aarch64.dmg",
        ),
    )

    @Test
    fun `every supported product is covered by a feed case`() {
        assertEquals(
            IdeProduct.knownProducts.toSet(),
            cases.map { it.product }.toSet(),
            "a new IdeProduct must get a download case here — that is how a new feed gets baseline coverage",
        )
        for (case in cases) {
            assertEquals(case.feed, backendDownloadFeed(case.product), "feed dispatch for ${case.product.id}")
        }
    }

    @Test
    fun `every product resolves from its feed with the right build shape`() {
        for (case in cases) {
            val resolution = case.resolve()

            assertEquals(case.product, resolution.product, case.product.id)
            assertEquals(case.version, resolution.version, case.product.id)
            assertEquals(case.resolvedBuild, resolution.build, case.product.id)
            assertTrue(resolution.url.endsWith(case.fileName), "${case.product.id}: ${resolution.url}")
            // The feed's build shape and the flag that describes it must agree — a feed that stops at
            // the baseline MUST say so, or the download is rejected on arrival (#423).
            assertEquals(case.expectedBaseline, resolution.buildIsBaseline, "buildIsBaseline for ${case.product.id}")
            assertEquals(case.expectedBaseline, isPlatformBaselineOnly(resolution.build), "build shape for ${case.product.id}")
            // Every product the CLI offers must be loadable by the bundled plugin (plugin.xml since-build 261).
            assertTrue(
                bundledPluginRange.accepts(resolution.build),
                "${case.product.id} build ${resolution.build} is outside ${bundledPluginRange.describe()}",
            )
        }
    }

    @Test
    fun `download accepts the build the real artifact reports, for every product`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        for (case in cases) {
            val home = tempDir.resolve("ok/${case.product.id}")
            val result = case.newManager(home).download(BackendId(case.product, case.version))

            assertEquals("${case.product.id}-${case.version}", result.id)
            assertEquals(case.product.installedProductCode, result.descriptor.productCode, case.product.id)
            assertEquals(case.installedBuild, result.descriptor.buildNumber, case.product.id)
            assertTrue(
                result.backendDir.resolve(result.descriptor.bundleDirName).resolve("product-info.json").exists(),
                "${case.product.id}: installed bundle must survive validation",
            )
        }
    }

    @Test
    fun `download still rejects an artifact from another baseline, for every product`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        for (case in cases) {
            // One baseline up: the shape a swapped or stale artifact has. A baseline resolution must
            // not degrade into "any build will do".
            val foreignBuild = case.installedBuild.replaceFirst(
                Regex("""(\D*)(\d{3})"""),
                "$1${ideBuildBaseline(case.resolvedBuild)!! + 1}",
            )
            val home = tempDir.resolve("reject/${case.product.id}")

            val error = assertFailsWith<ManagedBackendValidationException>(case.product.id) {
                case.newManager(home, installedBuild = foreignBuild).download(BackendId(case.product, case.version))
            }

            assertTrue(error.message!!.contains(foreignBuild), "${case.product.id}: ${error.message}")
            assertFalse(
                HomePaths(home).backendsDir.resolve("${case.product.id}-${case.version}.partial").exists(),
                "${case.product.id}: partial install must be cleaned",
            )
        }
    }

    /** Mirrors `ij-plugin/build.gradle.kts` — `sinceBuild = "261"`, no upper bound. */
    private val bundledPluginRange = PluginBuildRange(sinceBaseline = 261, untilBaseline = null)

    /** Resolves through the production per-feed resolver, from a recorded payload of that feed. */
    private fun ProductFeedCase.resolve(): IdeArchiveResolution = when (feed) {
        BackendDownloadFeed.ANDROID_STUDIO_PREVIEW -> resolveAndroidStudioCanaryArchiveFromHtml(
            androidStudioPreviewHtml(version, fileName), HostOs.MAC, HostArchitecture.ARM64,
        )

        BackendDownloadFeed.GITHUB_COMMUNITY -> resolveGithubCommunityArchiveFromReleasesJson(
            githubCommunityReleasesJson(product, version, fileName), product, HostOs.MAC, HostArchitecture.ARM64,
        )

        BackendDownloadFeed.JETBRAINS_PRODUCTS_API -> resolveArchiveFromProductsApiPayload(
            product = product,
            channel = IdeChannel.STABLE,
            os = HostOs.MAC,
            architecture = HostArchitecture.ARM64,
            productsApiUrl = "fixture://products?code=${product.code}",
            payload = productsApiPayload(product, version, resolvedBuild, fileName),
        )
    }

    private fun ProductFeedCase.newManager(
        home: Path,
        installedBuild: String = this.installedBuild,
    ): BackendManager {
        val resolution = resolve()
        return BackendManager(
            homePaths = HomePaths(home),
            downloader = FeedResolutionDownloader(
                resolution = resolution,
                installedProductCode = product.installedProductCode,
                installedBuild = installedBuild,
                launcherExecutable = product.launcherExecutable,
            ),
            bundledPluginResolver = FixtureBundledPluginResolver(home.resolve("dist/ij-plugin.zip")),
            pluginBuildRange = bundledPluginRange,
        )
    }

    /**
     * Installs what the resolved archive would unpack to: a bundle whose `product-info.json` carries
     * the real artifact's product code and build. Everything before it — the feed dispatch and the
     * resolution — is production code.
     */
    private class FeedResolutionDownloader(
        private val resolution: IdeArchiveResolution,
        private val installedProductCode: String,
        private val installedBuild: String,
        private val launcherExecutable: String,
    ) : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId) = BackendDownloadResolution(
            product = resolution.product,
            version = resolution.version,
            build = resolution.build,
            buildIsBaseline = resolution.buildIsBaseline,
            url = resolution.url,
            expectedSha256 = resolution.expectedSha256,
        )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact {
            val bundleDir = targetDir.resolve("${resolution.product.id}-${resolution.version}")
            Files.createDirectories(bundleDir.resolve("bin"))
            Files.writeString(
                bundleDir.resolve("product-info.json"),
                """
                {
                  "productCode": "$installedProductCode",
                  "buildNumber": "$installedBuild",
                  "launch": [
                    { "os": "Linux", "launcherPath": "bin/$launcherExecutable.sh" },
                    { "os": "macOS", "launcherPath": "bin/$launcherExecutable.sh" },
                    { "os": "Windows", "launcherPath": "bin/$launcherExecutable.bat" }
                  ]
                }
                """.trimIndent(),
            )
            Files.writeString(bundleDir.resolve("bin/$launcherExecutable.sh"), "#!/usr/bin/env sh\n")
            Files.writeString(bundleDir.resolve("bin/$launcherExecutable.bat"), "@echo off\r\n")
            // A real IU 262 artifact ships the native Remote Development launcher and plugin, and
            // download() refuses an idea-ultimate 262 install without them — mirror the artifact.
            if (ManagedBackendLauncherResolver().usesRemoteDevelopment(resolution.product.id, installedBuild)) {
                Files.writeString(bundleDir.resolve("bin/remote-dev-server"), "#!/usr/bin/env sh\n")
                    .toFile()
                    .setExecutable(true)
                Files.writeString(bundleDir.resolve("bin/remote-dev-server.exe"), "remote development launcher")
                val pluginJar = bundleDir.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
                Files.createDirectories(pluginJar.parent)
                Files.writeString(pluginJar, "remote development plugin")
            }
            return BackendDownloadArtifact(sourceArchiveSha256 = "sha-$installedProductCode")
        }
    }
}
