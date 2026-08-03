/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

// Parses a `developer.android.com` download page — `/studio` (stable) or `/studio/preview` (canary,
// beta, RC) — into ALL the releases it advertises, and picks one of them deterministically.
//
// Google serves several generations from the same page: the preview page currently offers
// `2026.1.4.3` (Quail 4 Canary 3) next to the older `2026.1.3.6` (Quail 3 RC 2). Which block comes
// first in the HTML is Google's editorial choice, so taking the first matching download link resolves
// whatever happens to be typed first — correct today, silently the older RC the day Google swaps the
// sections. Selection here reads only the page CONTENT: links are grouped by the version in their
// URL, and the pick is made by comparing version numbers.

/** Release channel of an Android Studio build, as spelled in its download filename. */
enum class AndroidStudioPageChannel(val token: String, val stability: Int) {
    /** No channel segment (`android-studio-quail3-linux.tar.gz`) or a stable patch (`-panda4-patch1-`). */
    RELEASE("release", 3),
    RC("rc", 2),
    BETA("beta", 1),
    CANARY("canary", 0),
}

/** One Android Studio version as published on a download page, with every artifact link it lists. */
data class AndroidStudioPageRelease(
    /** Marketing version taken from the download URL, e.g. `2026.1.4.3`. */
    val version: String,
    val channel: AndroidStudioPageChannel,
    /** Download URLs published for this version, in page order. */
    val urls: List<String>,
) {
    /** The IntelliJ platform baseline this build is made of: `2026.1.4.3` -> 261. */
    val platformBaseline: Int? get() = androidStudioPlatformBaseline(version)

    fun assetEndingWith(suffix: String): String? = urls.firstOrNull { it.endsWith(suffix) }
}

/** The artifact chosen for one host out of an [AndroidStudioPageRelease]. */
data class AndroidStudioPageDownload(
    val release: AndroidStudioPageRelease,
    val url: String,
)

/**
 * Maps an Android Studio marketing version to its IntelliJ platform baseline: `2026.1.2.3` -> 261,
 * `2025.3.4.7` -> 253. Android Studio tracks the platform it is built on as `YYYY.N`.
 */
fun androidStudioPlatformBaseline(version: String): Int? {
    val match = Regex("""^(\d{4})\.(\d+)""").find(version) ?: return null
    return (match.groupValues[1].toInt() % 100) * 10 + match.groupValues[2].toInt()
}

private val androidStudioDownloadUrlRegex =
    Regex("""https://[^"'\s<>]*android-studio[^"'\s<>]*\.(?:zip|tar\.gz|dmg|exe)""")

private val androidStudioUrlPathVersionRegex = Regex("""/(?:install|ide-zips)/(\d+(?:\.\d+)+)/""")

private val androidStudioFileNameVersionRegex = Regex("""android-studio-(\d+(?:\.\d+)+)-""")

private val androidStudioFileNameChannelRegex = Regex("""-(canary|beta|rc)\d*[-.]""", RegexOption.IGNORE_CASE)

/** The marketing version a download URL belongs to, or null when the URL carries no version. */
fun androidStudioVersionFromDownloadUrl(url: String): String? {
    androidStudioUrlPathVersionRegex.find(url)?.let { return it.groupValues[1] }
    return androidStudioFileNameVersionRegex.find(url.substringAfterLast('/'))?.groupValues?.get(1)
}

/** Every Android Studio release advertised on [html], each with all of its download links. */
fun parseAndroidStudioDownloadPage(html: String): List<AndroidStudioPageRelease> {
    val urlsByVersion = LinkedHashMap<String, MutableList<String>>()
    for (match in androidStudioDownloadUrlRegex.findAll(html)) {
        val url = match.value
        val version = androidStudioVersionFromDownloadUrl(url) ?: continue
        val urls = urlsByVersion.getOrPut(version) { mutableListOf() }
        if (url !in urls) urls += url
    }
    return urlsByVersion.map { (version, urls) ->
        AndroidStudioPageRelease(version, androidStudioChannelOf(urls), urls)
    }
}

/**
 * Compares two dotted numeric Android Studio versions segment by segment, so `2026.1.4.3` beats
 * `2026.1.3.6` (a plain string compare would not). Missing segments count as `0`: `2026.1` < `2026.1.1`.
 */
fun compareAndroidStudioVersions(left: String, right: String): Int {
    val leftSegments = left.split('.')
    val rightSegments = right.split('.')
    for (index in 0 until maxOf(leftSegments.size, rightSegments.size)) {
        val leftSegment = leftSegments.getOrNull(index)?.toIntOrNull() ?: 0
        val rightSegment = rightSegments.getOrNull(index)?.toIntOrNull() ?: 0
        if (leftSegment != rightSegment) return leftSegment.compareTo(rightSegment)
    }
    return 0
}

/**
 * Newest first. The marketing version decides; a tie falls back to the more stable channel (an RC
 * before the canary of the same version), then to the raw version text — so the order is a total
 * function of the page content and never of the order Google listed the blocks in.
 */
private val androidStudioNewestFirst: Comparator<AndroidStudioPageRelease> = Comparator { left, right ->
    compareAndroidStudioVersions(right.version, left.version)
        .takeIf { it != 0 }
        ?: (right.channel.stability - left.channel.stability).takeIf { it != 0 }
        ?: right.version.compareTo(left.version)
}

/**
 * The releases matching [selector], newest first. A blank [selector] matches everything. Otherwise it
 * is, in order: the exact marketing version (`2026.1.4.3`), a version prefix on a segment boundary
 * (`2026.1` -> the newest `2026.1.x`), a platform baseline (`261` -> the newest build made of the 261
 * platform), or a channel name (`canary`, `beta`, `rc`, `release`).
 */
fun androidStudioReleasesMatching(
    releases: List<AndroidStudioPageRelease>,
    selector: String?,
): List<AndroidStudioPageRelease> {
    val wanted = selector?.trim()?.takeIf { it.isNotEmpty() }
    val matching = if (wanted == null) releases else releases.filter { it.matchesSelector(wanted) }
    return matching.sortedWith(androidStudioNewestFirst)
}

/**
 * Resolves the artifact ending in [assetSuffix] for [selector] (see [androidStudioReleasesMatching])
 * out of the download page [html]. With no [selector] the newest release that publishes such an
 * artifact wins; the HTML order of the release blocks is never consulted.
 */
fun resolveAndroidStudioPageDownload(
    html: String,
    pageUrl: String,
    assetSuffix: String,
    selector: String?,
): AndroidStudioPageDownload {
    val releases = parseAndroidStudioDownloadPage(html)
    if (releases.isEmpty()) {
        error("Could not find any android-studio download URL on $pageUrl. Page format may have changed.")
    }

    val candidates = androidStudioReleasesMatching(releases, selector)
    if (candidates.isEmpty()) {
        error(
            "Android Studio '$selector' is not published on $pageUrl. " +
                "Available: ${releases.describeReleases()}"
        )
    }

    for (release in candidates) {
        val url = release.assetEndingWith(assetSuffix) ?: continue
        return AndroidStudioPageDownload(release, url)
    }
    error(
        "No Android Studio download URL ending in '$assetSuffix' on $pageUrl " +
            "(searched ${candidates.describeReleases()})"
    )
}

private fun androidStudioChannelOf(urls: List<String>): AndroidStudioPageChannel {
    val token = urls.firstNotNullOfOrNull { url ->
        androidStudioFileNameChannelRegex.find(url.substringAfterLast('/'))?.groupValues?.get(1)
    } ?: return AndroidStudioPageChannel.RELEASE
    return AndroidStudioPageChannel.entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
        ?: AndroidStudioPageChannel.RELEASE
}

private fun AndroidStudioPageRelease.matchesSelector(selector: String): Boolean {
    if (version == selector) return true
    if (version.startsWith("$selector.")) return true
    val baseline = selector.toIntOrNull()
    if (baseline != null) return baseline in 100..999 && platformBaseline == baseline
    return channel.token.equals(selector, ignoreCase = true)
}

private fun List<AndroidStudioPageRelease>.describeReleases(): String =
    sortedWith(androidStudioNewestFirst).joinToString { "${it.version} (${it.channel.token})" }
