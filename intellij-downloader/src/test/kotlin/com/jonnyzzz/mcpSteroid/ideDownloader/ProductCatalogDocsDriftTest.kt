/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    private val repoRoot: File?
        get() = System.getProperty("mcp.repo.root")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `published devrig docs list every catalog product id`() {
        val docs = repoFile("website/content/docs/devrig.md") ?: return
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
            ?: return
        // The help text lives in the file's KDoc; matching the whole file is enough to catch drift
        // without pinning the exact wrapping.
        val text = main.readText()
        val missing = IdeProduct.knownProducts.map { it.id }.filterNot { text.contains(it) }
        assertTrue(
            "${main.path} --product help does not mention $missing",
            missing.isEmpty(),
        )
    }

    private fun repoFile(relativePath: String): File? {
        val root = repoRoot
        // Keep the suite green where the property is absent (e.g. running the class straight from
        // an IDE) instead of failing for an environment reason.
        assumeTrue("mcp.repo.root is not set — skipping docs drift check", root != null)
        val file = File(root, relativePath)
        assumeTrue("$relativePath not found under $root — skipping docs drift check", file.isFile)
        return file
    }
}
