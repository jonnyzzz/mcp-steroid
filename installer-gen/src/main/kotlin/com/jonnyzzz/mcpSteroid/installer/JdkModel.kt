/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.Serializable
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
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
 *  - [unpackedSize]   — the byte length of the archive's contents once extracted, summed from the real
 *                       entries (see [archiveUnpackedSize]). Together with [size] it gives the installers
 *                       an exact pre-download disk-space requirement instead of a multiple-of-archive guess.
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
    val unpackedSize: Long,
    val sha256: String,
    val javaHome: String,
)

/** The whole JDK data model: one [JdkArtifact] per supported platform. */
@Serializable
data class JdkModel(val jdks: List<JdkArtifact>)

/** The archive file name = the URL basename, minus any query string. Shared by the vendor resolvers. */
internal fun fileNameOf(url: String): String = url.substringAfterLast('/').substringBefore('?')

// ── archive scanning: compute JAVA_HOME (shared by the vendor resolvers) ─────────────────────────

/**
 * One archive entry: its `/`-separated [name] and the UNPACKED byte length of its content
 * ([unpackedSize]; 0 for directories, symlinks and other non-regular entries).
 */
data class ArchiveEntry(val name: String, val unpackedSize: Long)

/**
 * Every entry of [bytes], with its unpacked length. ZIPs are read through [ZipFile] (the CENTRAL
 * DIRECTORY) rather than streamed: a streaming reader reports size `-1` for entries whose sizes live in
 * a trailing data descriptor, which is exactly how `java.util.zip.ZipOutputStream` writes them.
 */
fun archiveEntries(bytes: ByteArray, archive: ArchiveType): List<ArchiveEntry> = when (archive) {
    ArchiveType.TAR_GZ ->
        TarArchiveInputStream(GzipCompressorInputStream(ByteArrayInputStream(bytes))).use { tis ->
            generateSequence { tis.nextEntry }.map { ArchiveEntry(it.name, it.size) }.toList()
        }
    ArchiveType.ZIP ->
        ZipFile.builder().setSeekableByteChannel(SeekableInMemoryByteChannel(bytes)).get().use { zf ->
            zf.entries.asSequence().map { ArchiveEntry(it.name, it.size) }.toList()
        }
}

internal fun archiveEntryNames(bytes: ByteArray, archive: ArchiveType): List<String> =
    archiveEntries(bytes, archive).map { it.name }

/**
 * The REAL byte length of [bytes] once unpacked — the sum of every entry's content length. Bakes an exact
 * number into the installers' pre-download disk-space check instead of the old `archive x 3` guess (#228):
 * an already-compressed JDK unpacks to well under 2x its archive, a text-heavy one to well over it.
 *
 * Sizes must be KNOWN (never `-1`): both readers above are central-directory / header based, so a missing
 * size means a malformed archive — fail generation rather than bake a too-small disk requirement.
 */
fun archiveUnpackedSize(bytes: ByteArray, archive: ArchiveType): Long {
    val entries = archiveEntries(bytes, archive)
    val unsized = entries.filter { it.unpackedSize < 0 }
    require(unsized.isEmpty()) {
        "archive has entries with an unknown unpacked size: ${unsized.take(5).map { it.name }}"
    }
    val total = entries.sumOf { it.unpackedSize }
    require(total > 0) { "archive unpacks to 0 bytes (${entries.size} entries) - refusing to bake it" }
    return total
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
