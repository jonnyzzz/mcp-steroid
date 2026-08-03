/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs as DownloaderHostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel as DownloaderChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeDistribution as DownloaderDistribution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct as DownloaderProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndDownload
import java.io.File

enum class IdeChannel(val apiValue: String) {
    STABLE("release"),
    EAP("eap"),
}

sealed class IdeDistribution {
    abstract val product: IdeProduct

    data class Latest(
        override val product: IdeProduct = IdeProduct.IntelliJIdea,
        val channel: IdeChannel = IdeChannel.STABLE,
        /**
         * Optional version filter passed to the resolver — e.g. `"2026.1"` picks the latest 2026.1.x
         * (platform 261) build on the channel. Null means "newest on the channel".
         */
        val version: String? = null,
    ) : IdeDistribution()

    data class FromUrl(
        override val product: IdeProduct,
        val url: String,
        val fileName: String? = null,
    ) : IdeDistribution()

    companion object {
        fun fromSystemProperties(): IdeDistribution {
            val productRaw = System.getProperty("test.integration.ide.product", "idea")
            val channelRaw = System.getProperty("test.integration.ide.channel", "stable").trim().lowercase()
            val product = IdeProduct.fromSystemProperty(productRaw)
            val channel = when (channelRaw) {
                "stable", "release" -> IdeChannel.STABLE
                "eap" -> IdeChannel.EAP
                else -> error("Unknown channel '$channelRaw'. Use 'stable' or 'eap'.")
            }
            return Latest(product = product, channel = channel)
        }
    }
}

fun IdeDistribution.resolveAndDownload(): File {
    val downloaderDist = toDownloaderDistribution()
    // Always download the Linux archive — IDE containers are always Linux regardless of host OS.
    return downloaderDist.resolveAndDownload(IdeTestFolders.ideDownloadDir, DownloaderHostOs.LINUX)
}

private fun IdeDistribution.toDownloaderDistribution(): DownloaderDistribution {
    return when (this) {
        is IdeDistribution.FromUrl -> DownloaderDistribution.FromUrl(
            product = product.toDownloaderProduct(),
            url = url,
            fileName = fileName,
        )
        is IdeDistribution.Latest -> DownloaderDistribution.Latest(
            product = product.toDownloaderProduct(),
            channel = channel.toDownloaderChannel(),
            version = version,
        )
    }
}

private fun IdeProduct.toDownloaderProduct(): DownloaderProduct = when (this) {
    IdeProduct.IntelliJIdea -> DownloaderProduct.IntelliJIdea
    IdeProduct.PyCharm -> DownloaderProduct.PyCharm
    IdeProduct.GoLand -> DownloaderProduct.GoLand
    IdeProduct.WebStorm -> DownloaderProduct.WebStorm
    IdeProduct.Rider -> DownloaderProduct.Rider
    IdeProduct.CLion -> DownloaderProduct.CLion
    IdeProduct.PhpStorm -> DownloaderProduct.PhpStorm
    IdeProduct.RubyMine -> DownloaderProduct.RubyMine
    IdeProduct.RustRover -> DownloaderProduct.RustRover
    IdeProduct.DataGrip -> DownloaderProduct.DataGrip
    IdeProduct.Mps -> DownloaderProduct.Mps
    IdeProduct.AndroidStudio -> DownloaderProduct.AndroidStudio
}

private fun IdeChannel.toDownloaderChannel(): DownloaderChannel = when (this) {
    IdeChannel.STABLE -> DownloaderChannel.STABLE
    IdeChannel.EAP -> DownloaderChannel.EAP
}
