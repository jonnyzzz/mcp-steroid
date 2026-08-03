/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeArchiveResolution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndroidStudioPageDownload
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Android Studio's STABLE channel lags the IntelliJ platform: the current stable (codename "Panda",
 * 2025.3) is build 253, which the bundled plugin (since-build 261) cannot load. The matching 261
 * platform ships only on the canary/preview channel (codename "Quail", 2026.1.x). So devrig resolves
 * Android Studio from the preview page, where the build is plugin-compatible.
 */
const val ANDROID_STUDIO_PREVIEW_PAGE: String = "https://developer.android.com/studio/preview"

private fun androidStudioPreviewSuffix(os: HostOs, architecture: HostArchitecture): String = when (os) {
    HostOs.LINUX -> {
        require(!architecture.isArmArch) { "Android Studio publishes no Linux ARM64 build; pick x86_64 or another product." }
        "-linux.tar.gz"
    }
    HostOs.MAC -> if (architecture.isArmArch) "-mac_arm.dmg" else "-mac.dmg"
    HostOs.WINDOWS -> {
        require(!architecture.isArmArch) { "Android Studio publishes no Windows ARM64 build; pick x86_64 or another product." }
        "-windows.exe"
    }
}

/**
 * Resolves the Android Studio canary archive from the preview page HTML (pure — no I/O, so it is
 * unit testable). The download URLs are absolute `edgedl.me.gvt1.com` links picked by OS/arch suffix
 * (stable across the codename segment, e.g. `quail2-canary3`); the build is the platform baseline.
 *
 * The preview page publishes SEVERAL generations at once — `2026.1.4.3` (Quail 4 Canary 3) next to
 * the older `2026.1.3.6` (Quail 3 RC 2) — in an order Google is free to change. All of them are
 * parsed and the newest wins ([resolveAndroidStudioPageDownload]); [version] pins a specific one by
 * exact version (`2026.1.4.3`), version prefix (`2026.1`), platform baseline (`261`) or channel
 * (`canary`, `rc`, `beta`).
 */
fun resolveAndroidStudioCanaryArchiveFromHtml(
    html: String,
    os: HostOs,
    architecture: HostArchitecture,
    version: String? = null,
): IdeArchiveResolution {
    val download = resolveAndroidStudioPageDownload(
        html = html,
        pageUrl = ANDROID_STUDIO_PREVIEW_PAGE,
        assetSuffix = androidStudioPreviewSuffix(os, architecture),
        selector = version,
    )
    val resolvedVersion = download.release.version
    val baseline = download.release.platformBaseline

    return IdeArchiveResolution(
        product = IdeProduct.AndroidStudio,
        channel = IdeChannel.STABLE,
        version = resolvedVersion,
        build = baseline?.toString() ?: resolvedVersion,
        // The preview page only exposes the marketing version, so the build is the platform baseline
        // (`262`) while the installed artifact reports the full `262.8665.258.2621.14049965`.
        buildIsBaseline = baseline != null,
        url = download.url,
        downloadKey = download.url.substringAfterLast('/'),
    )
}

/** Blocking fetch of the Android Studio preview page. Call on [kotlinx.coroutines.Dispatchers.IO]. */
fun fetchAndroidStudioPreviewHtml(): String {
    val connection = (URI(ANDROID_STUDIO_PREVIEW_PAGE).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "text/html,*/*")
        setRequestProperty("User-Agent", "devrig")
    }
    try {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IOException("Failed to fetch $ANDROID_STUDIO_PREVIEW_PAGE. HTTP $status\n${body.take(300)}")
        }
        return body
    } finally {
        connection.disconnect()
    }
}

/** Resolves the Android Studio canary archive. Blocking; call on [kotlinx.coroutines.Dispatchers.IO]. */
fun resolveAndroidStudioCanaryArchive(
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
    fetch: () -> String = ::fetchAndroidStudioPreviewHtml,
): IdeArchiveResolution =
    resolveAndroidStudioCanaryArchiveFromHtml(fetch(), os, architecture, version)
