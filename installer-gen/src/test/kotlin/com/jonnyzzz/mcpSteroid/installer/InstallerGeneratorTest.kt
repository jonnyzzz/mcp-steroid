/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstallerGeneratorTest {
    // ── platform-key adapter ─────────────────────────────────────────────────────────────────────

    @Test
    fun `scriptKey maps enums to the os-cpu strings the scripts use (aarch64 - arm64)`() {
        assertEquals("linux-x64", JdkPlatform(JdkOs.LINUX, JdkArch.X64).scriptKey())
        assertEquals("linux-arm64", JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64).scriptKey())
        assertEquals("macos-arm64", JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64).scriptKey())
        assertEquals("windows-x64", JdkPlatform(JdkOs.WINDOWS, JdkArch.X64).scriptKey())
        assertEquals("windows-arm64", JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64).scriptKey())
    }

    // ── jdkScriptTable: covers exactly the 5 installer platforms ─────────────────────────────────

    private fun art(platform: JdkPlatform, archive: ArchiveType, javaHome: String) = JdkArtifact(
        platform = platform, vendor = "v", version = "25.0.3.9.1", featureVersion = 25, archive = archive,
        url = "https://example.com/jdk.${archive.extension}", fileName = "jdk.${archive.extension}",
        size = 1, sha256 = "a".repeat(64), javaHome = javaHome,
    )

    private fun fullModel() = JdkModel(
        listOf(
            art(JdkPlatform(JdkOs.LINUX, JdkArch.X64), ArchiveType.TAR_GZ, "jdk-x64"),
            art(JdkPlatform(JdkOs.LINUX, JdkArch.AARCH64), ArchiveType.TAR_GZ, "jdk-arm64"),
            art(JdkPlatform(JdkOs.MACOS, JdkArch.AARCH64), ArchiveType.TAR_GZ, "jdk.jdk/Contents/Home"),
            art(JdkPlatform(JdkOs.WINDOWS, JdkArch.X64), ArchiveType.ZIP, "jdk-win"),
            art(JdkPlatform(JdkOs.WINDOWS, JdkArch.AARCH64), ArchiveType.ZIP, "zulu-win"),
        )
    )

    @Test
    fun `jdkScriptTable accepts exactly the 5 platforms`() {
        assertEquals(ALL_PLATFORMS.toSet(), jdkScriptTable(fullModel()).keys)
    }

    @Test
    fun `jdkScriptTable fails when a platform is missing`() {
        val partial = JdkModel(fullModel().jdks.dropLast(1)) // drop windows-arm64
        val ex = assertFailsWith<IllegalArgumentException> { jdkScriptTable(partial) }
        assertTrue(ex.message!!.contains("missing installer platforms"), ex.message!!)
    }

    @Test
    fun `validateScriptTable rejects a non-hex sha256 and an absolute javaHome`() {
        val badSha = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "ZZZ", "zip", "h") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(badSha) }
        val absHome = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "/abs") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(absHome) }
    }

    // ── render pipeline: scripts bake the table + carry the musl guard, no leftover placeholders ─

    // Launcher subpaths carry the build hash (devrig-<version>-<hash>), the way the real zip unpacks.
    private val devrig = DevrigEntry(
        url = "https://example.com/devrig-1.0.zip", sha256 = "b".repeat(64),
        launcherPosix = "devrig-1.0-abc1234/bin/devrig", launcherWindows = "devrig-1.0-abc1234/bin/devrig.bat",
    )

    @Test
    fun `renderInstallerScripts bakes every platform and leaves no placeholder`() {
        val table = jdkScriptTable(fullModel())
        val scripts = renderInstallerScripts(table, devrig, "1.2.3")

        // No unresolved @@…@@ placeholders survived.
        assertTrue(!scripts.sh.contains("@@") && !scripts.ps.contains("@@"), "placeholders left unresolved")

        // install.sh: the 3 POSIX platform arms + the musl-fail guard + the COMPUTED devrig launcher subpath
        // + the delegation to `devrig install devrig`.
        listOf("macos-arm64)", "linux-arm64)", "linux-x64)").forEach {
            assertTrue(scripts.sh.contains(it), "install.sh missing arm $it")
        }
        assertTrue(scripts.sh.contains("musl libc (Alpine) is not supported"), "install.sh missing musl guard")
        assertTrue(scripts.sh.contains("DEVRIG_BINSUB='devrig-1.0-abc1234/bin/devrig'"), "install.sh missing computed binsub")
        assertTrue(scripts.sh.contains("install devrig --install-script="), "install.sh must delegate to 'devrig install devrig'")
        assertTrue(scripts.sh.contains("DEVRIG_URL='https://example.com/devrig-1.0.zip'"), "install.sh missing devrig url")

        // install.ps1: the 2 Windows entries + the COMPUTED .bat launcher subpath + the delegation.
        listOf("'windows-x64' = @{", "'windows-arm64' = @{").forEach {
            assertTrue(scripts.ps.contains(it), "install.ps1 missing entry $it")
        }
        assertTrue(scripts.ps.contains("\$DevrigBinSub = 'devrig-1.0-abc1234/bin/devrig.bat'"), "install.ps1 missing computed binsub")
        assertTrue(scripts.ps.contains("install devrig"), "install.ps1 must delegate to 'devrig install devrig'")
    }

    @Test
    fun `install_ps1 closes stdin on the devrig launcher invocation`() {
        // Bootstrapping via `irm | iex` leaves the parent PS host with stdin still connected to the outer
        // shell's pipe. A devrig subprocess that reads stdin would hang forever with no user recourse.
        // `$null |` gives the launcher an empty input stream so the first read is EOF — enforcing the
        // `devrig install devrig` non-interactive contract (see runInstallDevrigCommand).
        val ps = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").ps
        assertTrue(
            ps.contains("\$null | & \$launcher install devrig"),
            "install.ps1 must pipe \$null into the devrig launcher so the child cannot inherit an open stdin",
        )
    }

    @Test
    fun `install_sh closes stdin on the devrig launcher invocation`() {
        // Mirror of the ps1 stdin-close on the POSIX side: `curl | sh` bootstrap leaves stdin open to the
        // outer shell's pipe. `< /dev/null` gives devrig an already-closed stdin.
        val sh = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").sh
        // Match the actual invocation line to avoid false positives on other `< /dev/null` uses.
        val launcherLine = sh.lines().firstOrNull { it.contains("\"\$launcher\" install devrig") }
            ?: error("install.sh must invoke \"\$launcher\" install devrig")
        assertTrue(
            "< /dev/null" in launcherLine,
            "install.sh must redirect stdin from /dev/null on the devrig launcher line: '$launcherLine'",
        )
    }

    @Test
    fun `rendered install scripts are ASCII-only`() {
        val scripts = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3")

        mapOf("install.sh" to scripts.sh, "install.ps1" to scripts.ps).forEach { (name, script) ->
            val nonAscii = script.withIndex().firstOrNull { it.value.code >= 128 }
            assertEquals(
                null,
                nonAscii,
                name + " must be ASCII-only; first non-ASCII character at index " + nonAscii?.index +
                    ": U+" + nonAscii?.value?.code?.toString(16)?.uppercase(),
            )
        }
    }

    @Test
    fun `renderInstallerScripts rejects non-ASCII substituted values`() {
        val unicodeDevrig = devrig.copy(url = devrig.url + 0x2603.toChar())

        val failure = assertFailsWith<IllegalArgumentException> {
            renderInstallerScripts(jdkScriptTable(fullModel()), unicodeDevrig, "1.2.3")
        }
        assertTrue(failure.message!!.contains("must be ASCII-only"), failure.message!!)
    }

    // ── devrig resolution: local override path (no network) ──────────────────────────────────────

    @Test
    fun `resolveDevrig from a local zip computes sha256 + the asserted launcher subpaths`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        // Top dir carries the build hash (devrig-<version>-<hash>), like the real release zip.
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-1.0-abc1234/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("devrig-1.0-abc1234/bin/devrig.bat")); z.write("@echo off".encodeToByteArray()); z.closeEntry()
        }
        val flags = mapOf("devrig-zip" to listOf(zip.toString()), "devrig-url" to listOf("https://example.com/devrig-1.0.zip"))

        val noNetwork = object : HttpFetcher {
            override fun head(url: String) = error("no network expected for the local-zip path")
            override fun getBytes(url: String) = error("no network expected for the local-zip path")
        }
        val devrig = resolveDevrig(flags, noNetwork, version = "1.0")
        assertEquals("https://example.com/devrig-1.0.zip", devrig.url)
        assertEquals(sha256Hex(Files.readAllBytes(zip)), devrig.sha256)
        // Computed + asserted from the real zip (NOT assumed devrig-<version>).
        assertEquals("devrig-1.0-abc1234/bin/devrig", devrig.launcherPosix)
        assertEquals("devrig-1.0-abc1234/bin/devrig.bat", devrig.launcherWindows)
        validateDevrig(devrig) // must pass
    }

    @Test
    fun `resolveDevrig fails when the zip has no devrig_bat launcher`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-1.0-abc1234/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
        }
        val flags = mapOf("devrig-zip" to listOf(zip.toString()), "devrig-url" to listOf("https://example.com/devrig.zip"))
        val ex = assertFailsWith<IllegalStateException> {
            resolveDevrig(flags, object : HttpFetcher {
                override fun head(url: String) = error("no network")
                override fun getBytes(url: String) = error("no network")
            }, version = "1.0")
        }
        assertTrue(ex.message!!.contains("devrig.bat"), ex.message!!)
    }

    @Test
    fun `resolveDevrig from the v-tag release resolves the devrig zip asset and the assert passes`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        // The real release zip's top dir carries the build hash: devrig-<version>-<hash>.
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-0.101-abc1234/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("devrig-0.101-abc1234/bin/devrig.bat")); z.write("@echo off".encodeToByteArray()); z.closeEntry()
        }
        val zipUrl = "https://github.com/jonnyzzz/mcp-steroid/releases/download/v0.101/devrig-0.101-abc1234.zip"
        // Fake GitHub: the v<version> tag release serves a devrig-<version>-<hash>.zip asset; getBytes on the
        // asset URL returns the synthetic zip bytes. No bare-tag fallback is needed (the v-tag resolves).
        val fakeGh = object : HttpFetcher {
            override fun head(url: String) = error("no head expected")
            override fun getBytes(url: String): ByteArray = when {
                url == "https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/tags/v0.101" ->
                    """{"assets":[{"name":"devrig-0.101-abc1234.zip","browser_download_url":"$zipUrl"}]}""".encodeToByteArray()
                url == zipUrl -> Files.readAllBytes(zip)
                else -> error("unexpected url: $url")
            }
        }
        val devrig = resolveDevrig(emptyMap(), fakeGh, version = "0.101")
        assertEquals(zipUrl, devrig.url)
        assertEquals("devrig-0.101-abc1234/bin/devrig", devrig.launcherPosix)
        validateDevrig(devrig) // must pass
    }

    @Test
    fun `resolveDevrig fails when the local zip top-dir version does not match --version`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        // Top dir says 0.100, but the generator runs with --version 0.101 → the install scripts would ship
        // a mismatched devrig. This must fail fast regardless of how devrig was resolved (here: local zip).
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-0.100-abc1234/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("devrig-0.100-abc1234/bin/devrig.bat")); z.write("@echo off".encodeToByteArray()); z.closeEntry()
        }
        val flags = mapOf("devrig-zip" to listOf(zip.toString()), "devrig-url" to listOf("https://example.com/devrig-0.100.zip"))
        val ex = assertFailsWith<IllegalArgumentException> {
            resolveDevrig(flags, object : HttpFetcher {
                override fun head(url: String) = error("no network")
                override fun getBytes(url: String) = error("no network")
            }, version = "0.101")
        }
        assertTrue(ex.message!!.contains("does not match repo VERSION '0.101'"), ex.message!!)
    }

    @Test
    fun `validateDevrig rejects a placeholder url`() {
        assertFailsWith<IllegalArgumentException> {
            validateDevrig(DevrigEntry(
                url = "https://example.com/PLACEHOLDER.zip", sha256 = "b".repeat(64),
                launcherPosix = "d/bin/devrig", launcherWindows = "d/bin/devrig.bat",
            ))
        }
    }
}
