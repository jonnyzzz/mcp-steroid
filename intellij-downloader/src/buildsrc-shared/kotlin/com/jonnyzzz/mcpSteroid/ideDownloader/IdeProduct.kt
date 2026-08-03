/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

/**
 * License tier of a JetBrains IDE. The downloader always fetches requested binaries;
 * licensing concerns apply when the IDE is activated, so CLI renderers use this tier
 * only for annotations.
 */
enum class LicenseTier {
    /** Open-source / fully free editions (IntelliJ Community, PyCharm Community). */
    Free,

    /** Free for non-commercial use; paid for commercial use (Rider, CLion, GoLand, WebStorm). */
    FreeForNonCommercial,

    /** Paid editions (IntelliJ IDEA Ultimate, PyCharm Professional). */
    Paid,
}

/**
 * A JetBrains IDE product the downloader knows how to resolve.
 *
 * Implementations are either one of the `data object` constants below (for well-known
 * IDEs we ship aliases for) or [Custom] for any other JetBrains product. The product
 * `code` is what the public products API expects (e.g. "IIU", "IIC", "PCC", "PCP").
 *
 * Keeping the type sealed and the well-known products as `data object` preserves source
 * compatibility with existing callers that reference `IdeProduct.IntelliJIdea` etc.
 */
sealed interface IdeProduct {
    /** Short slug used in CLI args, file names, etc. */
    val id: String

    val displayName: String

    /** JetBrains product code passed to `data.services.jetbrains.com/products?code=…`. */
    val code: String

    /** Launcher binary name inside the unpacked distribution. */
    val launcherExecutable: String

    val licenseTier: LicenseTier

    /**
     * Case-sensitive filename tokens that identify archives for this product in the
     * JetBrains products API. Empty means the resolver accepts any filename; this
     * keeps [Custom] products unblocked when the caller deliberately opts into an
     * unknown SKU.
     */
    val urlFilenameTokens: List<String> get() = emptyList()

    /** Top-level `productCode` expected in the unpacked bundle's product-info.json. */
    val installedProductCode: String get() = code

    // ---- IntelliJ IDEA ----

    /** IntelliJ IDEA Ultimate (paid). Kept as `IntelliJIdea` to preserve existing call sites. */
    data object IntelliJIdea : IdeProduct {
        override val id = "idea-ultimate"
        override val displayName = "IntelliJ IDEA Ultimate"
        override val code = "IIU"
        override val launcherExecutable = "idea"
        override val licenseTier = LicenseTier.Paid
        override val installedProductCode = "IU"
        // HEAD evidence: current Ultimate releases use unprefixed idea-*. Older releases used ideaIU-*.
        override val urlFilenameTokens = listOf("ideaIU-", "idea-")
    }

    /** IntelliJ IDEA Community (free, open source). */
    data object IntelliJIdeaCommunity : IdeProduct {
        override val id = "idea-community"
        override val displayName = "IntelliJ IDEA Community"
        override val code = "IIC"
        override val launcherExecutable = "idea"
        override val licenseTier = LicenseTier.Free
        override val installedProductCode = "IC"
        // HEAD evidence: Community binaries use ideaIC-*; unprefixed idea-* is Ultimate.
        override val urlFilenameTokens = listOf("ideaIC-")
    }

    // ---- PyCharm ----

    /** PyCharm Professional (paid). Kept as `PyCharm` to preserve existing call sites. */
    data object PyCharm : IdeProduct {
        override val id = "pycharm-pro"
        override val displayName = "PyCharm Professional"
        override val code = "PCP"
        override val launcherExecutable = "pycharm"
        override val licenseTier = LicenseTier.Paid
        override val installedProductCode = "PY"
        // HEAD evidence: current Professional releases use unprefixed pycharm-*; older releases used pycharm-professional-*.
        override val urlFilenameTokens = listOf("pycharmPP-", "pycharm-professional-", "pycharm-")
    }

    /** PyCharm Community (free, open source). */
    data object PyCharmCommunity : IdeProduct {
        override val id = "pycharm-community"
        override val displayName = "PyCharm Community"
        override val code = "PCC"
        override val launcherExecutable = "pycharm"
        override val licenseTier = LicenseTier.Free
        override val installedProductCode = "PC"
        // HEAD evidence: Community binaries use pycharm-community-*; accept pycharmPC-* for older API naming.
        override val urlFilenameTokens = listOf("pycharmPC-", "pycharm-community-")
    }

    // ---- Free-for-non-commercial IDEs ----

    data object GoLand : IdeProduct {
        override val id = "goland"
        override val displayName = "GoLand"
        override val code = "GO"
        override val launcherExecutable = "goland"
        override val licenseTier = LicenseTier.FreeForNonCommercial
        // HEAD evidence: GoLand archives use goland-*.
        override val urlFilenameTokens = listOf("goland-")
    }

    data object WebStorm : IdeProduct {
        override val id = "webstorm"
        override val displayName = "WebStorm"
        override val code = "WS"
        override val launcherExecutable = "webstorm"
        override val licenseTier = LicenseTier.FreeForNonCommercial
        // HEAD evidence: WebStorm archives use case-sensitive WebStorm-* filenames.
        override val urlFilenameTokens = listOf("WebStorm-")
    }

    data object Rider : IdeProduct {
        override val id = "rider"
        override val displayName = "Rider"
        override val code = "RD"
        override val launcherExecutable = "rider"
        override val licenseTier = LicenseTier.FreeForNonCommercial
        // HEAD evidence: Rider archives use JetBrains.Rider-*; keep rider-* for older lowercase feed entries.
        override val urlFilenameTokens = listOf("JetBrains.Rider-", "rider-")
    }

    data object CLion : IdeProduct {
        override val id = "clion"
        override val displayName = "CLion"
        override val code = "CL"
        override val launcherExecutable = "clion"
        override val licenseTier = LicenseTier.FreeForNonCommercial
        // HEAD evidence: CLion archives use case-sensitive CLion-* filenames.
        override val urlFilenameTokens = listOf("CLion-")
    }

    /**
     * RustRover. The products feed serves only the `release` and `eap` channels for `RR` — there is
     * no `rc` stream — which both [IdeChannel] values already cover.
     */
    data object RustRover : IdeProduct {
        override val id = "rustrover"
        override val displayName = "RustRover"
        override val code = "RR"
        override val launcherExecutable = "rustrover"
        override val licenseTier = LicenseTier.FreeForNonCommercial
        // HEAD evidence: RustRover archives use case-sensitive RustRover-* filenames.
        override val urlFilenameTokens = listOf("RustRover-")
    }

    // ---- Paid IDEs ----

    data object PhpStorm : IdeProduct {
        override val id = "phpstorm"
        override val displayName = "PhpStorm"
        override val code = "PS"
        override val launcherExecutable = "phpstorm"
        override val licenseTier = LicenseTier.Paid
        // HEAD evidence: PhpStorm archives use case-sensitive PhpStorm-* filenames (served under /webide/).
        override val urlFilenameTokens = listOf("PhpStorm-")
    }

    data object RubyMine : IdeProduct {
        override val id = "rubymine"
        override val displayName = "RubyMine"
        override val code = "RM"
        override val launcherExecutable = "rubymine"
        override val licenseTier = LicenseTier.Paid
        // HEAD evidence: RubyMine archives use case-sensitive RubyMine-* filenames (served under /ruby/).
        override val urlFilenameTokens = listOf("RubyMine-")
    }

    /**
     * DataGrip. **Two different codes are in play, deliberately:** the products API is queried with
     * `DG` (`alternativeCodes: ["DB"]`), while the feed's `intellijProductCode` — and therefore the
     * `productCode` a real install writes into `product-info.json` — is `DB`. Same split as
     * `IIU`→`IU` and `PCP`→`PY`, so [installedProductCode] carries `DB` and validation passes.
     * `DB` is also the spelling `prompts/AGENTS.md` uses, so the alias map accepts both.
     */
    data object DataGrip : IdeProduct {
        override val id = "datagrip"
        override val displayName = "DataGrip"
        override val code = "DG"
        override val launcherExecutable = "datagrip"
        override val licenseTier = LicenseTier.Paid
        override val installedProductCode = "DB"
        // HEAD evidence: DataGrip archives use LOWERCASE datagrip-* filenames, unlike its sibling IDEs.
        override val urlFilenameTokens = listOf("datagrip-")
    }

    // ---- Free IDE ----

    /**
     * MPS (Apache 2.0, free). Its feed entry is the odd one in the catalog:
     *
     *  - **no `linuxARM64` and no `windowsARM64` distribution** in any release, so those two hosts
     *    are simply not published; the resolver reports that explicitly instead of suggesting
     *    `--version` (see `unpublishedPlatformFailureMessage`).
     *  - it additionally offers a cross-platform `zip` that the OS/arch → download-key mapping does
     *    not use. We deliberately do NOT fall back to it: that archive carries no per-host JBR, so
     *    "resolved" would mean an install that cannot launch — a worse outcome than a clear error.
     *  - `mac` / `macM1` are `.dmg`, same as every other JetBrains IDE, and `unpackIdeArchive`
     *    already mounts those via `hdiutil` on a macOS host.
     */
    data object Mps : IdeProduct {
        override val id = "mps"
        override val displayName = "MPS"
        override val code = "MPS"
        override val launcherExecutable = "mps"
        override val licenseTier = LicenseTier.Free
        // HEAD evidence: MPS archives use case-sensitive MPS-* filenames (mac adds a -macos infix).
        override val urlFilenameTokens = listOf("MPS-")
    }

    // ---- Google-published IDE ----

    /**
     * Android Studio (Google). Free for all uses. NOT served by the JetBrains products API —
     * resolution scrapes Google's official `developer.android.com/studio` page; see
     * [resolveAndroidStudioArchiveUrl].
     */
    data object AndroidStudio : IdeProduct {
        override val id = "android-studio"
        override val displayName = "Android Studio"
        override val code = "AI" // matches Google's updates.xml product code
        override val launcherExecutable = "studio"
        override val licenseTier = LicenseTier.Free
    }

    /**
     * Catch-all for any JetBrains product not in the well-known list. The caller
     * supplies the public product `code` (e.g. "DS" for DataSpell, or "AC") and a license
     * tier so paid/free policies still work. Everything else is descriptive metadata.
     *
     * This is the escape hatch for the products listed as deliberately out of scope next to
     * [knownProducts] — reaching one does not require editing the catalog.
     */
    data class Custom(
        override val id: String,
        override val displayName: String,
        override val code: String,
        override val launcherExecutable: String,
        override val licenseTier: LicenseTier,
    ) : IdeProduct

    companion object {
        // Lazy-initialized to avoid a class-init cycle: data-object references to
        // IdeProduct constants trigger the Companion's <clinit>, which in turn would
        // touch those same data objects mid-init and pick up null entries.
        /**
         * All hard-coded products this module ships aliases for.
         *
         * ### Products we deliberately do NOT ship (not an oversight — do not "fix" this)
         *
         * The scope boundary for a devrig backend is: an IntelliJ-platform IDE that the
         * products feed publishes as a self-contained desktop distribution, and that we can
         * validate end to end. The following were evaluated and rejected on purpose:
         *
         *  - **DataSpell (`DS`)** — deliberately NOT added. In scope technically, left out by
         *    decision; revisit only on an explicit request, not as catalog cleanup.
         *  - **GitClient (`GIG`)** — deliberately NOT added. Same: an explicit decision, not a gap.
         *  - **CLion Nova (`CLN`)** — deliberately NOT added because it is dead: the feed reports
         *    `alternativeCodes: [CL]` and `intellijProductCode: CL`, i.e. Nova was folded back into
         *    regular CLion. It is already covered by [CLion] (`CL`); a separate entry would be a
         *    duplicate of the same artifacts.
         *  - **Gateway (`GW`)** — a thin remote-development client, not a backend IDE.
         *  - **JetBrains Air / Fleet** — not IntelliJ-platform desktop IDE distributions.
         *  - **AppCode (`AC`)** — discontinued by JetBrains.
         *  - **PhpStorm Light** — a stripped SKU of the [PhpStorm] entry we already ship.
         *  - **Aqua** — test-automation IDE, out of the agreed backend scope.
         *  - **Writerside / Qodana** — not IDEs (authoring tool / static-analysis platform).
         *
         * Anything genuinely missing can still be reached through [Custom] without touching
         * this list.
         */
        val knownProducts: List<IdeProduct> by lazy {
            listOf(
                IntelliJIdea,
                IntelliJIdeaCommunity,
                PyCharm,
                PyCharmCommunity,
                GoLand,
                WebStorm,
                Rider,
                CLion,
                RustRover,
                PhpStorm,
                RubyMine,
                DataGrip,
                Mps,
                AndroidStudio,
            )
        }

        private val knownByAlias: Map<String, IdeProduct> by lazy {
            buildMap {
                for (p in knownProducts) {
                    put(p.id.lowercase(), p)
                    put(p.code.lowercase(), p)
                }
                put("idea", IntelliJIdea)
                put("intellij", IntelliJIdea)
                put("intellijidea", IntelliJIdea)
                put("intellijideaultimate", IntelliJIdea)
                put("idea-ultimate", IntelliJIdea)
                put("ultimate", IntelliJIdea)
                put("ic", IntelliJIdeaCommunity)
                put("community", IntelliJIdeaCommunity)
                put("idea-ce", IntelliJIdeaCommunity)
                put("ideac", IntelliJIdeaCommunity)
                put("pycharm", PyCharm)
                put("python", PyCharm)
                put("pycharm-professional", PyCharm)
                put("pycharm-pro", PyCharm)
                put("pcp", PyCharm)
                put("pycharm-ce", PyCharmCommunity)
                put("pycharmc", PyCharmCommunity)
                put("pc", PyCharmCommunity)
                put("dotnet", Rider)
                put("rd", Rider)
                put("go", GoLand)
                put("ws", WebStorm)
                put("cl", CLion)
                put("php", PhpStorm)
                put("ruby", RubyMine)
                put("rust", RustRover)
                // DataGrip answers to both spellings on purpose: `DG` is the products-API code we
                // query with, `DB` is its alternativeCode / intellijProductCode and the spelling
                // prompts/AGENTS.md already uses. Accepting both avoids a third spelling.
                put("db", DataGrip)
                put("ai", AndroidStudio)
                put("studio", AndroidStudio)
                put("androidstudio", AndroidStudio)
                put("android", AndroidStudio)
            }
        }

        /**
         * Resolves a known product by id / code / alias. To use an unknown product code,
         * construct [Custom] explicitly so the license tier is set deliberately.
         */
        fun fromString(rawValue: String): IdeProduct {
            val normalized = rawValue.trim().lowercase()
            return knownByAlias[normalized]
                ?: error(
                    "Unsupported IDE product '$rawValue'. " +
                        "Known: ${knownProducts.joinToString { it.id }}. " +
                        "For other JetBrains products construct IdeProduct.Custom(code = …) directly."
                )
        }
    }
}
