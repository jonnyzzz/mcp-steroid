/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ── Azul Zulu 25, Windows/aarch64 (Metadata API; detached OpenPGP signature) ─────────────────────

private const val AZUL_PACKAGES = "https://api.azul.com/metadata/v1/zulu/packages/"
// Azul's package signing key (RSA-4096, fingerprint 27BC…B1998361219BD9C9) — the key that signs the
// `signature-binary` detached OpenPGP signatures the Metadata API advertises for each package.
private const val AZUL_KEY_URL = "https://repos.azul.com/azul-repo.key"

/** Pinned fingerprint of the Azul package signing key (RSA-4096) — same rationale as Corretto's. */
const val AZUL_KEY_FINGERPRINT = "27bc0c8cb3d81623f59bdadcb1998361219bd9c9"

private val azulJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class AzulPackage(
    @SerialName("download_url") val downloadUrl: String,
    val name: String,
    @SerialName("java_version") val javaVersion: List<Int> = emptyList(),
    @SerialName("package_uuid") val packageUuid: String,
)

@Serializable
private data class AzulSignature(val type: String, val url: String)

@Serializable
private data class AzulDetail(
    @SerialName("download_url") val downloadUrl: String,
    val name: String,
    @SerialName("sha256_hash") val sha256Hash: String,
    @SerialName("java_version") val javaVersion: List<Int> = emptyList(),
    val signatures: List<AzulSignature> = emptyList(),
)

private fun compareVersionLists(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}

/** Resolve the latest GA Azul Zulu 25 for Windows/aarch64 via the Metadata API and PGP-verify it. */
internal fun resolveAzulJdk(cache: Cache, http: HttpFetcher, keyFingerprint: String): JdkArtifact {
    // Resolve the LATEST GA JDK 25 for Windows/aarch64 via the Azul Metadata API — no hardcoded URL.
    val listUrl = AZUL_PACKAGES + "?java_version=25&os=windows&arch=aarch64&archive_type=zip" +
            "&java_package_type=jdk&javafx_bundled=false&latest=true&release_status=ga" +
            "&availability_types=CA&page=1&page_size=100"
    val packages = azulJson.decodeFromString<List<AzulPackage>>(http.getBytes(listUrl).decodeToString())
    require(packages.isNotEmpty()) { "Azul Metadata API returned no GA JDK 25 win_aarch64 packages: $listUrl" }
    val latest = packages.sortedWith { x, y -> compareVersionLists(x.javaVersion, y.javaVersion) }.last()

    val detail = azulJson.decodeFromString<AzulDetail>(http.getBytes(AZUL_PACKAGES + latest.packageUuid).decodeToString())

    // Azul exposes no HEAD ETag, but DOES publish a sha256 — content-address the cache by it (the hash is
    // both the cache key and a first-line integrity check on the download).
    val bytes = cache.downloadVerifyingSha256(detail.downloadUrl, detail.sha256Hash, http)

    // Vendor-natural validation, same as Corretto: verify Azul's detached OpenPGP signature against the
    // pinned Azul package signing key. Run every resolve (cheap) so a cache hit is validated too.
    val openpgp = detail.signatures.firstOrNull { it.type.equals("openpgp", ignoreCase = true) }
        ?: error("Azul package ${latest.packageUuid} advertises no OpenPGP signature: ${detail.signatures}")
    PgpVerifier.verifyDetached(bytes, http.getBytes(openpgp.url), http.getBytes(AZUL_KEY_URL), keyFingerprint)

    require(detail.javaVersion.isNotEmpty()) { "Azul package ${latest.packageUuid} has no java_version" }
    return JdkArtifact(
        platform = JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64),
        vendor = "azul-zulu",
        version = detail.javaVersion.joinToString("."),
        featureVersion = detail.javaVersion.first(),
        archive = ArchiveType.ZIP,
        url = detail.downloadUrl,
        fileName = fileNameOf(detail.downloadUrl),
        size = bytes.size.toLong(),
        sha256 = detail.sha256Hash.lowercase(),
        javaHome = findJavaHome(bytes, ArchiveType.ZIP),
    )
}
