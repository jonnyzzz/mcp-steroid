/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeArchiveResolution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * The genuine open-source Community editions (IntelliJ IDEA Community, PyCharm Community) are NOT
 * served by `data.services.jetbrains.com` for current releases — that feed stops at 2025.3 / build
 * 253. JetBrains publishes the real, current Community distributions (build 261+) as GitHub releases
 * on `JetBrains/intellij-community`, tagged `idea/<version>` and `pycharm/<version>`. This module
 * resolves those, so devrig can offer a plugin-compatible Community IDE.
 */
const val INTELLIJ_COMMUNITY_RELEASES_API: String =
    "https://api.github.com/repos/JetBrains/intellij-community/releases?per_page=100"

/** Community product id -> the release-tag prefix used in the intellij-community repo. */
private val COMMUNITY_REPO_TAG: Map<String, String> = mapOf(
    IdeProduct.IntelliJIdeaCommunity.id to "idea",
    IdeProduct.PyCharmCommunity.id to "pycharm",
)

/** True for the Community editions whose downloads come from GitHub rather than the products API. */
fun isGithubCommunityProduct(product: IdeProduct): Boolean = product.id in COMMUNITY_REPO_TAG

/**
 * Maps a Community marketing version to its IntelliJ platform baseline: `2026.1.2` -> 261,
 * `2025.3` -> 253. GitHub releases do not expose the full build number, but the baseline (which is
 * what plugin compatibility is checked against) is derivable from `YYYY.N`.
 */
fun communityBuildBaseline(version: String): Int? {
    val match = Regex("""^(\d{4})\.(\d+)""").find(version) ?: return null
    return (match.groupValues[1].toInt() % 100) * 10 + match.groupValues[2].toInt()
}

private fun assetMatches(name: String, os: HostOs, architecture: HostArchitecture): Boolean {
    val (armSuffix, x64Suffix) = when (os) {
        HostOs.MAC -> "-aarch64.dmg" to ".dmg"
        HostOs.LINUX -> "-aarch64.tar.gz" to ".tar.gz"
        HostOs.WINDOWS -> "-aarch64.exe" to ".exe"
    }
    return if (architecture.isArmArch) name.endsWith(armSuffix)
    else name.endsWith(x64Suffix) && !name.endsWith(armSuffix)
}

/**
 * Resolves a Community archive from a GitHub releases API payload (pure — no I/O, so it is unit
 * testable). Picks the newest non-prerelease release for the product's tag prefix (or the requested
 * [version]) and the asset matching [os]/[architecture].
 */
fun resolveGithubCommunityArchiveFromReleasesJson(
    payload: String,
    product: IdeProduct,
    os: HostOs,
    architecture: HostArchitecture,
    version: String? = null,
): IdeArchiveResolution {
    val tagPrefix = COMMUNITY_REPO_TAG[product.id]
        ?: error("'${product.id}' is not a GitHub Community product")
    val wanted = version?.takeIf { it.isNotBlank() }

    val releases = Json { ignoreUnknownKeys = true }.parseToJsonElement(payload).jsonArray
        .filterIsInstance<JsonObject>()
        .filter { (it["prerelease"] as? JsonPrimitive)?.content != "true" }
        .mapNotNull { release ->
            val tag = (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!tag.startsWith("$tagPrefix/")) return@mapNotNull null
            CommunityRelease(
                version = tag.substringAfter("$tagPrefix/"),
                publishedAt = (release["published_at"] as? JsonPrimitive)?.contentOrNull?.substringBefore('T'),
                assets = release["assets"] as? JsonArray ?: JsonArray(emptyList()),
            )
        }

    require(releases.isNotEmpty()) {
        "No '$tagPrefix/*' releases found at $INTELLIJ_COMMUNITY_RELEASES_API for ${product.id}"
    }

    val chosen = if (wanted != null) {
        releases.firstOrNull { it.version == wanted }
            ?: error("No '$tagPrefix/$wanted' release at $INTELLIJ_COMMUNITY_RELEASES_API for ${product.id}")
    } else {
        releases.maxWith(Comparator { a, b -> compareBackendVersions(a.version, b.version) })
    }

    val asset = chosen.assets.filterIsInstance<JsonObject>().firstOrNull { asset ->
        val name = (asset["name"] as? JsonPrimitive)?.contentOrNull ?: return@firstOrNull false
        assetMatches(name, os, architecture)
    } ?: error("No ${os}/${architecture} asset for $tagPrefix/${chosen.version} (intellij-community)")

    val name = (asset["name"] as JsonPrimitive).content
    val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull
        ?: error("Release asset $name has no browser_download_url")
    val baseline = communityBuildBaseline(chosen.version)
        ?: error("Cannot derive a platform baseline from Community version '${chosen.version}'")

    return IdeArchiveResolution(
        product = product,
        channel = IdeChannel.STABLE,
        version = chosen.version,
        build = baseline.toString(),
        // GitHub releases carry no full build number, so this is the baseline only: the artifact
        // itself reports `262.8665.258` for baseline `262`.
        buildIsBaseline = true,
        url = url,
        downloadKey = name,
        releaseDate = chosen.publishedAt,
    )
}

private data class CommunityRelease(
    val version: String,
    val publishedAt: String?,
    val assets: JsonArray,
)

/** Blocking fetch of the intellij-community releases JSON. Call on [kotlinx.coroutines.Dispatchers.IO]. */
fun fetchGithubCommunityReleasesJson(): String {
    val connection = (URI(INTELLIJ_COMMUNITY_RELEASES_API).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "devrig")
    }
    try {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IOException("Failed to fetch $INTELLIJ_COMMUNITY_RELEASES_API. HTTP $status\n${body.take(300)}")
        }
        return body
    } finally {
        connection.disconnect()
    }
}

/** Resolves a Community archive from GitHub. Blocking; call on [kotlinx.coroutines.Dispatchers.IO]. */
fun resolveGithubCommunityArchive(
    product: IdeProduct,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
    fetch: () -> String = ::fetchGithubCommunityReleasesJson,
): IdeArchiveResolution =
    resolveGithubCommunityArchiveFromReleasesJson(fetch(), product, os, architecture, version)
