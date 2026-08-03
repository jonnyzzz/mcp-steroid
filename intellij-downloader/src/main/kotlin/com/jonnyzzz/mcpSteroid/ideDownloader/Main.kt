/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.slf4j.LoggerFactory
import java.io.File

private val ideDownloaderMainLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.Main")

/**
 * CLI entry point for downloading and optionally unpacking IDE archives.
 *
 * Usage:
 *   java -jar intellij-downloader.jar --product idea-community --channel stable --output-dir /path/to/dir
 *
 * Arguments:
 *   --product            IDE product: idea-ultimate, idea-community, pycharm-pro,
 *                        pycharm-community, goland, webstorm, rider, clion, rustrover,
 *                        phpstorm, rubymine, datagrip, mps, android-studio
 *                        (default: idea-ultimate; legacy aliases idea/pycharm accepted,
 *                        datagrip also answers to db)
 *   --channel            Release channel: stable, eap (default: stable)
 *   --output-dir         Directory to store downloaded archives (required)
 *   --url                Direct download URL (overrides --product/--channel resolution)
 *   --os                 Target OS: linux, mac, windows (default: auto-detected)
 *   --unpack-dir         Directory to unpack the archive into (optional). Dispatches by
 *                        archive extension: .tar.gz / .tgz / .zip / .dmg (mac host only) / .exe
 */
fun main(args: Array<String>) {
    val argsMap = parseArgs(args)
    val outputDir = File(argsMap["--output-dir"] ?: error("--output-dir is required"))
    val url = argsMap["--url"]

    val os = argsMap["--os"]?.let { raw ->
        when (raw.trim().lowercase()) {
            "linux" -> HostOs.LINUX
            "mac", "macos" -> HostOs.MAC
            "windows", "win" -> HostOs.WINDOWS
            else -> error("Unknown OS '$raw'. Use 'linux', 'mac', or 'windows'.")
        }
    } ?: resolveHostOs()

    val distribution = if (url != null) {
        val productRaw = argsMap["--product"] ?: "idea-ultimate"
        val product = IdeProduct.fromString(productRaw)
        IdeDistribution.FromUrl(product = product, url = url)
    } else {
        val productRaw = argsMap["--product"] ?: "idea-ultimate"
        val channelRaw = argsMap["--channel"] ?: "stable"
        val product = IdeProduct.fromString(productRaw)
        val channel = when (channelRaw.trim().lowercase()) {
            "stable", "release" -> IdeChannel.STABLE
            "eap" -> IdeChannel.EAP
            else -> error("Unknown channel '$channelRaw'. Use 'stable' or 'eap'.")
        }
        IdeDistribution.Latest(product = product, channel = channel)
    }

    val archiveFile = distribution.resolveAndDownload(outputDir, os)
    ideDownloaderMainLog.debug("[IDE-DOWNLOAD] Archive: {}", archiveFile.absolutePath)

    val unpackDir = argsMap["--unpack-dir"]
    if (unpackDir != null) {
        unpackIdeArchive(archiveFile, File(unpackDir))
    }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val knownArgs = setOf("--product", "--channel", "--output-dir", "--url", "--os", "--unpack-dir")
    val result = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val key = args[i]
        require(key.startsWith("--")) { "Expected argument key starting with --, got: $key" }
        require(key in knownArgs) { "Unknown argument: $key" }
        require(i + 1 < args.size) { "Missing value for argument: $key" }
        result[key] = args[i + 1]
        i += 2
    }
    return result
}
