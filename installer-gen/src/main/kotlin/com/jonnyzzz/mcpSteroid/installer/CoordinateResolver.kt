/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * The coordinate RESOLVERS: all the hard logic + asserts that turn a downloaded artifact ON LOCAL DISK
 * into the validated coordinate metadata baked into the install scripts. Nothing here is string-derived
 * or guessed — sha256 and javaHomeSubpath come from INSPECTING the real bytes, so the generated scripts
 * carry exact values and the client never has to infer anything.
 *
 * Real mode: callers pass the public vendor / release URL as [LocalJdkArtifact.publicUrl]. Test mode:
 * callers pass the side-car (nginx) URL that serves the same local file. Either way the resolver reads
 * the local file for sha + layout.
 */
data class LocalJdkArtifact(
    val platformKey: String,
    val file: Path,
    /** URL recorded in the emitted coordinates (public vendor URL, or the test side-car URL). */
    val publicUrl: String,
    val vendor: String,
    val version: String,
    val format: String,
    /** Optional vendor-authoritative sha256 to cross-check the bytes against (fails fast on mismatch). */
    val expectedSha256: String? = null,
)

/** Builds [JdkCoordinates] from local JDK archives by inspecting their real contents. */
object JdkCoordinateResolver {
    fun resolve(artifacts: List<LocalJdkArtifact>): JdkCoordinates {
        val platforms = LinkedHashMap<String, JdkEntry>()
        for (a in artifacts) {
            require(Files.isRegularFile(a.file)) { "missing downloaded JDK for ${a.platformKey}: ${a.file}" }
            val sha = sha256(a.file)
            a.expectedSha256?.let { expected ->
                val norm = expected.trim().lowercase()
                require(sha == norm) {
                    "sha256 mismatch for ${a.platformKey}: computed $sha != expected $norm (${a.file})"
                }
            }
            val javaHomeSubpath = inspectJavaHomeSubpath(a.file, a.format, a.platformKey)
            platforms[a.platformKey] = JdkEntry(
                vendor = a.vendor,
                version = a.version,
                url = a.publicUrl,
                sha256 = sha,
                format = a.format,
                javaHomeSubpath = javaHomeSubpath,
            )
        }
        val coords = JdkCoordinates(schema = 1, platforms = platforms)
        validate(coords) // all 5 platforms present, sha256 == 64 lowercase hex, format in the allowed set
        return coords
    }
}

/** Builds [DevrigCoordinates] from the local devrig package zip. */
object DevrigCoordinateResolver {
    fun resolve(file: Path, publicUrl: String, format: String = "zip"): DevrigCoordinates {
        require(Files.isRegularFile(file)) { "missing devrig package: $file" }
        return DevrigCoordinates(
            schema = 1,
            devrig = DevrigEntry(url = publicUrl, sha256 = sha256(file), size = Files.size(file), format = format),
        )
    }
}

/** Lowercase-hex SHA-256 of a file — the form `InstallerGenerator.validate` requires. */
internal fun sha256(file: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            if (n > 0) md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Inspect the archive's REAL entries and derive the JAVA_HOME subdir = the directory that contains
 * `bin/java` (or `bin/java.exe`), with the trailing `/bin/java` stripped. This both DERIVES
 * javaHomeSubpath from the actual top-level dir (never string-derived — Corretto Linux/Windows embed the
 * full version; macOS nests Contents/Home; Microsoft's jdk-25.0.x+N is unguessable) AND asserts a JDK
 * launcher is present, so a malformed/wrong archive fails fast instead of baking a bad path into a script.
 */
internal fun inspectJavaHomeSubpath(file: Path, format: String, platformKey: String): String {
    val binJava = mutableListOf<String>()
    forEachArchiveEntry(file, format) { name, isDirectory ->
        if (isDirectory) return@forEachArchiveEntry
        val n = name.replace('\\', '/').removePrefix("./")
        if (n.endsWith("/bin/java") || n.endsWith("/bin/java.exe")) binJava += n
    }
    require(binJava.isNotEmpty()) {
        "no */bin/java(.exe) entry in the $platformKey archive $file — not a JDK, or the layout changed"
    }
    // The JDK launcher is the shortest such path (any nested jre/sample launcher would sit deeper).
    val launcher = binJava.minByOrNull { it.length }!!
    return launcher.removeSuffix(".exe").removeSuffix("/bin/java")
}

private fun forEachArchiveEntry(file: Path, format: String, onEntry: (name: String, isDirectory: Boolean) -> Unit) {
    BufferedInputStream(Files.newInputStream(file)).use { raw ->
        when (format) {
            "zip" -> ZipArchiveInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    onEntry(e.name, e.isDirectory)
                }
            }
            "tar.gz" -> readTar(GzipCompressorInputStream(raw), onEntry)
            "tar.xz" -> readTar(XZCompressorInputStream(raw), onEntry)
            else -> error("unknown archive format '$format' for $file")
        }
    }
}

private fun readTar(decompressed: InputStream, onEntry: (name: String, isDirectory: Boolean) -> Unit) {
    TarArchiveInputStream(decompressed).use { tar ->
        while (true) {
            val e = tar.nextEntry ?: break
            onEntry(e.name, e.isDirectory)
        }
    }
}
