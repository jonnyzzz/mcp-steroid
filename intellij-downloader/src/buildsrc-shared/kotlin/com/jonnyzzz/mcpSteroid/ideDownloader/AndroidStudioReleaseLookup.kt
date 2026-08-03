/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.slf4j.LoggerFactory

private val androidStudioReleaseLookupLog =
    LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.AndroidStudioReleaseLookup")

/**
 * Resolves the latest stable Android Studio archive URL for ([os], [architecture]).
 *
 * Android Studio is a Google product and is NOT served by the JetBrains products API.
 * The most reliable public source is the official download page at
 * `https://developer.android.com/studio`, which lists the current direct-download URLs
 * for all supported platforms (hosted on Google's `edgedl.me.gvt1.com` CDN).
 *
 * URL filenames as they currently appear on the page (May 2026, "Panda 4 | 2025.3.4 Patch 1"):
 *
 *  | Platform | Filename suffix |
 *  |---|---|
 *  | Linux x86_64 | `-linux.tar.gz` |
 *  | macOS Intel | `-mac.dmg` |
 *  | macOS Apple Silicon | `-mac_arm.dmg` |
 *  | Windows x86_64 (installer) | `-windows.exe` |
 *  | Windows x86_64 (zip) | `-windows.zip` |
 *
 * Android Studio does NOT publish Linux ARM64 or Windows ARM64 builds. Those combos throw
 * with a clear message so callers can pick a different IDE / architecture.
 *
 * The page can advertise more than one version at a time (a fresh release next to the previous
 * patch), so the release is picked by version number rather than by HTML order — see
 * [resolveAndroidStudioPageDownload].
 *
 * Channels other than stable (canary, beta) live on a separate page and are not yet supported.
 *
 * @param channel only `STABLE` is supported (Android Studio's `EAP` channel = canary, served
 *  from a different page; not yet implemented).
 */
fun resolveAndroidStudioArchiveUrl(
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
): String {
    return resolveAndroidStudioArchive(channel, os, architecture, version = null).url
}

fun resolveAndroidStudioArchive(
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    version: String?,
): IdeArchiveResolution = resolveAndroidStudioArchiveWithUrlReader(
    channel = channel,
    os = os,
    architecture = architecture,
    version = version,
    urlReader = { url -> readUrlText(url, accept = "text/html,*/*") },
)

internal fun resolveAndroidStudioArchiveWithUrlReader(
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    version: String?,
    urlReader: (String) -> String,
): IdeArchiveResolution {
    require(channel == IdeChannel.STABLE) {
        "Android Studio: only IdeChannel.STABLE is supported by this downloader; got $channel. " +
            "Canary / Beta live on a separate Google page and aren't wired up yet."
    }

    val pageUrl = "https://developer.android.com/studio"
    logFetchingAndroidStudioDownloads(pageUrl)
    val html = urlReader(pageUrl)
    return resolveAndroidStudioArchiveFromHtml(channel, os, architecture, version, pageUrl, html)
}

internal fun resolveAndroidStudioArchiveFromHtml(
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    version: String?,
    pageUrl: String,
    html: String,
): IdeArchiveResolution {
    // Each download is an absolute https URL into edgedl.me.gvt1.com/android/studio/...
    // We pull every match out of the page and pick the artifact by suffix — that's stable
    // across the marketing-name segment in the filename ("panda4-patch1"), which we can't
    // derive from the updates.xml alone. Which RELEASE the artifact belongs to is decided by
    // version number, not by where Google placed the link on the page.
    val wantedSuffix = when (os) {
        HostOs.LINUX -> {
            require(!architecture.isArmArch) {
                "Android Studio does not publish a Linux ARM64 build (only x86_64 .tar.gz). " +
                    "Pick another product or architecture."
            }
            "-linux.tar.gz"
        }
        HostOs.MAC -> if (architecture.isArmArch) "-mac_arm.dmg" else "-mac.dmg"
        HostOs.WINDOWS -> {
            require(!architecture.isArmArch) {
                "Android Studio does not publish a Windows ARM64 build. " +
                    "Pick x86_64 or another product."
            }
            "-windows.exe"
        }
    }

    val download = resolveAndroidStudioPageDownload(
        html = html,
        pageUrl = pageUrl,
        assetSuffix = wantedSuffix,
        selector = version,
    )
    val checksumsByFileName = androidStudioChecksumsByFileName(html)

    return IdeArchiveResolution(
        product = IdeProduct.AndroidStudio,
        channel = channel,
        version = download.release.version,
        build = download.release.version,
        url = download.url,
        downloadKey = wantedSuffix.removePrefix("-").removeSuffix(".tar.gz").removeSuffix(".dmg")
            .removeSuffix(".zip").removeSuffix(".exe"),
        expectedSha256 = checksumsByFileName[downloadFilenameFromUrl(download.url)],
    )
}

internal fun logFetchingAndroidStudioDownloads(pageUrl: String) {
    androidStudioReleaseLookupLog.debug("[IDE-DOWNLOAD] Fetching Android Studio downloads from {}", pageUrl)
}

internal fun androidStudioChecksumsByFileName(html: String): Map<String, String> {
    val rowRegex = Regex(
        """<tr\b[^>]*>.*?<button\b[^>]*>\s*([^<]*android-studio[^<]*\.(?:zip|tar\.gz|dmg|exe))\s*</button>.*?<td>\s*([0-9a-fA-F]{64})\s*</td>.*?</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    return rowRegex.findAll(html).associate { match ->
        match.groupValues[1].trim() to match.groupValues[2].lowercase()
    }
}

internal fun inferAndroidStudioVersion(url: String): String =
    androidStudioVersionFromDownloadUrl(url)
        ?: error("Could not infer Android Studio version from download URL: $url")
