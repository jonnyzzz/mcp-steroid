/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdkArtifactsTest {
    // ── findJavaHome: layout-aware JAVA_HOME discovery ───────────────────────────────────────────

    @Test
    fun `findJavaHome on a Linux tar_gz returns the top-level dir`() {
        val bytes = tarGz(
            mapOf(
                "amazon-corretto-25.0.3.9.1-linux-x64/bin/java" to "ELF".encodeToByteArray(),
                "amazon-corretto-25.0.3.9.1-linux-x64/lib/modules" to "x".encodeToByteArray(),
            )
        )
        assertEquals("amazon-corretto-25.0.3.9.1-linux-x64", findJavaHome(bytes, ArchiveType.TAR_GZ))
    }

    @Test
    fun `findJavaHome on a macOS tar_gz returns the Contents-Home dir`() {
        val bytes = tarGz(mapOf("amazon-corretto-25.jdk/Contents/Home/bin/java" to "ELF".encodeToByteArray()))
        assertEquals("amazon-corretto-25.jdk/Contents/Home", findJavaHome(bytes, ArchiveType.TAR_GZ))
    }

    @Test
    fun `findJavaHome on a Windows zip finds java_exe`() {
        val bytes = zip(mapOf("jdk25.0.3.9.1/bin/java.exe" to "MZ".encodeToByteArray()))
        assertEquals("jdk25.0.3.9.1", findJavaHome(bytes, ArchiveType.ZIP))
    }

    @Test
    fun `findJavaHome prefers the JDK root over a nested jre, regardless of entry order`() {
        // Nested jre/bin/java listed FIRST — must not be mistaken for JAVA_HOME.
        val bytes = tarGz(
            linkedMapOf(
                "jdk-25/jre/bin/java" to "ELF".encodeToByteArray(),
                "jdk-25/bin/java" to "ELF".encodeToByteArray(),
            )
        )
        assertEquals("jdk-25", findJavaHome(bytes, ArchiveType.TAR_GZ))
    }

    // ── resolveAllJdks: full model assembly, hermetic (synthetic archives + fake HTTP) ───────────

    @Test
    fun `resolveAllJdks computes every platform with vendor-natural PGP validation`() {
        val keys = TestPgp.generate()
        val fixtures = JdkFixtures(keys)
        val cache = Cache.inMemory()

        // The fixtures serve the test key for BOTH vendor key URLs, so pin to its fingerprint.
        val model = resolveAllJdks(cache, fixtures, keys.fingerprint, keys.fingerprint)
        val jdks = model.jdks

        // 4 Corretto (linux x64+aarch64, macOS aarch64, windows x64) + 1 Azul windows/aarch64 = the 5
        // platforms the installer ships. No alpine (musl unsupported), no macos-x64.
        assertEquals(5, jdks.size, "expected 4 Corretto + 1 Azul, got ${jdks.map { it.platform to it.vendor }}")

        val corretto = jdks.filter { it.vendor == "corretto" }
        assertEquals(4, corretto.size)
        assertTrue(corretto.all { it.version == "25.0.3.9.1" }, "Corretto version parsed from resolved URL")
        assertEquals(
            setOf(
                JdkPlatform(JdkOs.LINUX, JdkArch.X64), JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64),
                JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64),
                JdkPlatform(JdkOs.WINDOWS, JdkArch.X64),
            ),
            corretto.map { it.platform }.toSet(),
        )

        // macOS layout -> Contents/Home; everything else -> top-level dir.
        corretto.filter { it.platform.os == JdkOs.MACOS }.forEach {
            assertEquals("amazon-corretto-25.jdk/Contents/Home", it.javaHome)
        }
        corretto.first { it.platform == JdkPlatform(JdkOs.LINUX, JdkArch.X64) }.let {
            assertEquals("amazon-corretto-25.0.3.9.1-linux-x64", it.javaHome)
            assertEquals(sha256Hex(fixtures.linuxTarGz), it.sha256)
            assertEquals(fixtures.linuxTarGz.size.toLong(), it.size)
            assertTrue(it.url.contains("amazon-corretto-25.0.3.9.1"), "records the version-pinned URL: ${it.url}")
            assertEquals(ArchiveType.TAR_GZ, it.archive)
            assertEquals(25, it.featureVersion)
            assertEquals(it.url.substringAfterLast('/'), it.fileName)
            assertTrue(it.fileName.endsWith(".tar.gz"), it.fileName)
        }
        corretto.first { it.platform == JdkPlatform(JdkOs.WINDOWS, JdkArch.X64) }.let {
            assertEquals(ArchiveType.ZIP, it.archive)
            assertEquals("jdk25.0.3.9.1", it.javaHome)
        }

        val azul = jdks.single { it.vendor == "azul-zulu" }
        assertEquals(JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64), azul.platform)
        assertEquals("25.0.3", azul.version)
        assertEquals(25, azul.featureVersion)
        assertEquals(ArchiveType.ZIP, azul.archive)
        assertEquals("zulu25.34.17-ca-jdk25.0.3-win_aarch64", azul.javaHome)
        assertEquals("zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip", azul.fileName)
        assertEquals(sha256Hex(fixtures.azulZip), azul.sha256)
        assertEquals("https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip", azul.url)
    }

    @Test
    fun `resolveAllJdks fails when the Corretto signature does not verify`() {
        val keys = TestPgp.generate()
        val fixtures = JdkFixtures(keys, tamperCorrettoSig = true)
        assertFailsWith<IllegalStateException> {
            resolveAllJdks(Cache.inMemory(), fixtures, keys.fingerprint, keys.fingerprint)
        }
    }

    @Test
    fun `resolveAllJdks fails when the Azul signature does not verify`() {
        val keys = TestPgp.generate()
        val fixtures = JdkFixtures(keys, tamperAzulSig = true)
        assertFailsWith<IllegalStateException> {
            resolveAllJdks(Cache.inMemory(), fixtures, keys.fingerprint, keys.fingerprint)
        }
    }

    @Test
    fun `resolveAllJdks fails when the Corretto key fingerprint is not the pinned one`() {
        val keys = TestPgp.generate()
        val fixtures = JdkFixtures(keys)
        // Production default pins the real Amazon fingerprint, which the test key cannot match.
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveAllJdks(Cache.inMemory(), fixtures) // defaults -> real pinned fingerprints
        }
        assertTrue(ex.message!!.contains("does not match the pinned"), ex.message!!)
    }
}

/**
 * In-memory HTTP fixtures for [resolveAllJdks]: serves synthetic JDK archives, the matching detached
 * OpenPGP signatures (signed by [keys]), the public key, and the Azul Metadata API JSON — all routed by
 * URL pattern so no network is touched.
 */
private class JdkFixtures(
    private val keys: TestPgp,
    private val tamperCorrettoSig: Boolean = false,
    private val tamperAzulSig: Boolean = false,
) : HttpFetcher {
    val linuxTarGz = tarGz(mapOf("amazon-corretto-25.0.3.9.1-linux-x64/bin/java" to "ELF".encodeToByteArray()))
    val macosTarGz = tarGz(mapOf("amazon-corretto-25.jdk/Contents/Home/bin/java" to "ELF".encodeToByteArray()))
    val windowsZip = zip(mapOf("jdk25.0.3.9.1/bin/java.exe" to "MZ".encodeToByteArray()))
    val azulZip = zip(mapOf("zulu25.34.17-ca-jdk25.0.3-win_aarch64/bin/java.exe" to "MZ".encodeToByteArray()))

    private val correttoLatest = "https://corretto.aws/downloads/latest/"
    private val azulPackages = "https://api.azul.com/metadata/v1/zulu/packages/"
    private val azulUuid = "7e4b3352-ff70-4dd9-958e-44910f65b9d1"
    private val azulDownload = "https://cdn.azul.com/zulu/bin/zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip"
    private val azulSigUrl = "${azulPackages}$azulUuid/signature-binary?signature-index=0"

    private fun correttoArchive(url: String): ByteArray = when {
        url.contains("windows") -> windowsZip
        url.contains("macos") -> macosTarGz
        else -> linuxTarGz
    }

    override fun head(url: String): UrlKey {
        require(url.startsWith(correttoLatest)) { "unexpected HEAD: $url" }
        // Mimic the `latest` alias -> versioned resource redirect, exposing an ETag (Corretto does).
        val resolved = url
            .replace("/downloads/latest/", "/downloads/resources/25.0.3.9.1/")
            .replace("amazon-corretto-25-", "amazon-corretto-25.0.3.9.1-")
        val bytes = correttoArchive(url)
        return UrlKey(url = url, size = bytes.size.toLong(), lastModified = null, etag = "\"etag-${url.hashCode()}\"", resolvedUrl = resolved)
    }

    override fun getBytes(url: String): ByteArray = when {
        url == "https://apt.corretto.aws/corretto.key" -> keys.publicKeyRing
        url == "https://repos.azul.com/azul-repo.key" -> keys.publicKeyRing

        url.startsWith(correttoLatest) && url.endsWith(".sig") -> {
            val sig = keys.signDetached(correttoArchive(url.removeSuffix(".sig")))
            if (tamperCorrettoSig) keys.signDetached("other".encodeToByteArray()) else sig
        }
        url.startsWith(correttoLatest) -> correttoArchive(url)

        url.startsWith("$azulPackages?") -> azulListJson().encodeToByteArray()
        url == "$azulPackages$azulUuid" -> azulDetailJson().encodeToByteArray()
        url == azulSigUrl ->
            if (tamperAzulSig) keys.signDetached("other".encodeToByteArray()) else keys.signDetached(azulZip)
        url == azulDownload -> azulZip

        else -> error("unexpected GET: $url")
    }

    // Two candidates so version selection (25.0.0 vs 25.0.3) is exercised; latest must win.
    private fun azulListJson(): String = """
        [
          {"download_url":"$azulDownload","name":"zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip",
           "java_version":[25,0,3],"package_uuid":"$azulUuid"},
          {"download_url":"https://cdn.azul.com/zulu/bin/zulu25.28.85-ca-jdk25.0.0-win_aarch64.zip",
           "name":"zulu25.28.85-ca-jdk25.0.0-win_aarch64.zip","java_version":[25,0,0],
           "package_uuid":"9de7a772-4546-46db-a91b-d50b6cff3d02"}
        ]
    """.trimIndent()

    private fun azulDetailJson(): String = """
        {"download_url":"$azulDownload","name":"zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip",
         "sha256_hash":"${sha256Hex(azulZip)}","java_version":[25,0,3],
         "signatures":[{"type":"openpgp","url":"$azulSigUrl"}]}
    """.trimIndent()
}
