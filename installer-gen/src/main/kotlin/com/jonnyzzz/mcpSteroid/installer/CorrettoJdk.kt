/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

// ── Amazon Corretto 25 (detached .sig on the CDN; ETag-cached download) ──────────────────────────

private const val CORRETTO_KEY_URL = "https://apt.corretto.aws/corretto.key"
private const val CORRETTO_LATEST = "https://corretto.aws/downloads/latest"

/**
 * Pinned fingerprint of the Amazon Corretto release key (RSA-4096). The key is fetched live over HTTPS,
 * so we bind verification to this constant rather than trusting whatever the key endpoint serves.
 */
const val CORRETTO_KEY_FINGERPRINT = "6dc3636dae534049c8b94623a122542ab04f24e3"

private data class CorrettoSpec(val platform: JdkPlatform, val archive: ArchiveType, val alias: String)

// The `latest` aliases auto-resolve to the newest Corretto 25 build (currently 25.0.3.9.1) — no pin.
// Exactly the 4 Corretto platforms the installer ships (+ Azul windows/aarch64 = 5 total). No alpine
// (musl unsupported) and no macos-x64 (the installer targets Apple-silicon macOS only).
private val CORRETTO_SPECS = listOf(
    CorrettoSpec(JdkPlatform(JdkOs.LINUX, JdkArch.X64), ArchiveType.TAR_GZ, "amazon-corretto-25-x64-linux-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64), ArchiveType.TAR_GZ, "amazon-corretto-25-aarch64-linux-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64), ArchiveType.TAR_GZ, "amazon-corretto-25-aarch64-macos-jdk.tar.gz"),
    CorrettoSpec(JdkPlatform(JdkOs.WINDOWS, JdkArch.X64), ArchiveType.ZIP, "amazon-corretto-25-x64-windows-jdk.zip"),
)

private val CORRETTO_VERSION_RE = Regex("""amazon-corretto-(\d[\d.]*\d)""")

/** Resolve every Corretto JDK the installer ships, fetching the release key once and PGP-verifying each. */
internal fun resolveCorrettoJdks(cache: Cache, http: HttpFetcher, keyFingerprint: String): List<JdkArtifact> {
    val correttoKey = http.getBytes(CORRETTO_KEY_URL)
    return CORRETTO_SPECS.map { resolveCorretto(it, cache, http, correttoKey, keyFingerprint) }
}

private fun resolveCorretto(
    spec: CorrettoSpec,
    cache: Cache,
    http: HttpFetcher,
    correttoKey: ByteArray,
    keyFingerprint: String,
): JdkArtifact {
    val aliasUrl = "$CORRETTO_LATEST/${spec.alias}"
    // The versioned URL behind the `latest` redirect — recorded in the model so installs are reproducible.
    val versionedUrl = http.head(aliasUrl).resolvedUrl
    val version = CORRETTO_VERSION_RE.find(versionedUrl)?.groupValues?.get(1)
        ?: error("Cannot parse Corretto version from resolved URL: $versionedUrl")

    // ETag-validated cache: an unchanged build is a cache hit; a new release re-downloads.
    val bytes = cache.downloadWithEtag(aliasUrl, http)

    // Vendor-natural validation: verify the detached .sig against the pinned Amazon Corretto release key.
    val signature = http.getBytes("$aliasUrl.sig")
    PgpVerifier.verifyDetached(bytes, signature, correttoKey, keyFingerprint)

    return JdkArtifact(
        platform = spec.platform,
        vendor = "corretto",
        version = version,
        featureVersion = version.substringBefore('.').toInt(),
        archive = spec.archive,
        url = versionedUrl,
        fileName = fileNameOf(versionedUrl),
        size = bytes.size.toLong(),
        sha256 = sha256Hex(bytes),
        javaHome = findJavaHome(bytes, spec.archive),
    )
}
