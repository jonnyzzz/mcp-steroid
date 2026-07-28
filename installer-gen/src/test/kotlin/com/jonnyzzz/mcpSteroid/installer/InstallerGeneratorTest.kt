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
        val badSha = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "ZZZ", "zip", "h", 1L) }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(badSha) }
        val absHome = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "/abs", 1L) }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(absHome) }
    }

    @Test
    fun `validateScriptTable rejects a non-positive size`() {
        // size feeds the pre-download disk-space check (#228); a 0/negative size would make it meaningless.
        val zeroSize = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "h", 0L) }.toTypedArray())
        val ex = assertFailsWith<IllegalArgumentException> { validateScriptTable(zeroSize) }
        assertTrue(ex.message!!.contains("size must be a positive byte count"), ex.message!!)
    }

    // ── render pipeline: scripts bake the table + carry the musl guard, no leftover placeholders ─

    // Launcher subpaths carry the build hash (devrig-<version>-<hash>), the way the real zip unpacks.
    private val devrig = DevrigEntry(
        url = "https://example.com/devrig-1.0.zip", sha256 = "b".repeat(64),
        launcherPosix = "devrig-1.0-abc1234/bin/devrig", launcherWindows = "devrig-1.0-abc1234/bin/devrig.bat",
        size = 233_000_000L,
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
    fun `rendered scripts carry the error-resilience machinery (disk precheck, retry+backoff, timeouts)`() {
        // Issue #228: the generated installers must precheck disk space, retry transient failures with
        // backoff, and bound the network with timeouts. Assert the machinery is baked into both scripts.
        val table = jdkScriptTable(fullModel()) // art() has size=1 → every arm bakes jdk_size='1'
        val scripts = renderInstallerScripts(table, devrig, "1.2.3")

        // -- install.sh --
        // sizes threaded through for the disk check
        assertTrue(scripts.sh.contains("DEVRIG_SIZE='233000000'"), "install.sh missing baked DEVRIG_SIZE")
        assertTrue(scripts.sh.contains("jdk_size='1'"), "install.sh missing per-platform jdk_size")
        // disk precheck: computes need vs df availability and fails with a clear message
        assertTrue(scripts.sh.contains("df -Pk"), "install.sh missing df-based disk check")
        assertTrue(scripts.sh.contains("insufficient disk space"), "install.sh missing clear disk-space error")
        // retry loop + growing backoff + final give-up
        assertTrue(scripts.sh.contains("DL_ATTEMPTS=3"), "install.sh missing retry-attempts config")
        assertTrue(scripts.sh.contains("attempt \$ia_attempt/\$DL_ATTEMPTS failed"), "install.sh missing retry log")
        assertTrue(scripts.sh.contains("after \$DL_ATTEMPTS attempts"), "install.sh missing final give-up message")
        // network timeouts / stall detection (so the retry loop can fire instead of hanging)
        assertTrue(scripts.sh.contains("--connect-timeout 30"), "install.sh curl missing connect timeout")
        assertTrue(scripts.sh.contains("--speed-limit 1024 --speed-time 30"), "install.sh curl missing stall detection")
        assertTrue(scripts.sh.contains("wget -q --timeout=30 --tries=1"), "install.sh wget missing timeout")
        // cleanup-on-exit trap for this run's staging
        assertTrue(scripts.sh.contains("trap 'rm -rf \"\$BINARIES_DIR\"/.tmp.\$\$.*"), "install.sh missing cleanup trap")

        // -- install.ps1 (ASCII-only) --
        assertTrue(scripts.ps.contains("\$DevrigSize   = 233000000"), "install.ps1 missing baked DevrigSize")
        assertTrue(scripts.ps.contains("JdkSize = 1"), "install.ps1 missing per-platform JdkSize")
        assertTrue(scripts.ps.contains("AvailableFreeSpace"), "install.ps1 missing DriveInfo disk check")
        assertTrue(scripts.ps.contains("insufficient disk space"), "install.ps1 missing clear disk-space error")
        assertTrue(scripts.ps.contains("\$DlAttempts   = 3"), "install.ps1 missing retry-attempts config")
        assertTrue(scripts.ps.contains("-TimeoutSec 1800"), "install.ps1 missing request timeout")
        assertTrue(scripts.ps.contains("after \$DlAttempts attempts"), "install.ps1 missing final give-up message")
        assertTrue(scripts.ps.contains("function Remove-Staging"), "install.ps1 missing staging cleanup")
    }

    @Test
    fun `both scripts announce the artifact size in the downloading line (parsed by the IDE plugin)`() {
        // The IDE plugin installs devrig for the user and shows a real progress fraction by parsing this
        // line (ij-plugin InstallerProgress.parseInstallerLine): the "(~N MB)" is the denominator. Both
        // scripts must therefore log the size in the SAME shape — install.ps1 used to omit it, which left
        // Windows users with an indeterminate bar for a ~611 MB download.
        val scripts = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3")

        assertTrue(
            scripts.sh.contains("""log "downloading ${'$'}{ia_kind} (~${'$'}((ia_size / 1024 / 1024)) MB) from ${'$'}ia_url ...""""),
            "install.sh must log the artifact size in the downloading line",
        )
        assertTrue(
            scripts.ps.contains("""Write-Log ("downloading {0} (~{1} MB) from {2} ..." -f ${'$'}kind, [long](${'$'}size / 1MB), ${'$'}url)"""),
            "install.ps1 must log the artifact size in the downloading line",
        )
        // The size has to reach the function that logs it, for both artifacts.
        assertTrue(
            scripts.ps.contains("Install-Artifact 'devrig' \$DevrigUrl \$DevrigSha256 \$DevrigFormat \$DevrigSize"),
            "install.ps1 must pass DevrigSize to Install-Artifact",
        )
        assertTrue(
            scripts.ps.contains("\$p.JdkSize"),
            "install.ps1 must pass the per-platform JdkSize to Install-Artifact",
        )
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
        val devrig = resolveDevrig(flags, noNetwork)
        assertEquals("https://example.com/devrig-1.0.zip", devrig.url)
        assertEquals(sha256Hex(Files.readAllBytes(zip)), devrig.sha256)
        assertEquals(Files.readAllBytes(zip).size.toLong(), devrig.size) // size computed from the bytes (#228)
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
            })
        }
        assertTrue(ex.message!!.contains("devrig.bat"), ex.message!!)
    }

    @Test
    fun `validateDevrig rejects a placeholder url`() {
        assertFailsWith<IllegalArgumentException> {
            validateDevrig(DevrigEntry(
                url = "https://example.com/PLACEHOLDER.zip", sha256 = "b".repeat(64),
                launcherPosix = "d/bin/devrig", launcherWindows = "d/bin/devrig.bat", size = 1L,
            ))
        }
    }

    @Test
    fun `validateDevrig rejects a non-positive size`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validateDevrig(DevrigEntry(
                url = "https://example.com/devrig-1.0.zip", sha256 = "b".repeat(64),
                launcherPosix = "d/bin/devrig", launcherWindows = "d/bin/devrig.bat", size = 0L,
            ))
        }
        assertTrue(ex.message!!.contains("size must be a positive byte count"), ex.message!!)
    }
}
