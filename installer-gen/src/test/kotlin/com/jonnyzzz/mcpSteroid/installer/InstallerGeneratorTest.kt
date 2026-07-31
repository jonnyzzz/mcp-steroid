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
        val badSha = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "ZZZ", "zip", "h", "25.0.3") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(badSha) }
        val absHome = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "/abs", "25.0.3") }.toTypedArray())
        assertFailsWith<IllegalArgumentException> { validateScriptTable(absHome) }
    }

    @Test
    fun `validateScriptTable rejects a blank or path-unsafe jdk version`() {
        // The version becomes a path segment of the install dir (jdk-<key>-<version>-<sha12>) — a blank
        // value or one carrying a path separator would corrupt the folder layout.
        for (bad in listOf("", "25.0.3/evil", "25 0 3")) {
            val table = mapOf(*ALL_PLATFORMS.map { it to JdkScriptEntry("https://x", "a".repeat(64), "zip", "h", bad) }.toTypedArray())
            assertFailsWith<IllegalArgumentException>("version '$bad' must be rejected") { validateScriptTable(table) }
        }
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
        // The JDK's own (vendor-native) version is baked per platform — the install dir is named by it,
        // not by the devrig VERSION (jonnyzzz/mcp-steroid#362).
        assertTrue(scripts.sh.contains("jdk_version='25.0.3.9.1'"), "install.sh missing baked jdk_version")
        assertTrue(scripts.ps.contains("JdkVersion = '25.0.3.9.1'"), "install.ps1 missing baked JdkVersion")
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
        // Silences PowerShell's default progress bar, which cripples Invoke-WebRequest / Expand-Archive
        // throughput (jonnyzzz/mcp-steroid#274). Regression-guard: removing this line brings the bug back.
        assertTrue(
            scripts.ps.contains("\$ProgressPreference = 'SilentlyContinue'"),
            "install.ps1 must silence \$ProgressPreference so Invoke-WebRequest + Expand-Archive stay fast",
        )
    }

    @Test
    fun `final guidance recommends agent-qualified install commands, never bare devrig install`() {
        // jonnyzzz/mcp-steroid#320: `devrig install` has a required <agent> argument, so the old
        // "run: devrig install" next-step guidance errored on every fresh install. Both scripts must
        // print agent-qualified commands instead.
        val scripts = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3")

        listOf("devrig install claude", "devrig install codex", "devrig install gemini").forEach {
            assertTrue(scripts.sh.contains(it), "install.sh guidance missing '$it'")
            assertTrue(scripts.ps.contains(it), "install.ps1 guidance missing '$it'")
        }
        // A bare `devrig install` right before the closing quote is the broken recommendation.
        assertTrue(!scripts.sh.contains("devrig install\""), "install.sh must not recommend bare 'devrig install'")
        assertTrue(!scripts.ps.contains("devrig install\""), "install.ps1 must not recommend bare 'devrig install'")
    }

    @Test
    fun `install_ps1 prepends BinDir to the current session PATH after the devrig handoff`() {
        // jonnyzzz/mcp-steroid#275: `devrig install devrig` registers the bin dir persistently
        // (HKCU\Environment), which only reaches NEW shells — but `irm | iex` runs in the caller's
        // session and the script tells the user to run `devrig install <agent>` immediately. The
        // template must make devrig resolvable in the calling session itself.
        val scripts = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3")
        assertTrue(
            scripts.ps.contains("\$env:PATH = \"\$BinDir;\$env:PATH\""),
            "install.ps1 must prepend \$BinDir to the current session \$env:PATH",
        )
    }

    @Test
    fun `install dirs are named by each artifact's own version - jdk folder carries the JDK version`() {
        // jonnyzzz/mcp-steroid#362: the JDK used to unpack into jdk-<key>-<DEVRIG VERSION>-<sha12>. Both
        // scripts must thread a per-artifact version into the install-dir name: $VERSION for devrig, the
        // baked vendor-native JDK version for the JDK.
        val scripts = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3")

        // install.sh: the target dir uses the helper's per-artifact version argument, never $VERSION.
        assertTrue(
            scripts.sh.contains("ia_target=\"\$BINARIES_DIR/\${ia_kind}-\${key}-\${ia_version}-\${ia_sha12}\""),
            "install.sh must name the install dir by the per-artifact ia_version",
        )
        assertTrue(!scripts.sh.contains("\${ia_kind}-\${key}-\${VERSION}"), "install.sh must not name install dirs by the devrig VERSION")
        assertTrue(
            scripts.sh.contains("install_artifact jdk \"\$jdk_url\" \"\$jdk_sha256\" \"\$jdk_format\" \"\$jdk_version\""),
            "install.sh must pass the baked jdk_version to install_artifact",
        )
        assertTrue(
            scripts.sh.contains("install_artifact devrig \"\$DEVRIG_URL\" \"\$DEVRIG_SHA256\" \"\$DEVRIG_FORMAT\" \"\$VERSION\""),
            "install.sh must pass the devrig VERSION to install_artifact",
        )

        // install.ps1: same split — $Version for devrig, $p.JdkVersion for the JDK.
        assertTrue(
            scripts.ps.contains("\$name   = \"\$kind-\$key-\$ver-\$sha12\""),
            "install.ps1 must name the install dir by the per-artifact \$ver parameter",
        )
        assertTrue(!scripts.ps.contains("\"\$kind-\$key-\$Version-\$sha12\""), "install.ps1 must not name install dirs by the devrig \$Version")
        assertTrue(
            scripts.ps.contains("Install-Artifact 'jdk'    \$p.JdkUrl  \$p.JdkSha256  \$p.JdkFormat \$p.JdkVersion"),
            "install.ps1 must pass the baked JdkVersion to Install-Artifact",
        )
        assertTrue(
            scripts.ps.contains("Install-Artifact 'devrig' \$DevrigUrl \$DevrigSha256 \$DevrigFormat \$Version"),
            "install.ps1 must pass the devrig \$Version to Install-Artifact",
        )
    }

    @Test
    fun `install_ps1 sets ProgressPreference before any Invoke-WebRequest or Expand-Archive call`() {
        // A `$ProgressPreference = 'SilentlyContinue'` line placed AFTER Invoke-WebRequest / Expand-Archive
        // would leave the first download and the first unpack still crawling under the default progress
        // bar — the exact regression jonnyzzz/mcp-steroid#274 pins. The setting must be baked BEFORE both,
        // so an edit that moves it below the download function fails this test.
        val ps = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").ps
        val silentAt = ps.indexOf("\$ProgressPreference = 'SilentlyContinue'")
        assertTrue(silentAt >= 0, "install.ps1 must set \$ProgressPreference = 'SilentlyContinue'")
        // Match the actual call syntax, not the bare cmdlet name — the setting's own comment mentions
        // both cmdlets by name, and the test must not fire on those mentions.
        val invokeAt = ps.indexOf("Invoke-WebRequest -Uri")
        val expandAt = ps.indexOf("Expand-Archive -Path")
        assertTrue(invokeAt > 0, "install.ps1 must call Invoke-WebRequest -Uri")
        assertTrue(expandAt > 0, "install.ps1 must call Expand-Archive -Path")
        assertTrue(invokeAt > silentAt, "\$ProgressPreference must precede Invoke-WebRequest (silentAt=$silentAt, invokeAt=$invokeAt)")
        assertTrue(expandAt > silentAt, "\$ProgressPreference must precede Expand-Archive (silentAt=$silentAt, expandAt=$expandAt)")
        // And it must appear exactly once (belt+suspenders — a stray duplicate would suggest a template
        // copy/paste bug that some future edit might revert one of the two lines).
        assertEquals(silentAt, ps.lastIndexOf("\$ProgressPreference = 'SilentlyContinue'"), "duplicate \$ProgressPreference setting")
    }

    @Test
    fun `install_ps1 restores the caller ProgressPreference (no session leak under irm iex)`() {
        // `irm | iex` runs install.ps1 in the CALLER's scope, so a bare `$ProgressPreference =
        // 'SilentlyContinue'` would leave the user's interactive session with progress permanently
        // silenced. The script must snapshot the prior value and restore it in a `finally` so the
        // success path and any uncaught terminating error both put the session back as they found it.
        val ps = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").ps
        val saveAt = ps.indexOf("\$SteroidPrevProgressPreference = \$ProgressPreference")
        val setAt = ps.indexOf("\$ProgressPreference = 'SilentlyContinue'")
        val restoreAt = ps.indexOf("\$ProgressPreference = \$SteroidPrevProgressPreference")
        assertTrue(saveAt >= 0, "install.ps1 must snapshot the caller's \$ProgressPreference before overriding it")
        assertTrue(restoreAt >= 0, "install.ps1 must restore the caller's \$ProgressPreference (leak fix)")
        // Order: snapshot → override → … → restore. Restore must be the LAST of the three so it wins.
        assertTrue(saveAt < setAt, "must snapshot BEFORE overriding \$ProgressPreference")
        assertTrue(restoreAt > setAt, "must restore AFTER the override")
        // The restore lives in a finally that closes a try opened right after the override.
        val tryAt = ps.indexOf("\ntry {", setAt)
        val finallyAt = ps.lastIndexOf("finally {")
        assertTrue(tryAt in 0 until restoreAt, "the body must be wrapped in a try{} opened after the override")
        assertTrue(finallyAt in 0 until restoreAt, "the restore must sit inside the finally{} block")
    }

    @Test
    fun `install_sh does not carry the PowerShell ProgressPreference setting`() {
        // Belt-and-suspenders: `$ProgressPreference` is a PowerShell-only automatic variable. The POSIX
        // install.sh must not contain it — a leak would mean the shared render pipeline crossed streams
        // between the two templates.
        val sh = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").sh
        assertTrue(!sh.contains("ProgressPreference"), "install.sh must not carry the PS-only ProgressPreference setting")
    }

    @Test
    fun `install_ps1 detects CPU arch via PROCESSOR_ARCHITECTURE, not RuntimeInformation`() {
        // jonnyzzz/mcp-steroid#273: reading [RuntimeInformation]::OSArchitecture aborts under
        // Set-StrictMode Latest on the .NET Framework 4.6.x that base Windows 10 ships. The primary
        // detection path must be the always-set Windows env vars; the RuntimeInformation branch may
        // exist ONLY as a non-Windows (pwsh-Core) fallback, guarded by try/catch.
        val ps = renderInstallerScripts(jdkScriptTable(fullModel()), devrig, "1.2.3").ps
        assertTrue(
            ps.contains("\$env:PROCESSOR_ARCHITECTURE"),
            "install.ps1 must consult \$env:PROCESSOR_ARCHITECTURE for CPU detection",
        )
        assertTrue(
            ps.contains("\$env:PROCESSOR_ARCHITEW6432"),
            "install.ps1 must also consult \$env:PROCESSOR_ARCHITEW6432 (WoW64 32-on-64 case)",
        )
        // If the .NET static access is present at all, the access itself must live inside a preceding
        // try{} block — StrictMode + missing property is exactly the #273 abort. Match the qualified
        // type reference (only appears in code, never in comments) so comment mentions of the bare
        // word "RuntimeInformation" don't false-positive.
        val accessMarker = "[System.Runtime.InteropServices.RuntimeInformation]"
        val accessAt = ps.indexOf(accessMarker)
        if (accessAt >= 0) {
            val tryAt = ps.lastIndexOf("try {", accessAt)
            assertTrue(
                tryAt in 0 until accessAt,
                "install.ps1 accesses $accessMarker but not inside a preceding try{} block — " +
                    "unguarded access re-introduces #273 on old .NET Framework builds",
            )
        }
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
    fun `resolveDevrig accepts the 0-102-plus release top-dir shape`(@TempDir dir: Path) {
        val zip = dir.resolve("devrig.zip")
        // 0.102+ release zips use the uniform lane layout: devrig-<version>.0-r-<hash> (#360)
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("devrig-0.102.0-r-abc1234/bin/devrig")); z.write("#!/bin/sh".encodeToByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("devrig-0.102.0-r-abc1234/bin/devrig.bat")); z.write("@echo off".encodeToByteArray()); z.closeEntry()
        }
        val flags = mapOf("devrig-zip" to listOf(zip.toString()), "devrig-url" to listOf("https://example.com/devrig-0.102.0-r-abc1234.zip"))
        val http = object : HttpFetcher {
            override fun head(url: String) = error("no network")
            override fun getBytes(url: String) = error("no network")
        }
        val devrig = resolveDevrig(flags, http, version = "0.102")
        assertEquals("devrig-0.102.0-r-abc1234/bin/devrig", devrig.launcherPosix)

        // a differing base still fails fast — and "0.10" must not prefix-match "0.102"
        val ex = assertFailsWith<IllegalArgumentException> { resolveDevrig(flags, http, version = "0.10") }
        assertTrue(ex.message!!.contains("does not match repo VERSION '0.10'"), ex.message!!)
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
