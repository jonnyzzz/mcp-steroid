/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.Serializable
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.ByteArrayInputStream

/**
 * Operating system family of a JDK build. We ship only glibc Linux — musl/alpine is intentionally NOT
 * supported (the IntelliJ IDEs require glibc), and `install.sh` detects musl and fails fast.
 */
@Serializable
enum class JdkOs { LINUX, MACOS, WINDOWS }

/** CPU architecture of a JDK build. */
@Serializable
enum class JdkArch { X64, AARCH64 }

/** Archive container the JDK ships in. [extension] is the conventional file-name suffix. */
@Serializable
enum class ArchiveType(val extension: String) { TAR_GZ("tar.gz"), ZIP("zip") }

/** The (os, arch) a JDK build targets. */
@Serializable
data class JdkPlatform(val os: JdkOs, val arch: JdkArch)

/**
 * A fully-resolved JDK build, ready for the installer-script generation. Every field is COMPUTED from
 * the live vendor sources — none are hand-pinned:
 *  - [version]        — the vendor-native version string. NOT comparable across vendors: Corretto reports
 *                       the 5-part Amazon build (`25.0.3.9.1`), Azul the 3-part Java version (`25.0.3`).
 *  - [featureVersion] — the Java feature version (e.g. `25`). Comparable across vendors; use this, not
 *                       [version], for "is this JDK 25?" checks.
 *  - [url]            — the resolved, version-pinned download URL (e.g. Corretto's `latest` alias
 *                       followed to its versioned resource).
 *  - [fileName]       — the archive's file name (the [url] basename), e.g. for `curl -o <fileName>`.
 *  - [size]           — the byte length of the downloaded archive.
 *  - [sha256]         — lowercase hex over the downloaded bytes (Azul also cross-checks the published hash).
 *  - [javaHome]       — the path to `JAVA_HOME` (the directory whose `bin/` holds `java`), discovered by
 *                       scanning the archive entries, so macOS's `…/Contents/Home` and the Linux/Windows
 *                       top-level dir are handled uniformly. Always **archive-relative, forward-slash, no
 *                       leading or trailing slash** (ZIP/TAR entries are `/`-separated even for Windows
 *                       builds) — Windows consumers translate separators.
 */
@Serializable
data class JdkArtifact(
    val platform: JdkPlatform,
    val vendor: String,
    val version: String,
    val featureVersion: Int,
    val archive: ArchiveType,
    val url: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val javaHome: String,
)

/** The whole JDK data model: one [JdkArtifact] per supported platform. */
@Serializable
data class JdkModel(val jdks: List<JdkArtifact>)

/** The archive file name = the URL basename, minus any query string. Shared by the vendor resolvers. */
internal fun fileNameOf(url: String): String = url.substringAfterLast('/').substringBefore('?')

// ── archive scanning: compute JAVA_HOME (shared by the vendor resolvers) ─────────────────────────

internal fun archiveEntryNames(bytes: ByteArray, archive: ArchiveType): List<String> = when (archive) {
    ArchiveType.TAR_GZ ->
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tis ->
            generateSequence { tis.nextEntry }.map { it.name }.toList()
        }
    ArchiveType.ZIP ->
        ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { zis ->
            generateSequence { zis.nextEntry }.map { it.name }.toList()
        }
}

/**
 * Compute the archive-relative `JAVA_HOME` by finding the `bin/java` (or `bin/java.exe`) launcher and
 * returning the directory that contains its `bin/`. Works for every layout we ship:
 *  - Linux/Windows: `amazon-corretto-…-linux-x64/bin/java`           -> `amazon-corretto-…-linux-x64`
 *  - macOS:         `amazon-corretto-25.jdk/Contents/Home/bin/java`  -> `amazon-corretto-25.jdk/Contents/Home`
 */
internal fun findJavaHome(bytes: ByteArray, archive: ArchiveType): String {
    val names = archiveEntryNames(bytes, archive).map { it.trimEnd('/') }
    val launchers = names.filter {
        it == "bin/java" || it == "bin/java.exe" || it.endsWith("/bin/java") || it.endsWith("/bin/java.exe")
    }
    require(launchers.isNotEmpty()) {
        "Archive has no bin/java[.exe] entry; cannot compute JAVA_HOME (first entries=${names.take(8)})"
    }
    // A JDK may bundle a nested JRE (`<root>/jre/bin/java`) whose entry can appear before the real
    // launcher; pick the SHALLOWEST `bin/java` (fewest path segments) so we get the JDK root, not its
    // nested jre — independent of archive entry order.
    val launcher = launchers.minBy { it.count { c -> c == '/' } }

    val idx = launcher.lastIndexOf("/bin/")
    return if (idx < 0) "" else launcher.substring(0, idx)
}

// ── public entry point ───────────────────────────────────────────────────────────────────────────

/**
 * Prepare the data model of all 5 JDKs the installer ships: Amazon Corretto 25 for linux x64/aarch64,
 * macOS aarch64 and windows x64 (see [resolveCorrettoJdks]); plus Azul Zulu 25 for windows/aarch64 (see
 * [resolveAzulJdk]). Every download is validated vendor-naturally — both vendors publish detached
 * OpenPGP signatures, verified against a pinned signing-key fingerprint — and cached through [cache], so
 * re-runs reuse unchanged builds. The fingerprints are injectable so tests can pin a generated test key.
 */
fun resolveAllJdks(
    cache: Cache,
    http: HttpFetcher = KtorHttpFetcher,
    correttoKeyFingerprint: String = CORRETTO_KEY_FINGERPRINT,
    azulKeyFingerprint: String = AZUL_KEY_FINGERPRINT,
): JdkModel {
    val corretto = resolveCorrettoJdks(cache, http, correttoKeyFingerprint)
    val azul = resolveAzulJdk(cache, http, azulKeyFingerprint)
    return JdkModel(corretto + azul)
}
