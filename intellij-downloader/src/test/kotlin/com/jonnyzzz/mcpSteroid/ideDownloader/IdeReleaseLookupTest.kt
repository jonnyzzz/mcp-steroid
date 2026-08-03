/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

class IdeReleaseLookupTest {

    // ---------- existing IntelliJ IDEA Ultimate (IIU) sanity ----------

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Linux`() {
        val url = resolveArchiveUrlFromFixtures(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolves IDEA Ultimate EAP archive URL for Linux`() {
        val url = resolveArchiveUrlFromFixtures(IdeProduct.IntelliJIdea, IdeChannel.EAP, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
    }

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Mac`() {
        val url = resolveArchiveUrlFromFixtures(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.MAC)
        assertTrue("Expected .dmg URL, got: $url", url.endsWith(".dmg"))
    }

    @Test
    fun `resolves IDEA Ultimate stable archive URL for Windows`() {
        val url = resolveArchiveUrlFromFixtures(IdeProduct.IntelliJIdea, IdeChannel.STABLE, os = HostOs.WINDOWS)
        assertTrue("Expected .exe URL, got: $url", url.endsWith(".exe"))
    }

    @Test
    fun `resolves Rider stable archive URL for Linux`() {
        val url = resolveArchiveUrlFromFixtures(IdeProduct.Rider, IdeChannel.STABLE, os = HostOs.LINUX)
        assertTrue("Expected .tar.gz URL, got: $url", url.endsWith(".tar.gz"))
        assertTrue("Expected download URL, got: $url", url.contains("download"))
    }

    @Test
    fun `resolver skips release whose filename belongs to another edition`() {
        val payload = productsPayload(
            IdeProduct.IntelliJIdeaCommunity,
            listOf(
                FixtureRelease(
                    version = "2025.3",
                    build = "253.28294.334",
                    link = "https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg",
                ),
                FixtureRelease(
                    version = "2025.2.6.2",
                    build = "252.28238.39",
                    link = "https://download.jetbrains.com/idea/ideaIC-2025.2.6.2-aarch64.dmg",
                ),
            ),
        )

        val resolution = resolveArchiveFromProductsApiPayload(
            product = IdeProduct.IntelliJIdeaCommunity,
            channel = IdeChannel.STABLE,
            os = HostOs.MAC,
            architecture = HostArchitecture.ARM64,
            productsApiUrl = "fixture://products?code=IIC",
            payload = payload,
        )

        assertEquals("2025.2.6.2", resolution.version)
        assertEquals("https://download.jetbrains.com/idea/ideaIC-2025.2.6.2-aarch64.dmg", resolution.url)
    }

    @Test
    fun `resolver surfaces checksumLink when products API provides it`() {
        val checksumLink = "https://download.jetbrains.com/idea/ideaIC-2025.2.6.2-aarch64.dmg.sha256"
        val payload = productsPayload(
            IdeProduct.IntelliJIdeaCommunity,
            listOf(
                FixtureRelease(
                    version = "2025.2.6.2",
                    build = "252.28238.39",
                    link = "https://download.jetbrains.com/idea/ideaIC-2025.2.6.2-aarch64.dmg",
                    checksumLink = checksumLink,
                ),
            ),
        )

        val resolution = resolveArchiveFromProductsApiPayload(
            product = IdeProduct.IntelliJIdeaCommunity,
            channel = IdeChannel.STABLE,
            os = HostOs.MAC,
            architecture = HostArchitecture.ARM64,
            productsApiUrl = "fixture://products?code=IIC",
            payload = payload,
        )

        assertEquals(checksumLink, resolution.checksumUrl)
        assertEquals(null, resolution.expectedSha256)
    }

    @Test
    fun `resolver keeps checksumLink null when products API omits it`() {
        val payload = productsPayload(
            IdeProduct.IntelliJIdeaCommunity,
            listOf(
                FixtureRelease(
                    version = "2025.2.6.2",
                    build = "252.28238.39",
                    link = "https://download.jetbrains.com/idea/ideaIC-2025.2.6.2-aarch64.dmg",
                ),
            ),
        )

        val resolution = resolveArchiveFromProductsApiPayload(
            product = IdeProduct.IntelliJIdeaCommunity,
            channel = IdeChannel.STABLE,
            os = HostOs.MAC,
            architecture = HostArchitecture.ARM64,
            productsApiUrl = "fixture://products?code=IIC",
            payload = payload,
        )

        assertEquals(null, resolution.checksumUrl)
        assertEquals(null, resolution.expectedSha256)
    }

    @Test
    fun `Android Studio parser surfaces inline SHA-256 for selected download`() {
        val expectedSha256 = "aae8f332f124afd23ca495dc770915a456da7480c8f859e01535ad42fcb4ca06"
        val html = """
            <a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2025.3.4.7/android-studio-panda4-patch1-linux.tar.gz">download</a>
            <table class="download">
              <tr>
                <td>Linux<br>(64-bit)</td>
                <td><button>android-studio-panda4-patch1-linux.tar.gz</button></td>
                <td>1.5 GB</td>
                <td>$expectedSha256</td>
              </tr>
            </table>
        """.trimIndent()

        val resolution = resolveAndroidStudioArchiveFromHtml(
            channel = IdeChannel.STABLE,
            os = HostOs.LINUX,
            architecture = HostArchitecture.X86_64,
            version = null,
            pageUrl = "fixture://android-studio",
            html = html,
        )

        assertEquals(expectedSha256, resolution.expectedSha256)
        assertEquals(null, resolution.checksumUrl)
    }

    @Test
    fun `resolver fails clearly when no release filename matches the product tokens`() {
        val payload = productsPayload(
            IdeProduct.IntelliJIdeaCommunity,
            listOf(
                FixtureRelease(
                    version = "2025.3",
                    build = "253.28294.334",
                    link = "https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg",
                ),
            ),
        )

        val ex = expectError {
            resolveArchiveFromProductsApiPayload(
                product = IdeProduct.IntelliJIdeaCommunity,
                channel = IdeChannel.STABLE,
                os = HostOs.MAC,
                architecture = HostArchitecture.ARM64,
                productsApiUrl = "fixture://products?code=IIC",
                payload = payload,
            )
        }

        assertTrue("expected product code in error, got: ${ex.message}", ex.message!!.contains("code=IIC"))
        assertTrue("expected token in error, got: ${ex.message}", ex.message!!.contains("ideaIC-"))
        assertTrue("expected skipped filename in error, got: ${ex.message}", ex.message!!.contains("idea-2025.3-aarch64.dmg"))
    }

    @Test
    fun `fixture JetBrains products resolve to URLs accepted by their filename token list`() {
        val products = IdeProduct.knownProducts.filterNot { it === IdeProduct.AndroidStudio }
        for (product in products) {
            assertTrue("${product.code} must define URL filename tokens", product.urlFilenameTokens.isNotEmpty())
            val resolution = resolveArchiveFromFixtures(
                product,
                IdeChannel.STABLE,
                os = HostOs.MAC,
                architecture = HostArchitecture.ARM64,
            )
            val filename = downloadFilenameFromUrl(resolution.url)
            assertTrue(
                "${product.code} resolved $filename, expected one of ${product.urlFilenameTokens}",
                product.acceptsDownloadFilename(filename),
            )
        }
    }

    @Test
    @Category(LiveNetwork::class)
    fun `live JetBrains product feed URLs still match filename token list`() {
        val products = listOf(IdeProduct.IntelliJIdea, IdeProduct.Rider)
        for (product in products) {
            val resolution = resolveArchive(
                product,
                IdeChannel.STABLE,
                os = HostOs.MAC,
                architecture = HostArchitecture.ARM64,
            )
            val filename = downloadFilenameFromUrl(resolution.url)
            assertTrue(
                "${product.code} resolved $filename from live feed, expected one of ${product.urlFilenameTokens}",
                product.acceptsDownloadFilename(filename),
            )
        }
    }

    /**
     * The offline matrix above runs off recorded fixtures, so it cannot catch JetBrains renaming an
     * archive or dropping an edition. This walks the SAME assertions against the live products API
     * for every catalog product. Opt-in (`:intellij-downloader:liveNetworkTest`) so a vendor-feed
     * outage cannot redden a default build.
     */
    @Test
    @Category(LiveNetwork::class)
    fun `live feed resolves every catalog product on every published OS-arch combo`() {
        val products = IdeProduct.knownProducts.filterNot { it === IdeProduct.AndroidStudio }
        val failures = mutableListOf<String>()
        for (product in products) {
            for (os in HostOs.values()) {
                for (arch in HostArchitecture.values()) {
                    val expectedUnpublished = product.id in unpublishedPlatforms &&
                        (os to arch) in unpublishedPlatforms.getValue(product.id)
                    try {
                        val resolution = resolveArchive(product, IdeChannel.STABLE, os = os, architecture = arch)
                        if (expectedUnpublished) {
                            failures += "${product.code} on $os/$arch is listed as unpublished but the live " +
                                "feed now serves ${resolution.url} — drop it from unpublishedPlatforms"
                            continue
                        }
                        val filename = downloadFilenameFromUrl(resolution.url)
                        if (!product.acceptsDownloadFilename(filename)) {
                            failures += "${product.code} on $os/$arch resolved $filename, which none of " +
                                "${product.urlFilenameTokens} accepts"
                        }
                    } catch (e: Exception) {
                        if (!expectedUnpublished) {
                            failures += "${product.code} on $os/$arch failed to resolve: ${e.message}"
                        }
                    }
                }
            }
        }
        assertTrue("Live feed resolution failures:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /**
     * Every catalog product must resolve on every host the feed publishes it for. A new
     * `knownProducts` entry fails here until its fixture exists and its filename tokens are right,
     * so a product cannot land in the CLI list while being undownloadable.
     *
     * [unpublishedPlatforms] is the explicit, per-product allow-list of OS/arch combos JetBrains
     * does not publish at all; those are asserted to fail with the dedicated diagnostic rather than
     * silently skipped.
     */
    @Test
    fun `every catalog product resolves on every published OS-arch combo`() {
        val products = IdeProduct.knownProducts.filterNot { it === IdeProduct.AndroidStudio }
        for (product in products) {
            for (os in HostOs.values()) {
                for (arch in HostArchitecture.values()) {
                    val expectedUnpublished = product.id in unpublishedPlatforms &&
                        (os to arch) in unpublishedPlatforms.getValue(product.id)
                    if (expectedUnpublished) {
                        val ex = expectError {
                            resolveArchiveFromFixtures(product, IdeChannel.STABLE, os = os, architecture = arch)
                        }
                        assertTrue(
                            "${product.code} on $os/$arch must fail with the unpublished-platform " +
                                "diagnostic, got: ${ex.message}",
                            ex.message!!.contains("publishes no '${resolveDownloadKey(os, arch)}' distribution"),
                        )
                        continue
                    }
                    val resolution = resolveArchiveFromFixtures(product, IdeChannel.STABLE, os = os, architecture = arch)
                    assertExpectedExtension(product, os, arch, resolution.url)
                    val filename = downloadFilenameFromUrl(resolution.url)
                    assertTrue(
                        "${product.code} resolved $filename on $os/$arch, expected one of ${product.urlFilenameTokens}",
                        product.acceptsDownloadFilename(filename),
                    )
                }
            }
        }
    }

    /**
     * MPS is the only catalog product with platform gaps: its feed entry carries no `linuxARM64`
     * and no `windowsARM64` download key in any release. Verified against the live feed; the
     * fixture reproduces it.
     */
    private val unpublishedPlatforms: Map<String, Set<Pair<HostOs, HostArchitecture>>> = mapOf(
        IdeProduct.Mps.id to setOf(
            HostOs.LINUX to HostArchitecture.ARM64,
            HostOs.WINDOWS to HostArchitecture.ARM64,
        ),
    )

    @Test
    fun `MPS Linux ARM64 names the keys the feed does offer instead of suggesting a version retry`() {
        val ex = expectError {
            resolveArchiveFromFixtures(
                IdeProduct.Mps,
                IdeChannel.STABLE,
                os = HostOs.LINUX,
                architecture = HostArchitecture.ARM64,
            )
        }
        val message = ex.message!!
        assertTrue("expected the offered keys listed, got: $message", message.contains("linux, mac, macM1"))
        assertTrue("expected product code, got: $message", message.contains("code=MPS"))
        assertFalse("must not advise --version for an unpublished platform, got: $message", message.contains("--version"))
    }

    @Test
    fun `DataGrip is queried as DG but validates the installed code DB`() {
        assertEquals("DG", IdeProduct.DataGrip.code)
        assertEquals("DB", IdeProduct.DataGrip.installedProductCode)
        assertTrue(IdeProduct.fromString("datagrip") === IdeProduct.DataGrip)
        assertTrue(IdeProduct.fromString("DG") === IdeProduct.DataGrip)
        // `DB` is the spelling prompts/AGENTS.md uses — it must reach the same product.
        assertTrue(IdeProduct.fromString("DB") === IdeProduct.DataGrip)
    }

    @Test
    fun `newly added products resolve via fromString and carry a verified license tier`() {
        assertTrue(IdeProduct.fromString("phpstorm") === IdeProduct.PhpStorm)
        assertTrue(IdeProduct.fromString("PS") === IdeProduct.PhpStorm)
        assertTrue(IdeProduct.fromString("php") === IdeProduct.PhpStorm)
        assertTrue(IdeProduct.fromString("rubymine") === IdeProduct.RubyMine)
        assertTrue(IdeProduct.fromString("RM") === IdeProduct.RubyMine)
        assertTrue(IdeProduct.fromString("rustrover") === IdeProduct.RustRover)
        assertTrue(IdeProduct.fromString("RR") === IdeProduct.RustRover)
        assertTrue(IdeProduct.fromString("mps") === IdeProduct.Mps)
        assertTrue(IdeProduct.fromString("MPS") === IdeProduct.Mps)

        assertEquals(LicenseTier.Paid, IdeProduct.PhpStorm.licenseTier)
        assertEquals(LicenseTier.Paid, IdeProduct.RubyMine.licenseTier)
        assertEquals(LicenseTier.Paid, IdeProduct.DataGrip.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.RustRover.licenseTier)
        assertEquals(LicenseTier.Free, IdeProduct.Mps.licenseTier)
    }

    /**
     * #430: every product code the prompt pipeline claims to support must have a downloadable
     * backend. The prompt codes are `ApplicationInfo` codes, so they compare against
     * [IdeProduct.installedProductCode] — which is exactly why DataGrip carries `DB` and not `DG`.
     * Source list: `prompts/AGENTS.md` "Common codes" + `PerIdeAvailabilityContractTest`.
     */
    @Test
    fun `every prompt-pipeline product code has a downloadable backend`() {
        val promptPipelineCodes = listOf("IU", "IC", "AI", "RD", "CL", "GO", "PY", "WS", "RM", "DB")
        val installedCodes = IdeProduct.knownProducts.map { it.installedProductCode }.toSet()
        val missing = promptPipelineCodes.filterNot { it in installedCodes }
        assertTrue(
            "prompts ship for $missing but devrig cannot download a backend for them; " +
                "installedProductCode values in the catalog: ${installedCodes.sorted()}",
            missing.isEmpty(),
        )
    }

    @Test
    fun `catalog product ids and installed codes are unique`() {
        val ids = IdeProduct.knownProducts.map { it.id }
        assertEquals("duplicate product ids in knownProducts: $ids", ids.size, ids.toSet().size)
        val installedCodes = IdeProduct.knownProducts.map { it.installedProductCode }
        assertEquals(
            "duplicate installedProductCode in knownProducts: $installedCodes",
            installedCodes.size,
            installedCodes.toSet().size,
        )
        // Every id must round-trip through the alias map to the very same product.
        for (product in IdeProduct.knownProducts) {
            assertTrue("${product.id} must round-trip via fromString", IdeProduct.fromString(product.id) === product)
            assertTrue("${product.code} must round-trip via fromString", IdeProduct.fromString(product.code) === product)
        }
    }

    /**
     * Guards the in/out-of-scope decision recorded next to `knownProducts`: DataSpell (`DS`),
     * GitClient (`GIG`) and CLion Nova (`CLN`) were evaluated and left out on purpose. If someone
     * adds one without also revisiting that decision, this fails and points at the comment.
     */
    @Test
    fun `deliberately excluded product codes stay out of the catalog`() {
        val excluded = listOf("DS", "GIG", "CLN", "GW", "AC")
        val catalogCodes = IdeProduct.knownProducts.map { it.code }.toSet()
        for (code in excluded) {
            assertFalse(
                "$code is documented as deliberately excluded next to IdeProduct.knownProducts — " +
                    "adding it needs that decision revisited first",
                code in catalogCodes,
            )
        }
        // CLion Nova is dead upstream: the feed folds it into CL, which we already ship.
        assertTrue("CL must stay in the catalog — it covers CLion Nova", "CL" in catalogCodes)
    }

    @Test
    fun `Custom product keeps accepting arbitrary API filenames`() {
        val custom = IdeProduct.Custom(
            id = "rubymine",
            displayName = "RubyMine",
            code = "RM",
            launcherExecutable = "rubymine",
            licenseTier = LicenseTier.FreeForNonCommercial,
        )
        val payload = productsPayload(
            custom,
            listOf(
                FixtureRelease(
                    version = "2026.1",
                    build = "261.1",
                    link = "https://download.jetbrains.com/ruby/RubyMine-2026.1-aarch64.dmg",
                ),
            ),
        )

        val resolution = resolveArchiveFromProductsApiPayload(
            product = custom,
            channel = IdeChannel.STABLE,
            os = HostOs.MAC,
            architecture = HostArchitecture.ARM64,
            productsApiUrl = "fixture://products?code=RM",
            payload = payload,
        )

        assertEquals("https://download.jetbrains.com/ruby/RubyMine-2026.1-aarch64.dmg", resolution.url)
    }

    // ---------- new: IntelliJ Community (IIC) on every OS × arch ----------

    @Test
    fun `IntelliJ Community resolves for every OS-arch combo`() {
        for (os in HostOs.values()) {
            for (arch in HostArchitecture.values()) {
                val url = resolveArchiveUrlFromFixtures(
                    IdeProduct.IntelliJIdeaCommunity,
                    IdeChannel.STABLE,
                    os = os,
                    architecture = arch
                )
                assertExpectedExtension(IdeProduct.IntelliJIdeaCommunity, os, arch, url)
            }
        }
    }

    // ---------- new: PyCharm Community (PCC) on every OS × arch ----------

    @Test
    fun `PyCharm Community resolves for every OS-arch combo`() {
        for (os in HostOs.values()) {
            for (arch in HostArchitecture.values()) {
                val url = resolveArchiveUrlFromFixtures(
                    IdeProduct.PyCharmCommunity,
                    IdeChannel.STABLE,
                    os = os,
                    architecture = arch,
                )
                assertExpectedExtension(IdeProduct.PyCharmCommunity, os, arch, url)
            }
        }
    }

    // ---------- Android Studio (Google) ----------

    @Test
    fun `Android Studio resolves stable URLs for supported OS-arch combos`() {
        val cases = listOf(
            Triple(HostOs.LINUX, HostArchitecture.X86_64, "-linux.tar.gz"),
            Triple(HostOs.MAC, HostArchitecture.X86_64, "-mac.dmg"),
            Triple(HostOs.MAC, HostArchitecture.ARM64, "-mac_arm.dmg"),
        )
        for ((os, arch, suffix) in cases) {
            val url = resolveArchiveUrlFromFixtures(IdeProduct.AndroidStudio, IdeChannel.STABLE, os = os, architecture = arch)
            assertTrue("Expected $os/$arch URL to end with $suffix, got: $url", url.endsWith(suffix))
            assertTrue("Expected gvt1.com URL, got: $url", url.contains("gvt1.com") || url.contains("googleusercontent"))
        }
    }

    @Test
    fun `Android Studio on Windows x64 yields exe`() {
        val url = resolveArchiveUrlFromFixtures(
            IdeProduct.AndroidStudio, IdeChannel.STABLE,
            os = HostOs.WINDOWS, architecture = HostArchitecture.X86_64,
        )
        assertTrue("Expected .exe URL, got: $url", url.endsWith("-windows.exe"))
    }

    @Test
    fun `Android Studio version can be inferred from current install URL path`() {
        val url = "https://edgedl.me.gvt1.com/android/studio/install/2025.3.4.7/android-studio-panda4-patch1-mac_arm.dmg"

        assertEquals("2025.3.4.7", inferAndroidStudioVersion(url))
    }

    @Test
    fun `Android Studio rejects unsupported Linux ARM64`() {
        val ex = expectError {
            resolveArchiveUrlFromFixtures(IdeProduct.AndroidStudio, IdeChannel.STABLE,
                os = HostOs.LINUX, architecture = HostArchitecture.ARM64)
        }
        assertTrue("expected 'Linux ARM64' message, got: ${ex.message}",
            ex.message!!.contains("Linux ARM64"))
    }

    @Test
    fun `Android Studio rejects unsupported Windows ARM64`() {
        val ex = expectError {
            resolveArchiveUrlFromFixtures(IdeProduct.AndroidStudio, IdeChannel.STABLE,
                os = HostOs.WINDOWS, architecture = HostArchitecture.ARM64)
        }
        assertTrue("expected 'Windows ARM64' message, got: ${ex.message}",
            ex.message!!.contains("Windows ARM64"))
    }

    @Test
    fun `Android Studio rejects EAP channel (canary not wired up)`() {
        val ex = expectError {
            resolveArchiveUrlFromFixtures(IdeProduct.AndroidStudio, IdeChannel.EAP, os = HostOs.MAC)
        }
        assertTrue("expected channel message, got: ${ex.message}",
            ex.message!!.contains("only IdeChannel.STABLE is supported"))
    }

    @Test
    fun `Android Studio aliases resolve via fromString`() {
        assertTrue(IdeProduct.fromString("android-studio") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("studio") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("AI") === IdeProduct.AndroidStudio)
        assertTrue(IdeProduct.fromString("android") === IdeProduct.AndroidStudio)
        assertEquals(LicenseTier.Free, IdeProduct.AndroidStudio.licenseTier)
    }

    // ---------- download-key matrix ----------

    @Test
    fun `resolveDownloadKey maps correctly`() {
        assertEquals("linux", resolveDownloadKey(HostOs.LINUX, HostArchitecture.X86_64))
        assertEquals("linuxARM64", resolveDownloadKey(HostOs.LINUX, HostArchitecture.ARM64))
        assertEquals("mac", resolveDownloadKey(HostOs.MAC, HostArchitecture.X86_64))
        assertEquals("macM1", resolveDownloadKey(HostOs.MAC, HostArchitecture.ARM64))
        assertEquals("windows", resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.X86_64))
        assertEquals("windowsARM64", resolveDownloadKey(HostOs.WINDOWS, HostArchitecture.ARM64))
    }

    // ---------- product enum / aliases ----------

    @Test
    fun `IdeProduct fromString maps known aliases`() {
        assertEquals("idea-ultimate", IdeProduct.IntelliJIdea.id)
        assertEquals("pycharm-pro", IdeProduct.PyCharm.id)
        assertTrue(IdeProduct.fromString("idea") === IdeProduct.IntelliJIdea)
        assertTrue(IdeProduct.fromString("idea-ultimate") === IdeProduct.IntelliJIdea)
        assertTrue(IdeProduct.fromString("idea-community") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("IIC") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("community") === IdeProduct.IntelliJIdeaCommunity)
        assertTrue(IdeProduct.fromString("pycharm") === IdeProduct.PyCharm)
        assertTrue(IdeProduct.fromString("pycharm-pro") === IdeProduct.PyCharm)
        assertTrue(IdeProduct.fromString("pycharm-community") === IdeProduct.PyCharmCommunity)
        assertTrue(IdeProduct.fromString("PCC") === IdeProduct.PyCharmCommunity)
        assertTrue(IdeProduct.fromString("goland") === IdeProduct.GoLand)
        assertTrue(IdeProduct.fromString("webstorm") === IdeProduct.WebStorm)
        assertTrue(IdeProduct.fromString("rider") === IdeProduct.Rider)
        assertTrue(IdeProduct.fromString("clion") === IdeProduct.CLion)
    }

    @Test
    fun `IdeProduct license tier classification`() {
        assertEquals(LicenseTier.Paid, IdeProduct.IntelliJIdea.licenseTier)
        assertEquals(LicenseTier.Paid, IdeProduct.PyCharm.licenseTier)
        assertEquals(LicenseTier.Free, IdeProduct.IntelliJIdeaCommunity.licenseTier)
        assertEquals(LicenseTier.Free, IdeProduct.PyCharmCommunity.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.GoLand.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.WebStorm.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.Rider.licenseTier)
        assertEquals(LicenseTier.FreeForNonCommercial, IdeProduct.CLion.licenseTier)
    }

    @Test
    fun `IdeProduct Custom can describe unknown JetBrains products`() {
        val rubymine = IdeProduct.Custom(
            id = "rubymine",
            displayName = "RubyMine",
            code = "RM",
            launcherExecutable = "rubymine",
            licenseTier = LicenseTier.FreeForNonCommercial,
        )
        assertEquals("RM", rubymine.code)
        assertEquals(LicenseTier.FreeForNonCommercial, rubymine.licenseTier)
    }

    @Test
    fun `IdeDistribution Latest accepts paid SKUs without a consent flag`() {
        val distribution = IdeDistribution.Latest(IdeProduct.IntelliJIdea)

        assertEquals(IdeProduct.IntelliJIdea, distribution.product)
        assertEquals(IdeChannel.STABLE, distribution.channel)
    }

    // ---------- host helpers ----------

    @Test
    fun `HostArchitecture resolves correctly`() {
        val arm = resolveHostArchitecture("aarch64")
        assertEquals(HostArchitecture.ARM64, arm)
        assertTrue(arm.isArmArch)
        val x86 = resolveHostArchitecture("x86_64")
        assertEquals(HostArchitecture.X86_64, x86)
        assertTrue(!x86.isArmArch)
    }

    @Test
    fun `HostOs resolves correctly`() {
        assertEquals(HostOs.LINUX, resolveHostOs("Linux"))
        assertEquals(HostOs.MAC, resolveHostOs("Mac OS X"))
        assertEquals(HostOs.MAC, resolveHostOs("Darwin"))
        assertEquals(HostOs.WINDOWS, resolveHostOs("Windows 10"))
    }

    // ---------- helpers ----------

    /**
     * Verifies that the resolved URL's extension matches the platform expectation.
     * IDEs ship one canonical archive type per platform:
     *  - linux / mac → .tar.gz / .dmg
     *  - windows → .exe (installer)
     */
    private fun resolveArchiveUrlFromFixtures(
        product: IdeProduct,
        channel: IdeChannel,
        os: HostOs = resolveHostOs(),
        architecture: HostArchitecture = resolveHostArchitecture(),
        version: String? = null,
    ): String = resolveArchiveFromFixtures(product, channel, os, architecture, version).url

    private fun resolveArchiveFromFixtures(
        product: IdeProduct,
        channel: IdeChannel,
        os: HostOs = resolveHostOs(),
        architecture: HostArchitecture = resolveHostArchitecture(),
        version: String? = null,
    ): IdeArchiveResolution = resolveArchiveWithUrlReader(
        product = product,
        channel = channel,
        os = os,
        architecture = architecture,
        version = version,
        urlReader = ::fixtureForUrl,
    )

    private fun fixtureForUrl(url: String): String {
        if (url == "https://developer.android.com/studio") {
            return fixtureText("android-studio.html")
        }
        val code = Regex("""[?&]code=([^&]+)""")
            .find(url)
            ?.groupValues
            ?.get(1)
            ?: error("Fixture URL does not include a products API code: $url")
        return fixtureText("products-$code.json")
    }

    private fun fixtureText(name: String): String {
        val resource = javaClass.classLoader.getResource("fixtures/$name")
            ?: error("Missing test fixture: fixtures/$name")
        return resource.readText()
    }

    private fun assertExpectedExtension(product: IdeProduct, os: HostOs, arch: HostArchitecture, url: String) {
        val expectedSuffixes: List<String> = when (os) {
            HostOs.LINUX -> listOf(".tar.gz", ".tgz")
            HostOs.MAC -> listOf(".dmg")
            HostOs.WINDOWS -> listOf(".exe")
        }
        assertTrue(
            "Expected URL for ${product.code}/$os/$arch to end with one of $expectedSuffixes, got: $url",
            expectedSuffixes.any { url.endsWith(it) }
        )
        assertTrue("Expected download URL, got: $url", url.startsWith("https://") || url.startsWith("http://"))
    }

    private data class FixtureRelease(
        val version: String,
        val build: String,
        val link: String,
        val type: String = IdeChannel.STABLE.apiValue,
        val checksumLink: String? = null,
    )

    private fun productsPayload(product: IdeProduct, releases: List<FixtureRelease>): String {
        val payload = buildJsonArray {
            add(buildJsonObject {
                put("code", product.code)
                put("releases", buildJsonArray {
                    for (release in releases) {
                        add(buildJsonObject {
                            put("type", release.type)
                            put("version", release.version)
                            put("build", release.build)
                            put("date", "2026-05-15")
                            put("downloads", buildJsonObject {
                                put("macM1", buildJsonObject {
                                    put("link", release.link)
                                    if (release.checksumLink != null) {
                                        put("checksumLink", release.checksumLink)
                                    }
                                })
                            })
                        })
                    }
                })
            })
        }
        return Json.encodeToString(JsonArray.serializer(), payload)
    }

    private inline fun expectError(block: () -> Unit): Throwable {
        try { block() } catch (e: Throwable) { return e }
        fail("Expected an exception; none thrown")
        @Suppress("UNREACHABLE_CODE") throw AssertionError()
    }
}
