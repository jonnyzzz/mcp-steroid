/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Recorded shapes of the three feeds `devrig backend download` reads, trimmed to the fields the
 * resolvers actually consume. Shared by the offline all-products download test and the live-feed
 * test, so both describe the same contract.
 */

/** One release of one product, as `data.services.jetbrains.com/products?code=…` serves it. */
fun productsApiPayload(
    product: IdeProduct,
    version: String,
    build: String,
    fileName: String,
    date: String = "2026-07-23",
): String = """
    [
      {
        "code": "${product.code}",
        "name": "${product.displayName}",
        "releases": [
          {
            "date": "$date",
            "type": "release",
            "version": "$version",
            "build": "$build",
            "downloads": {
              "macM1": {
                "link": "https://download.jetbrains.com/product/$fileName",
                "checksumLink": "https://download.jetbrains.com/product/$fileName.sha256"
              }
            }
          }
        ]
      }
    ]
""".trimIndent()

/** A `JetBrains/intellij-community` releases page holding a single `<tag>/<version>` release. */
fun githubCommunityReleasesJson(
    product: IdeProduct,
    version: String,
    fileName: String,
    publishedAt: String = "2026-07-16T13:32:03Z",
): String {
    val tag = when (product) {
        IdeProduct.IntelliJIdeaCommunity -> "idea"
        IdeProduct.PyCharmCommunity -> "pycharm"
        else -> error("${product.id} is not served by the GitHub Community feed")
    }
    return """
        [
          {
            "tag_name": "$tag/$version",
            "prerelease": false,
            "published_at": "$publishedAt",
            "assets": [
              {"name": "$fileName", "browser_download_url": "https://github.com/JetBrains/intellij-community/releases/download/$tag/$version/$fileName"}
            ]
          }
        ]
    """.trimIndent()
}

/** The `developer.android.com/studio/preview` download links for one preview build. */
fun androidStudioPreviewHtml(version: String, macArmFileName: String): String = """
    <table>
      <a href="https://edgedl.me.gvt1.com/android/studio/install/$version/${macArmFileName.replace("-mac_arm.dmg", "-windows.exe")}">win</a>
      <a href="https://edgedl.me.gvt1.com/android/studio/install/$version/$macArmFileName">mac arm</a>
      <a href="https://edgedl.me.gvt1.com/android/studio/install/$version/${macArmFileName.replace("-mac_arm.dmg", "-mac.dmg")}">mac intel</a>
      <a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/$version/${macArmFileName.replace("-mac_arm.dmg", "-linux.tar.gz")}">linux</a>
    </table>
""".trimIndent()

/** A minimal `ij-plugin.zip` — [BackendManager.download] deploys it after a successful install. */
fun pluginZipFixture(zip: Path): Path {
    if (Files.isRegularFile(zip)) return zip
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

class FixtureBundledPluginResolver(private val zip: Path) : BundledPluginResolver {
    override fun resolveBundledPluginZip(): Path = pluginZipFixture(zip)
}
