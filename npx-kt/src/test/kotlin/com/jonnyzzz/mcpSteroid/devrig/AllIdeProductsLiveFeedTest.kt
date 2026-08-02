/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The same contract as [AllIdeProductsDownloadTest], but against the LIVE vendor feeds: every
 * supported IDE must still resolve, still be plugin-compatible, and still declare its build shape
 * truthfully. Catches a feed changing what it publishes (the 2025.3 -> 262 Community move that
 * produced #423) without waiting for a user to hit it after a 850 MB download.
 *
 * Opt-in — `./gradlew :npx-kt:liveNetworkTest`. Resolution only, so it costs three HTTP GETs
 * per product, not a download.
 */
@Tag("live-network")
class AllIdeProductsLiveFeedTest {

    /** Mirrors `ij-plugin/build.gradle.kts` — `sinceBuild = "261"`, no upper bound. */
    private val bundledPluginRange = PluginBuildRange(sinceBaseline = 261, untilBaseline = null)

    // Feeds are host-independent; pinning the host keeps the assertions the same everywhere.
    private val os = HostOs.MAC
    private val architecture = HostArchitecture.ARM64

    @Test
    fun `every supported product resolves from its live feed`(@TempDir tempDir: Path) {
        for (product in IdeProduct.knownProducts) {
            val resolution = resolveBackendArchive(product = product, os = os, architecture = architecture)

            assertEquals(product, resolution.product)
            assertTrue(resolution.url.startsWith("https://"), "${product.id}: ${resolution.url}")
            assertTrue(resolution.version.isNotBlank(), "${product.id}: blank version")
            assertTrue(resolution.build.isNotBlank(), "${product.id}: blank build")
            assertTrue(
                bundledPluginRange.accepts(resolution.build),
                "${product.id} ${resolution.version} (build ${resolution.build}) is outside " +
                    "${bundledPluginRange.describe()} — `devrig backend download` would refuse it",
            )

            // Only data.services.jetbrains.com publishes a full build; the other two feeds stop at the
            // platform baseline and MUST say so via buildIsBaseline (#429).
            val expectBaseline = backendDownloadFeed(product).publishesBaselineOnly
            assertEquals(expectBaseline, resolution.buildIsBaseline, "buildIsBaseline for ${product.id}")
            assertEquals(expectBaseline, isPlatformBaselineOnly(resolution.build), "build shape for ${product.id}")

            // #423: the build the unpacked artifact reports must pass validation against what the feed
            // resolved to. For a baseline feed that is a full build on the same baseline.
            val installedBuild = installedBuildFor(resolution.build, resolution.buildIsBaseline)
            validateInstalledBuildNumber(
                product = product,
                expectedBuild = resolution.build,
                expectedBuildIsBaseline = resolution.buildIsBaseline,
                actualBuildNumber = installedBuild,
                downloadedUrl = resolution.url,
                archivePath = null,
                bundleDir = tempDir.resolve("bundle/${product.id}"),
                descriptorPath = tempDir.resolve("descriptor/${product.id}.json"),
            )

            // …and an artifact from the next baseline must still be refused.
            assertFailsWith<ManagedBackendValidationException>(product.id) {
                validateInstalledBuildNumber(
                    product = product,
                    expectedBuild = resolution.build,
                    expectedBuildIsBaseline = resolution.buildIsBaseline,
                    actualBuildNumber = installedBuild.replaceFirst(
                        Regex("""(\D*)(\d{3})"""),
                        "$1${ideBuildBaseline(resolution.build)!! + 1}",
                    ),
                    downloadedUrl = resolution.url,
                    archivePath = null,
                    bundleDir = tempDir.resolve("reject/${product.id}"),
                    descriptorPath = tempDir.resolve("reject/${product.id}.json"),
                )
            }
        }
    }

    /**
     * The `product-info.json` build an install of [resolvedBuild] reports. A baseline feed only names
     * the platform, so the artifact adds the build/patch segments (`262` -> `262.8665.258`); the
     * products API already named the whole thing. Marker-style product-code prefixes are a separate
     * path, covered by [IdeBuildMatchesTest].
     */
    private fun installedBuildFor(resolvedBuild: String, isBaseline: Boolean): String =
        if (isBaseline) "$resolvedBuild.8665.258" else resolvedBuild
}
