/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `IdeProduct.knownProducts` is the single source of truth for the product catalog, but two
 * user-facing surfaces restate it in prose: the published `devrig backend download` docs and the
 * standalone downloader's `--product` help. Both used to drift silently — #430 shipped nine
 * products while the README promised "any IntelliJ-based IDE".
 *
 * This test fails when either surface stops listing every catalog id, so adding a product to the
 * catalog forces the docs edit in the same commit.
 */
class ProductCatalogDocsDriftTest {

    @Test
    fun `published devrig docs list every catalog product id`() {
        val docs = repoFile("website/content/docs/devrig.md")
        val text = docs.readText()
        val missing = IdeProduct.knownProducts.map { it.id }.filterNot { text.contains("`$it`") }
        assertTrue(
            "${docs.path} does not list ${missing} as `<id>` — the docs' \"Known product ids\" line " +
                "must cover every IdeProduct.knownProducts entry",
            missing.isEmpty(),
        )
    }

    @Test
    fun `standalone downloader --product help lists every catalog product id`() {
        val main = repoFile("intellij-downloader/src/main/kotlin/com/jonnyzzz/mcpSteroid/ideDownloader/Main.kt")
        // The help text lives in the file's KDoc; matching the whole file is enough to catch drift
        // without pinning the exact wrapping.
        val text = main.readText()
        val missing = IdeProduct.knownProducts.map { it.id }.filterNot { text.contains(it) }
        assertTrue(
            "${main.path} --product help does not mention $missing",
            missing.isEmpty(),
        )
    }

    /**
     * Hard requirements, not assumptions: the Gradle test task always sets `mcp.repo.root`
     * (see `intellij-downloader/build.gradle.kts`), and a missing docs file is exactly the drift
     * this guard exists to catch — skipping would let the docs vanish silently.
     */
    private fun repoFile(relativePath: String): File {
        val rootPath = System.getProperty("mcp.repo.root")
            ?: error("mcp.repo.root system property is not set — the :intellij-downloader:test Gradle task always sets it")
        val root = File(rootPath)
        assertTrue("mcp.repo.root=$rootPath is not a directory", root.isDirectory)
        val file = File(root, relativePath)
        assertTrue("$relativePath not found under $root — the docs drift guard must see the published docs", file.isFile)
        return file
    }
}
