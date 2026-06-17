/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import com.jonnyzzz.mcpSteroid.installer.resolver.PinnedJdkCoordinates
import com.jonnyzzz.mcpSteroid.installer.resolver.PinnedJdkEntry
import com.jonnyzzz.mcpSteroid.installer.resolver.resolveJdk
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoordinateResolverTest {
    @TempDir
    lateinit var tmp: Path

    private val json = Json { ignoreUnknownKeys = true }

    // The 5 real-world archive layouts the resolver must inspect (top dir differs from filename; macOS
    // nests Contents/Home; Windows uses bin/java.exe; Azul has its own dir name).
    private data class Fixture(val key: String, val vendor: String, val version: String, val format: String, val topDir: String)

    private val fixtures = listOf(
        Fixture("linux-x64", "corretto", "25.0.3.9.1", "tar.gz", "amazon-corretto-25.0.3.9.1-linux-x64"),
        Fixture("linux-arm64", "corretto", "25.0.3.9.1", "tar.gz", "amazon-corretto-25.0.3.9.1-linux-aarch64"),
        Fixture("macos-arm64", "corretto", "25.0.3.9.1", "tar.gz", "amazon-corretto-25.jdk/Contents/Home"),
        Fixture("windows-x64", "corretto", "25.0.3.9.1", "zip", "jdk25.0.3_9"),
        Fixture("windows-arm64", "azul-zulu", "25.0.3", "zip", "zulu25.34.17-ca-jdk25.0.3-win_aarch64"),
    )

    private fun javaEntry(topDir: String, zip: Boolean) = "$topDir/bin/" + if (zip) "java.exe" else "java"

    private fun buildArchive(f: Fixture): Path {
        val out = tmp.resolve("${f.key}.${f.format}")
        val zip = f.format == "zip"
        val entries = linkedMapOf(
            "${f.topDir}/release" to "JAVA_VERSION=\"25\"\n".toByteArray(),
            javaEntry(f.topDir, zip) to "#!/bin/sh\necho java-stub\n".toByteArray(),
        )
        writeArchive(out, f.format, entries)
        return out
    }

    private fun writeArchive(out: Path, format: String, entries: Map<String, ByteArray>) {
        Files.newOutputStream(out).use { fileOut ->
            when (format) {
                "zip" -> ZipArchiveOutputStream(fileOut).use { zip ->
                    for ((name, bytes) in entries) {
                        zip.putArchiveEntry(ZipArchiveEntry(name))
                        zip.write(bytes)
                        zip.closeArchiveEntry()
                    }
                }
                "tar.gz" -> writeTar(GzipCompressorOutputStream(fileOut), entries)
                "tar.xz" -> writeTar(XZCompressorOutputStream(fileOut), entries)
                else -> error("unsupported test archive format: $format")
            }
        }
    }

    private fun writeTar(compressed: OutputStream, entries: Map<String, ByteArray>) {
        TarArchiveOutputStream(compressed).use { tar ->
            for ((name, bytes) in entries) {
                val e = TarArchiveEntry(name)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }

    @Test
    fun `resolves all 5 platforms with javaHomeSubpath inspected from the real archive`() {
        val artifacts = fixtures.map { f ->
            LocalJdkArtifact(
                platformKey = f.key,
                file = buildArchive(f),
                publicUrl = "https://example/${f.key}.${f.format}",
                vendor = f.vendor,
                version = f.version,
                format = f.format,
            )
        }
        val coords = JdkCoordinateResolver.resolve(artifacts)

        // validate() inside resolve() already enforced all 5 platforms + sha/format; check the inspected subpaths.
        assertEquals("amazon-corretto-25.0.3.9.1-linux-x64", coords.platforms.getValue("linux-x64").javaHomeSubpath)
        assertEquals("amazon-corretto-25.jdk/Contents/Home", coords.platforms.getValue("macos-arm64").javaHomeSubpath)
        assertEquals("jdk25.0.3_9", coords.platforms.getValue("windows-x64").javaHomeSubpath)
        assertEquals("zulu25.34.17-ca-jdk25.0.3-win_aarch64", coords.platforms.getValue("windows-arm64").javaHomeSubpath)
        // sha256 is the real file digest (64 lowercase hex) and the recorded URL is what we passed.
        val linux = coords.platforms.getValue("linux-x64")
        assertTrue(linux.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals("https://example/linux-x64.tar.gz", linux.url)
    }

    @Test
    fun `expectedSha256 mismatch fails fast`() {
        val f = fixtures.first()
        val ex = assertFailsWith<IllegalArgumentException> {
            JdkCoordinateResolver.resolve(
                listOf(
                    LocalJdkArtifact(f.key, buildArchive(f), "https://e/x", f.vendor, f.version, f.format, expectedSha256 = "0".repeat(64)),
                ),
            )
        }
        assertTrue(ex.message!!.contains("sha256 mismatch"))
    }

    @Test
    fun `an archive without bin-java is rejected`() {
        val out = tmp.resolve("nojava.tar.gz")
        writeArchive(out, "tar.gz", linkedMapOf("some-jdk/release" to "x".toByteArray(), "some-jdk/lib/rt" to "y".toByteArray()))
        val ex = assertFailsWith<IllegalArgumentException> { inspectJavaHomeSubpath(out, "tar.gz", "linux-x64") }
        assertTrue(ex.message!!.contains("bin/java"))
    }

    @Test
    fun `nested launcher does not fool the top-level pick`() {
        val out = tmp.resolve("nested.tar.gz")
        writeArchive(
            out,
            "tar.gz",
            linkedMapOf(
                "jdk-25/bin/java" to "real".toByteArray(),
                "jdk-25/demo/sample/bin/java" to "decoy".toByteArray(),
            ),
        )
        assertEquals("jdk-25", inspectJavaHomeSubpath(out, "tar.gz", "linux-x64"))
    }

    @Test
    fun `tar_xz archives are inspected (xz codec is on the classpath)`() {
        val out = tmp.resolve("jdk.tar.xz")
        writeArchive(out, "tar.xz", linkedMapOf("jdk-25-xz/release" to "x".toByteArray(), "jdk-25-xz/bin/java" to "real".toByteArray()))
        assertEquals("jdk-25-xz", inspectJavaHomeSubpath(out, "tar.xz", "linux-x64"))
    }

    @Test
    fun `entry-name normalization handles leading dot-slash and backslashes`() {
        val dotSlash = tmp.resolve("dotslash.tar.gz")
        writeArchive(dotSlash, "tar.gz", linkedMapOf("./jdk-25/bin/java" to "real".toByteArray()))
        assertEquals("jdk-25", inspectJavaHomeSubpath(dotSlash, "tar.gz", "linux-x64"))

        val backslash = tmp.resolve("backslash.zip")
        writeArchive(backslash, "zip", linkedMapOf("jdk25_0_3_9\\bin\\java.exe" to "real".toByteArray()))
        assertEquals("jdk25_0_3_9", inspectJavaHomeSubpath(backslash, "zip", "windows-x64"))
    }

    // ── ResolverMain.resolveJdk: vendor sha is FETCHED (no hardcoded sha) + verified against the download ──

    /** Build the fixture into [dir] named by URL basename `<key>.<format>` (what resolveJdk resolves). */
    private fun buildArchiveInto(dir: Path, f: Fixture): Path {
        val out = dir.resolve("${f.key}.${f.format}")
        val zip = f.format == "zip"
        writeArchive(
            out, f.format,
            linkedMapOf(
                "${f.topDir}/release" to "JAVA_VERSION=\"25\"\n".toByteArray(),
                javaEntry(f.topDir, zip) to "#!/bin/sh\necho java-stub\n".toByteArray(),
            ),
        )
        return out
    }

    /** Vendor-shaped sha body: azul-zulu returns JSON `sha256_hash`; corretto/microsoft return bare hex. */
    private fun fakeShaBody(vendor: String, sha: String) =
        if (vendor == "azul-zulu") """{"sha256_hash":"$sha"}""" else "$sha\n"

    private fun writePinned(dir: Path): Pair<Path, MutableMap<String, String>> {
        val shaByUrl = HashMap<String, String>()
        val platforms = fixtures.associate { f ->
            val file = buildArchiveInto(dir, f)
            val shaUrl = "https://sha.example/${f.key}"
            shaByUrl[shaUrl] = fakeShaBody(f.vendor, sha256(file))
            f.key to PinnedJdkEntry(f.vendor, f.version, "https://dl.example/${file.fileName}", f.format, shaUrl)
        }
        val source = tmp.resolve("pinned.json")
        source.writeText(Json.encodeToString(PinnedJdkCoordinates(2, platforms)))
        return source to shaByUrl
    }

    @Test
    fun `resolveJdk fetches the vendor sha, verifies the download, and emits coordinates`() {
        val dl = Files.createDirectories(tmp.resolve("dl"))
        val (source, shaByUrl) = writePinned(dl)
        val out = tmp.resolve("out/jdk-coordinates.json")

        resolveJdk(source, dl, out, urlBase = null, fetcher = { shaByUrl.getValue(it) })

        val coords = json.decodeFromString<JdkCoordinates>(out.readText())
        assertEquals(fixtures.map { it.key }.toSet(), coords.platforms.keys)
        // The emitted sha is the real digest of the downloaded file (== the vendor sha we served).
        assertEquals(sha256(dl.resolve("linux-x64.tar.gz")), coords.platforms.getValue("linux-x64").sha256)
        assertEquals("zulu25.34.17-ca-jdk25.0.3-win_aarch64", coords.platforms.getValue("windows-arm64").javaHomeSubpath)
    }

    @Test
    fun `resolveJdk fails when the vendor sha does not match the download`() {
        val dl = Files.createDirectories(tmp.resolve("dl"))
        val (source, shaByUrl) = writePinned(dl)
        // Vendor reports a different sha for one platform → corrupt download OR a newer build shipped.
        shaByUrl["https://sha.example/linux-x64"] = "${"a".repeat(64)}\n"
        val out = tmp.resolve("out/jdk-coordinates.json")

        val ex = assertFailsWith<IllegalArgumentException> {
            resolveJdk(source, dl, out, urlBase = null, fetcher = { shaByUrl.getValue(it) })
        }
        assertTrue(ex.message!!.contains("sha256 mismatch"), ex.message)
    }

    @Test
    fun `devrig resolver records sha size and url`() {
        val zip = tmp.resolve("devrig.zip")
        writeArchive(zip, "zip", linkedMapOf("devrig-0.0.0/bin/devrig" to "#!/bin/sh\n".toByteArray()))
        val coords = DevrigCoordinateResolver.resolve(zip, publicUrl = "https://example/devrig-0.0.0.zip")
        assertTrue(coords.devrig.sha256.matches(Regex("[0-9a-f]{64}")))
        assertEquals(Files.size(zip), coords.devrig.size)
        assertEquals("zip", coords.devrig.format)
        assertEquals("https://example/devrig-0.0.0.zip", coords.devrig.url)
    }
}
