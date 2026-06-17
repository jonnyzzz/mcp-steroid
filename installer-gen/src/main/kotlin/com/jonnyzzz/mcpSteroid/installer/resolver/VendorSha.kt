/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.resolver

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The PINNED JDK source (`jdk-downloader/jdk25-pinned.json`): URL + vendor + version + format + the
 * VENDOR endpoint that publishes the sha256. NO hardcoded sha — it is fetched + verified so download
 * correctness is validated rather than asserted against a literal that silently rots.
 */
@Serializable
data class PinnedJdkCoordinates(val schema: Int = 2, val platforms: Map<String, PinnedJdkEntry>)

@Serializable
data class PinnedJdkEntry(
    val vendor: String,
    val version: String,
    val url: String,
    val format: String,
    /** Vendor endpoint returning this artifact's sha256 (see [parseVendorSha] for the per-vendor body). */
    val sha256Url: String,
)

/** Fetches the raw body of a vendor sha URL. An injectable seam — [parseVendorSha] is the tested unit. */
fun interface ShaFetcher {
    fun fetch(url: String): String
}

/** Production fetcher over HTTPS (JDK's built-in client; no extra dependency). */
val HttpShaFetcher = ShaFetcher { url ->
    // followRedirects(NORMAL): the JDK default is NEVER, so a vendor CDN 3xx would otherwise surface as a
    // non-200 and trip the require below for a reason unrelated to integrity.
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    require(response.statusCode() == 200) { "vendor sha fetch failed ($url): HTTP ${response.statusCode()}" }
    response.body()
}

private val shaJson = Json { ignoreUnknownKeys = true }

/**
 * Parse the vendor-published sha256 (returned as 64 lowercase hex) from [body], by [vendor] convention:
 *  - `corretto`   — bare `<64hex>` (e.g. `corretto.aws/downloads/latest_sha256/<asset>`; trim handles an
 *                   optional trailing newline — the live body is 64 bytes, no newline).
 *  - `azul-zulu`  — JSON object with `sha256_hash` (e.g. `api.azul.com/metadata/v1/zulu/packages/<uuid>`).
 *  - `microsoft`  — `.sha256sum.txt` line: `<64hex>␠␠<filename>` → first whitespace-delimited token.
 *                   (Supported for completeness — no shipping platform pins Microsoft today.)
 *
 * Fails fast on an unknown vendor or a body that does not yield 64 hex chars.
 */
fun parseVendorSha(vendor: String, body: String): String {
    val raw = when (vendor) {
        "corretto" -> body.trim()
        "azul-zulu" -> shaJson.parseToJsonElement(body).jsonObject["sha256_hash"]?.jsonPrimitive?.content
            ?: error("azul-zulu sha response has no 'sha256_hash' field (body starts: ${body.take(160)})")
        "microsoft" -> body.trim().substringBefore(' ').substringBefore('\t').trim()
        else -> error("unknown vendor '$vendor' — no sha256 parser")
    }.lowercase()
    require(raw.matches(Regex("[0-9a-f]{64}"))) {
        "vendor '$vendor' sha256 is not 64 lowercase hex: '$raw' (body starts: ${body.take(120)})"
    }
    return raw
}
