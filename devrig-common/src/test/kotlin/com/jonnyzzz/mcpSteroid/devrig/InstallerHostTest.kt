/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the published-installer contract: the URLs both halves of the product fetch, and the one-liner
 * every user-facing surface (website install CTA, README, the IDE settings page) shows VERBATIM.
 */
class InstallerHostTest {

    @Test
    fun `the installer URLs are the published ones`() {
        assertEquals("https://devrig.dev/install.sh", devrigInstallerUrl(isWin = false))
        assertEquals("https://devrig.dev/install.ps1", devrigInstallerUrl(isWin = true))
    }

    /**
     * The exact strings `website/layouts/partials/install-cta.html` publishes and the installer
     * templates carry in their headers. A drift here means the IDE settings page shows a command the
     * docs never promoted — change the website and the templates together with this pin, or not at all.
     */
    @Test
    fun `the install one-liner matches the website, verbatim, per OS`() {
        assertEquals("curl -fsSL https://devrig.dev/install.sh | sh", devrigInstallOneLiner(isWin = false))
        assertEquals("irm https://devrig.dev/install.ps1 | iex", devrigInstallOneLiner(isWin = true))
    }

    /**
     * The mechanical half of the verbatim pin: the literals above say what the strings ARE, this reads
     * the published sources and proves they still SAY it — so a website-only or template-only edit
     * (say, `| sh` → `| bash`) fails here instead of drifting silently past the settings page. Same
     * lint-test pattern as `BuildScriptIncrementalInputsTest` (walk up to the repo root, read the
     * source file); the files are checked into this repo, so a missing one is a real breakage, not a
     * condition to skip on.
     */
    @Test
    fun `the published website CTA and installer templates carry the same one-liners`() {
        val posix = devrigInstallOneLiner(isWin = false)
        val windows = devrigInstallOneLiner(isWin = true)
        val published = mapOf(
            "README.md" to listOf(posix, windows),
            "website/layouts/partials/install-cta.html" to listOf(posix, windows),
            "installer-gen/src/main/resources/templates/install.sh.tmpl" to listOf(posix),
            "installer-gen/src/main/resources/templates/install.ps1.tmpl" to listOf(windows),
        )
        for ((relativePath, oneLiners) in published) {
            val file = repoRoot().resolve(relativePath)
            assertTrue(Files.isRegularFile(file), "published install source is missing: $file")
            val text = Files.readString(file)
            for (oneLiner in oneLiners) {
                assertTrue(
                    text.contains(oneLiner),
                    "$relativePath no longer carries the one-liner `$oneLiner` verbatim — " +
                        "change devrigInstallOneLiner and the published sources together, or not at all",
                )
            }
        }
    }

    private fun repoRoot(): Path {
        var dir = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
            dir = dir.parent ?: error("repo root (settings.gradle.kts) not found above ${Path.of("").toAbsolutePath()}")
        }
        return dir
    }
}
