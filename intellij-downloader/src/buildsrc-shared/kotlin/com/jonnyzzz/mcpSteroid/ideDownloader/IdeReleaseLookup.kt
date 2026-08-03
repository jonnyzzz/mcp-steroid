/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private val ideReleaseLookupLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.ideDownloader.IdeReleaseLookup")

data class IdeArchiveResolution(
    val product: IdeProduct,
    val channel: IdeChannel,
    val version: String,
    val build: String,
    /**
     * True when [build] is only the platform baseline (`262`) because the feed does not publish the
     * full build number — GitHub Community releases and the Android Studio canary page both resolve
     * that way, while the downloaded artifact reports `262.8665.258`. Consumers must compare such a
     * build as a baseline instead of for exact equality.
     */
    val buildIsBaseline: Boolean = false,
    val url: String,
    val downloadKey: String,
    val releaseDate: String? = null,
    val checksumUrl: String? = null,
    val expectedSha256: String? = null,
)

/**
 * Returns the JetBrains products-API download key for the given OS / architecture combo.
 *
 * @see <a href="https://data.services.jetbrains.com/products">JetBrains Products API</a>
 */
fun resolveDownloadKey(
    os: HostOs,
    architecture: HostArchitecture,
): String = when (os) {
    HostOs.LINUX -> if (architecture.isArmArch) "linuxARM64" else "linux"
    HostOs.MAC -> if (architecture.isArmArch) "macM1" else "mac"
    HostOs.WINDOWS -> if (architecture.isArmArch) "windowsARM64" else "windows"
}

fun resolveArchive(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
    buildPrefix: String? = null,
): IdeArchiveResolution = resolveArchiveWithUrlReader(
    product = product,
    channel = channel,
    os = os,
    architecture = architecture,
    version = version,
    buildPrefix = buildPrefix,
    productsApiReader = { url -> readUrlText(url) },
    androidStudioReader = { url -> readUrlText(url, accept = "text/html,*/*") },
)

internal fun resolveArchiveWithUrlReader(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs = resolveHostOs(),
    architecture: HostArchitecture = resolveHostArchitecture(),
    version: String? = null,
    buildPrefix: String? = null,
    urlReader: (String) -> String,
): IdeArchiveResolution = resolveArchiveWithUrlReader(
    product = product,
    channel = channel,
    os = os,
    architecture = architecture,
    version = version,
    buildPrefix = buildPrefix,
    productsApiReader = urlReader,
    androidStudioReader = urlReader,
)

private fun resolveArchiveWithUrlReader(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    version: String?,
    buildPrefix: String?,
    productsApiReader: (String) -> String,
    androidStudioReader: (String) -> String,
): IdeArchiveResolution {
    // Android Studio is a Google product and lives on a different feed.
    if (product === IdeProduct.AndroidStudio) {
        return resolveAndroidStudioArchiveWithUrlReader(channel, os, architecture, version, androidStudioReader)
    }

    val releaseType = URLEncoder.encode(channel.apiValue, StandardCharsets.UTF_8)
    val url = "https://data.services.jetbrains.com/products?code=${product.code}&release.type=$releaseType"

    logFetchingProductsInfo(url)
    val payload = productsApiReader(url)

    return resolveArchiveFromProductsApiPayload(
        product = product,
        channel = channel,
        os = os,
        architecture = architecture,
        version = version,
        buildPrefix = buildPrefix,
        productsApiUrl = url,
        payload = payload,
    )
}

/**
 * Resolves an archive from a raw `data.services.jetbrains.com/products` payload — pure, no I/O, so
 * callers can exercise the products-API feed from a fixture. The sibling feeds expose the same seam
 * (`resolveGithubCommunityArchiveFromReleasesJson`, `resolveAndroidStudioCanaryArchiveFromHtml`).
 */
fun resolveArchiveFromProductsApiPayload(
    product: IdeProduct,
    channel: IdeChannel,
    os: HostOs,
    architecture: HostArchitecture,
    version: String? = null,
    buildPrefix: String? = null,
    productsApiUrl: String,
    payload: String,
): IdeArchiveResolution {
    val json = Json { ignoreUnknownKeys = true }
    val products = json.parseToJsonElement(payload).jsonArray

    val matchingProduct = products
        .filterIsInstance<JsonObject>()
        .firstOrNull { obj -> (obj["code"] as? JsonPrimitive)?.content == product.code }
        ?: error("Products response does not contain '${product.code}' entry")

    val releases = (matchingProduct["releases"] as? JsonArray) ?: JsonArray(emptyList())
    val downloadKey = resolveDownloadKey(os, architecture)
    val wantedVersion = version?.takeIf { it.isNotBlank() }
    val skippedWrongFilename = mutableListOf<String>()
    // Distinguishes "no release carries a `linuxARM64` download" (the product is simply not
    // published for that platform — MPS ships no Linux/Windows ARM64 at all) from "releases
    // carry it but every filename belongs to another edition". The two need different advice.
    var candidateReleases = 0
    var releasesOfferingDownloadKey = 0
    val offeredDownloadKeys = linkedSetOf<String>()

    for (release in releases.filterIsInstance<JsonObject>()) {
        val type = (release["type"] as? JsonPrimitive)?.content
        val releaseVersion = (release["version"] as? JsonPrimitive)?.content
        val build = (release["build"] as? JsonPrimitive)?.content
        val releaseDate = (release["date"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        if (!type.equals(channel.apiValue, ignoreCase = true)) continue
        if (releaseVersion.isNullOrBlank() || build.isNullOrBlank()) continue
        if (wantedVersion != null && wantedVersion != releaseVersion && wantedVersion != build) continue
        if (buildPrefix != null && !build.startsWith(buildPrefix)) continue

        candidateReleases++
        val downloads = release["downloads"] as? JsonObject ?: continue
        offeredDownloadKeys += downloads.keys
        val platformDownload = downloads[downloadKey] as? JsonObject ?: continue
        releasesOfferingDownloadKey++
        val link = (platformDownload["link"] as? JsonPrimitive)?.content ?: continue
        if (link.isBlank()) continue
        val checksumLink = (platformDownload["checksumLink"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val filename = downloadFilenameFromUrl(link)
        if (!product.acceptsDownloadFilename(filename)) {
            skippedWrongFilename += "$releaseVersion -> $filename"
            continue
        }
        return IdeArchiveResolution(
            product = product,
            channel = channel,
            version = releaseVersion,
            build = build,
            url = link,
            downloadKey = downloadKey,
            releaseDate = releaseDate,
            checksumUrl = checksumLink,
        )
    }

    if (candidateReleases > 0 && releasesOfferingDownloadKey == 0) {
        error(
            unpublishedPlatformFailureMessage(
                product = product,
                channel = channel,
                downloadKey = downloadKey,
                os = os,
                architecture = architecture,
                candidateReleases = candidateReleases,
                offeredDownloadKeys = offeredDownloadKeys,
                productsApiUrl = productsApiUrl,
                wantedVersion = wantedVersion,
                buildPrefix = buildPrefix,
            )
        )
    }
    error(resolveArchiveFailureMessage(product, channel, wantedVersion, buildPrefix, downloadKey, productsApiUrl, skippedWrongFilename))
}

/**
 * The feed answered, releases exist in the requested channel, and not one of them publishes a
 * [downloadKey] distribution — i.e. JetBrains does not ship this product for this OS/arch at all.
 * MPS is the in-catalog example: it has no `linuxARM64` and no `windowsARM64` entry in any release.
 *
 * Kept separate from [resolveArchiveFailureMessage] so the advice is truthful: retrying with
 * `--version` cannot help, and the caller should pick another host platform instead.
 *
 * When a `--version`/build filter was active only the matching releases were inspected, so the
 * message must not over-claim "not a supported host" — releases outside the filter may still
 * publish the platform.
 */
private fun unpublishedPlatformFailureMessage(
    product: IdeProduct,
    channel: IdeChannel,
    downloadKey: String,
    os: HostOs,
    architecture: HostArchitecture,
    candidateReleases: Int,
    offeredDownloadKeys: Set<String>,
    productsApiUrl: String,
    wantedVersion: String?,
    buildPrefix: String?,
): String {
    val offered = "Keys the feed does offer: ${offeredDownloadKeys.sorted().joinToString()}. Feed: $productsApiUrl"
    val filters = listOfNotNull(
        wantedVersion?.let { "version '$it'" },
        buildPrefix?.let { "build prefix '$it'" },
    )
    if (filters.isNotEmpty()) {
        return "JetBrains publishes no '$downloadKey' distribution of ${product.displayName} " +
            "(code=${product.code}) in the $candidateReleases '${channel.apiValue}' release(s) matching " +
            "${filters.joinToString(" and ")} — releases outside that filter may still cover " +
            "$os/$architecture; drop the filter to check. $offered"
    }
    return "JetBrains publishes no '$downloadKey' distribution for ${product.displayName} " +
        "(code=${product.code}): none of the $candidateReleases '${channel.apiValue}' releases carries that " +
        "download key, so $os/$architecture is not a supported host for this product. $offered"
}

internal fun IdeProduct.acceptsDownloadFilename(filename: String): Boolean {
    val tokens = urlFilenameTokens
    return tokens.isEmpty() || tokens.any { token -> filename.contains(token) }
}

internal fun downloadFilenameFromUrl(link: String): String =
    URI(link).path.substringAfterLast('/').takeIf { it.isNotBlank() } ?: link.substringAfterLast('/')

private fun resolveArchiveFailureMessage(
    product: IdeProduct,
    channel: IdeChannel,
    wantedVersion: String?,
    buildPrefix: String?,
    downloadKey: String,
    productsApiUrl: String,
    skippedWrongFilename: List<String>,
): String {
    val baseSelector = if (wantedVersion == null) "latest" else "version '$wantedVersion'"
    val versionMessage = if (buildPrefix == null) baseSelector
        else "$baseSelector (filtered to builds starting with '$buildPrefix')"
    val tokens = product.urlFilenameTokens
    if (tokens.isEmpty()) {
        return "Unable to resolve $versionMessage '${channel.apiValue}' release for product '${product.code}' " +
            "(tried download key $downloadKey) from $productsApiUrl"
    }
    val tokensText = tokens.joinToString { "`$it`" }
    val skippedText = skippedWrongFilename
        .take(5)
        .joinToString(prefix = " Skipped mismatched filenames: ")
        .takeIf { skippedWrongFilename.isNotEmpty() }
        .orEmpty()
    return "No release in the '${channel.apiValue}' channel of code=${product.code} serves a download URL whose filename " +
        "contains any of: $tokensText (tried download key $downloadKey) from $productsApiUrl. " +
        "Latest matched: <none>.$skippedText JetBrains may not have published this edition for the most recent " +
        "version; try --version with an older known-good build."
}

internal fun logFetchingProductsInfo(url: String) {
    ideReleaseLookupLog.debug("[IDE-DOWNLOAD] Fetching products info from {}", url)
}

internal fun readUrlText(url: String, accept: String = "application/json"): String {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 15_000
        readTimeout = 15_000
        setRequestProperty("Accept", accept)
    }
    try {
        val statusCode = connection.responseCode
        val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (statusCode !in 200..299) {
            throw IOException("Failed to fetch from $url. HTTP $statusCode\n$body")
        }
        return body
    } finally {
        connection.disconnect()
    }
}
