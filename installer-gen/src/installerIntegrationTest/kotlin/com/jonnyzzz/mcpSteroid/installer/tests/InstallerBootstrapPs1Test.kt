/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.DevrigEntry
import com.jonnyzzz.mcpSteroid.installer.JdkScriptEntry
import com.jonnyzzz.mcpSteroid.installer.writeInstallerScripts
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.queryContainerIp
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * PowerShell peer of [InstallerBootstrapTest]: renders `install.ps1` from a synthetic model + nginx
 * side-car URLs (the same public [writeInstallerScripts] seam — no real 200 MB JDK download), runs
 * it inside an Ubuntu-based `pwsh` container, and asserts the same download → sha256-verify →
 * unpack-verbatim → content-address → launcher → devrig-delegation → ready-prompt pipeline the
 * POSIX sibling covers.
 *
 * Windows-native runs of `install.ps1` need a Windows Docker host, which this repo's CI does not
 * provision; running pwsh under Linux instead exercises the same script (`Invoke-WebRequest`,
 * `Expand-Archive`, `Get-FileHash`, `Move-Item`, and — critically for jonnyzzz/mcp-steroid#274 —
 * `$ProgressPreference = 'SilentlyContinue'` at the top). The template already accepts a `bin/java`
 * stub in place of `bin/java.exe` "so pwsh-on-Linux test runs without java.exe" — the design
 * anticipates this exact test.
 */
class InstallerBootstrapPs1Test {
    private val version = INSTALLER_TEST_VERSION

    // Official Microsoft pwsh-on-Ubuntu image, pinned to the LTS 7.4 line on Ubuntu 22.04 (`lts-*`
    // tags carry the PS LTS version; the plain `ubuntu-<os>` tags float). glibc-based, so the same
    // fake JDK zip stub the sh test uses works here too.
    private val pwshImage = "mcr.microsoft.com/powershell:lts-7.4-ubuntu-22.04"
    private val homeDir = INSTALLER_HOME_DIR

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `generated install_ps1 end-to-end under pwsh on ubuntu (glibc)`() = runWithCloseableStack { lifetime ->
        // ── 1. build fixtures into a temp dir ──
        val fixturesDir = createInstallerWorkDir("installer-ps1-fixtures")
        val devrigZip = File(fixturesDir, "devrig.zip").also { buildFakePwshDevrigZip(it) }
        val jdkZip = File(fixturesDir, "jdk.zip").also { buildFakeJdkZip(it) }
        val devrigSha = sha256(devrigZip)
        val jdkSha = sha256(jdkZip)
        makeWorldReadable(fixturesDir)

        // ── 2. start the nginx side-car FIRST (need its bridge IP before baking URLs) ──
        val nginx = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(NGINX_IMAGE)
                .logPrefix("installer-ps1-nginx")
                .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
        )
        val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP — cannot serve fixtures")
        log("nginx side-car serving fixtures at http://$nginxIp/")

        // ── 3. render install.ps1 from a synthetic model. Windows JDK entries baked with `zip` format
        //       (matches the real Corretto/Azul Windows archives + Expand-Archive's contract) and
        //       javaHome="jdk" pointing at the top dir of the fixture zip. Non-Windows entries are
        //       required by validateScriptTable (all 5 platforms) but the ps1 script only reads its own
        //       $Platforms hashtable, which contains only windows-x64 + windows-arm64. ──
        val jdkEntryWin = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "zip", "jdk")
        val jdkEntryPosix = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "tar.gz", "jdk")
        val table = ALL_PLATFORMS.associateWith { key ->
            if (key.startsWith("windows-")) jdkEntryWin else jdkEntryPosix
        }
        // The fixture zip unpacks to devrig-<version>/, so that's the computed+asserted launcher subpath.
        val devrig = DevrigEntry(
            url = "http://$nginxIp/devrig.zip", sha256 = devrigSha,
            launcherPosix = "devrig-$version/bin/devrig", launcherWindows = "devrig-$version/bin/devrig.bat",
        )
        val genDir = createInstallerWorkDir("installer-ps1-gen-out")
        writeInstallerScripts(genDir.toPath(), table, devrig, version)
        require(File(genDir, "install.ps1").isFile) { "did not produce install.ps1 in $genDir" }
        makeWorldReadable(genDir)

        // ── 4. start the pwsh install container (Ubuntu base, adds curl for verifyMockServes) ──
        val install = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(pwshImage)
                .logPrefix("installer-pwsh-ubuntu")
                .volumes(ContainerVolume(genDir, "/gen", "ro"))
                .entryPoint(
                    "sh", "-c",
                    "apt-get update -qq && apt-get install -y -qq curl >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000",
                ),
        )
        awaitCurlInstalled(install)
        verifyMockServes(install, nginxIp, "/devrig.zip")
        verifyMockServes(install, nginxIp, "/jdk.zip")

        val devrigKey = "devrig-windows-x64-$version-${devrigSha.take(12)}"
        val jdkKey = "jdk-windows-x64-$version-${jdkSha.take(12)}"
        // install.ps1 uses Join-Path everywhere; on pwsh-Linux that returns forward-slash paths.
        val expectedLauncher = "$homeDir/.mcp-steroid/binaries/$devrigKey/devrig-$version/bin/devrig.bat"
        val expectedJdkHome = "$homeDir/.mcp-steroid/binaries/$jdkKey/jdk"

        // ── run #1: clean HOME → DOWNLOAD both, DELEGATE launcher+PATH registration to
        //    `devrig install devrig`. Exercise the FULL auto-detect path — no DEVRIG_OS / DEVRIG_CPU
        //    so this run stresses the same code the shipped `irm | iex` bootstrap hits on real Windows:
        //      • $os falls back to 'windows' (template default when DEVRIG_OS is unset).
        //      • $env:PROCESSOR_ARCHITECTURE / PROCESSOR_ARCHITEW6432 are unset on Linux → the
        //        RuntimeInformation fallback runs and returns "X64" on x86_64 hosts, matching the
        //        AMD64|X64 regex → $cpu = 'x64'.
        //    That auto-detect path is exactly what regressed in #273 (RuntimeInformation ungated) —
        //    keeping the vars unset here is the runtime coverage that stops #273 from recurring on
        //    the auto-detect branch. USERPROFILE stays set only so we don't cross into the "no user
        //    home resolvable" Fail branch. ──
        val env = mapOf(
            "HOME" to homeDir,
            "USERPROFILE" to homeDir,
        )
        val run1 = runInstall(install, env)
            .assertExitCode(0) { "install.ps1 run #1 failed:\n$this" }
            .assertOutputContains("downloading devrig", "downloading jdk", message = "run #1 (clean HOME) must download both")
            // The pwsh script ran the UNPACKED devrig launcher with the computed path + bundled JDK.
            .assertOutputContains(
                "DEVRIG_INSTALL_DEVRIG", "--install-script=$expectedLauncher", "--jdk-home=$expectedJdkHome",
                message = "install.ps1 must delegate to 'devrig install devrig' with the computed launcher + jdk-home",
            )
            .assertOutputContains("devrig binary is ready", "devrig install", message = "must report ready + how to register with agents")
        // NEVER auto-register with an agent (would edit agent configs — that is an explicit user step).
        run1.assertNoMessageInOutput("DEVRIG_INSTALL_CALLED")

        // (a) content-addressed dirs exist
        sh(install, "ls -1 \"$homeDir/.mcp-steroid/binaries\"")
            .assertExitCode(0) { "could not list binaries dir:\n$this" }
            .assertOutputContains(devrigKey, jdkKey, message = "expected content-addressed dirs")

        // (b) JDK downloaded + unpacked verbatim (fixture has bin/java, matching the template's
        //     pwsh-on-Linux fallback that accepts bin/java when bin/java.exe is missing).
        sh(install, "test -x \"$homeDir/.mcp-steroid/binaries/$jdkKey/jdk/bin/java\" && echo JDK_JAVA_OK")
            .assertOutputContains("JDK_JAVA_OK", message = "JDK bin/java missing — not downloaded/unpacked")

        // (c) idempotent re-run reuses the content-addressed dirs and DOWNLOADS NOTHING
        val reRun = runInstall(install, env)
            .assertExitCode(0) { "idempotent re-run failed:\n$this" }
            .assertOutputContains("already installed: $devrigKey", "already installed: $jdkKey", message = "re-run must report 'already installed'")
        reRun.assertNoMessageInOutput("downloading jdk")
        reRun.assertNoMessageInOutput("downloading devrig")

        log("ALL INSTALLER ASSERTIONS PASSED on ubuntu (glibc) under pwsh — download + delegate to devrig install devrig")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `install_ps1 auto-detects arch from PROCESSOR env (WoW64 + ARM64), rejects x86, catches bad sha`() =
        runWithCloseableStack { lifetime ->
            // The x64 CI runner cannot cover WoW64 / ARM64 / x86 natively, but detection reads env vars
            // ($env:PROCESSOR_ARCHITEW6432 / PROCESSOR_ARCHITECTURE), so pwsh-on-Linux with those vars set
            // exercises the exact real-Windows branches. DEVRIG_OS=windows forces the Windows platform table.
            val fixturesDir = createInstallerWorkDir("installer-ps1-arch-fixtures")
            val devrigZip = File(fixturesDir, "devrig.zip").also { buildFakePwshDevrigZip(it) }
            val jdkZip = File(fixturesDir, "jdk.zip").also { buildFakeJdkZip(it) }
            val devrigSha = sha256(devrigZip)
            val jdkSha = sha256(jdkZip)
            makeWorldReadable(fixturesDir)

            val nginx = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(NGINX_IMAGE).logPrefix("installer-ps1-arch-nginx")
                    .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
            )
            val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP")

            val jdkEntryWin = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "zip", "jdk")
            val jdkEntryPosix = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "tar.gz", "jdk")
            val table = ALL_PLATFORMS.associateWith { key -> if (key.startsWith("windows-")) jdkEntryWin else jdkEntryPosix }
            val devrig = DevrigEntry(
                url = "http://$nginxIp/devrig.zip", sha256 = devrigSha,
                launcherPosix = "devrig-$version/bin/devrig", launcherWindows = "devrig-$version/bin/devrig.bat",
            )
            val genDir = createInstallerWorkDir("installer-ps1-arch-gen")
            writeInstallerScripts(genDir.toPath(), table, devrig, version)
            makeWorldReadable(genDir)
            // A second render with a deliberately-corrupt devrig sha → download then Get-FileHash mismatch.
            val badGenDir = createInstallerWorkDir("installer-ps1-badsha-gen")
            writeInstallerScripts(badGenDir.toPath(), table, devrig.copy(sha256 = "0".repeat(64)), version)
            makeWorldReadable(badGenDir)

            val install = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(pwshImage).logPrefix("installer-pwsh-arch")
                    .volumes(ContainerVolume(genDir, "/gen", "ro"), ContainerVolume(badGenDir, "/badgen", "ro"))
                    .entryPoint("sh", "-c", "apt-get update -qq && apt-get install -y -qq curl >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000"),
            )
            awaitCurlInstalled(install)

            val base = mapOf("HOME" to homeDir, "USERPROFILE" to homeDir, "DEVRIG_OS" to "windows")
            // WoW64: 32-bit PS on 64-bit Windows sets PROCESSOR_ARCHITECTURE=x86 but the real arch in
            // PROCESSOR_ARCHITEW6432 — which must win → windows-x64.
            runInstall(install, base + mapOf("PROCESSOR_ARCHITEW6432" to "AMD64", "PROCESSOR_ARCHITECTURE" to "x86"))
                .assertExitCode(0) { "WoW64 run failed:\n$this" }
                .assertOutputContains("platform: windows-x64", message = "WoW64: PROCESSOR_ARCHITEW6432=AMD64 must beat x86 → windows-x64")
            // ARM64 (empty ARCHITEW6432 is falsy in PS → falls through to PROCESSOR_ARCHITECTURE).
            runInstall(install, base + mapOf("PROCESSOR_ARCHITEW6432" to "", "PROCESSOR_ARCHITECTURE" to "ARM64"))
                .assertExitCode(0) { "ARM64 run failed:\n$this" }
                .assertOutputContains("platform: windows-arm64", message = "ARM64 must resolve to windows-arm64")
            // x86 with no 64-bit ARCHITEW6432 → explicit rejection, non-zero exit.
            val x86 = runInstall(install, base + mapOf("PROCESSOR_ARCHITEW6432" to "", "PROCESSOR_ARCHITECTURE" to "x86"))
            require(x86.exitCode != 0) { "x86 must be rejected (non-zero exit):\n$x86" }
            x86.assertOutputContains("32-bit Windows is not supported", message = "x86 must be rejected with the 32-bit message")
            // Corrupt devrig sha → mismatch caught, non-zero exit.
            val bad = runInstall(install, base + mapOf("PROCESSOR_ARCHITECTURE" to "AMD64"), script = "/badgen/install.ps1")
            require(bad.exitCode != 0) { "bad-sha run must fail:\n$bad" }
            bad.assertOutputContains("SHA-256 mismatch", message = "a corrupt devrig sha must be caught before use")
            log("arch matrix (WoW64/ARM64/x86) + sha-mismatch assertions passed under pwsh")
        }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `install_ps1 hands devrig a closed stdin (child reads EOF) and restores caller ProgressPreference`() =
        runWithCloseableStack { lifetime ->
            // Behavioral proof of the stdin-close (`$null | & $launcher …`): the fake devrig READS stdin and
            // records whether it saw EOF (closed) or data. And a dot-sourced run (mimicking `irm | iex`)
            // proves $ProgressPreference is restored to the caller's value (the #274 leak fix).
            val fixturesDir = createInstallerWorkDir("installer-ps1-stdin-fixtures")
            val devrigZip = File(fixturesDir, "devrig.zip").also { buildStdinProbeDevrigZip(it) }
            val jdkZip = File(fixturesDir, "jdk.zip").also { buildFakeJdkZip(it) }
            val devrigSha = sha256(devrigZip)
            val jdkSha = sha256(jdkZip)
            makeWorldReadable(fixturesDir)

            val nginx = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(NGINX_IMAGE).logPrefix("installer-ps1-stdin-nginx")
                    .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
            )
            val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP")

            val jdkEntryWin = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "zip", "jdk")
            val jdkEntryPosix = JdkScriptEntry("http://$nginxIp/jdk.zip", jdkSha, "tar.gz", "jdk")
            val table = ALL_PLATFORMS.associateWith { key -> if (key.startsWith("windows-")) jdkEntryWin else jdkEntryPosix }
            val devrig = DevrigEntry(
                url = "http://$nginxIp/devrig.zip", sha256 = devrigSha,
                launcherPosix = "devrig-$version/bin/devrig", launcherWindows = "devrig-$version/bin/devrig.bat",
            )
            val genDir = createInstallerWorkDir("installer-ps1-stdin-gen")
            writeInstallerScripts(genDir.toPath(), table, devrig, version)
            makeWorldReadable(genDir)

            val install = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(pwshImage).logPrefix("installer-pwsh-stdin")
                    .volumes(ContainerVolume(genDir, "/gen", "ro"))
                    .entryPoint("sh", "-c", "apt-get update -qq && apt-get install -y -qq curl >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000"),
            )
            awaitCurlInstalled(install)

            val env = mapOf("HOME" to homeDir, "USERPROFILE" to homeDir)
            // (1) stdin close: the child devrig must see EOF, not block on / receive real input.
            runInstall(install, env)
                .assertExitCode(0) { "install.ps1 (stdin-probe devrig) failed:\n$this" }
                .assertOutputContains("DEVRIG_STDIN_GOT_EOF", message = "devrig child must inherit a closed stdin (immediate EOF)")
                .assertNoMessageInOutput("DEVRIG_STDIN_GOT_DATA")

            // (2) progress restore: dot-source (as `iex` would) with a sentinel value and confirm it is
            //     put back after the script completes — the caller's session must not leak SilentlyContinue.
            val restore = install.startProcessInContainer {
                args(
                    "pwsh", "-NoProfile", "-NonInteractive", "-Command",
                    "\$ProgressPreference='Continue'; . /gen/install.ps1; Write-Output \"PROGRESS_AFTER=\$ProgressPreference\"",
                ).timeoutSeconds(300).description("dot-source install.ps1, check ProgressPreference restored").extraEnv(env)
            }.awaitForProcessFinish()
            restore.assertExitCode(0) { "dot-sourced install.ps1 failed:\n$this" }
                .assertOutputContains("PROGRESS_AFTER=Continue", message = "install.ps1 must restore the caller's \$ProgressPreference (no session leak)")
            log("stdin-close (EOF) + ProgressPreference-restore assertions passed under pwsh")
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun runInstall(c: ContainerDriver, env: Map<String, String>, script: String = "/gen/install.ps1"): ProcessResult =
        c.startProcessInContainer {
            args("pwsh", "-NoProfile", "-NonInteractive", "-File", script)
                .timeoutSeconds(300).description("run generated install.ps1").extraEnv(env)
        }.awaitForProcessFinish()

    private fun sh(c: ContainerDriver, script: String, env: Map<String, String> = mapOf("HOME" to homeDir)): ProcessResult =
        c.startProcessInContainer {
            args("sh", "-c", script).timeoutSeconds(120).description("sh -c").extraEnv(env)
        }.awaitForProcessFinish()

    private fun awaitCurlInstalled(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val r = sh(c, "command -v curl >/dev/null 2>&1 && command -v pwsh >/dev/null 2>&1 && echo TOOLS_OK")
            if (r.exitCode == 0 && "TOOLS_OK" in r.stdout) { log("curl + pwsh present"); return }
            Thread.sleep(2_000)
        }
        error("curl + pwsh were not available in the pwsh container within the timeout (apt-get failed?)")
    }

    private fun verifyMockServes(install: ContainerDriver, nginxIp: String, path: String) {
        val r = sh(install, "curl -fsSL -o /dev/null http://$nginxIp$path && echo SERVED_$path")
        require(r.exitCode == 0 && "SERVED_$path" in r.stdout) {
            "nginx side-car does not serve $path:\nstdout=${r.stdout}\nstderr=${r.stderr}"
        }
        log("mock server serves $path")
    }

    private fun log(msg: String) = println("[InstallerBootstrapPs1Test] $msg")

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Fake devrig dist zip for the PowerShell lane. Top dir `devrig-<version>/`; inside sits
     * `bin/devrig.bat` — a `#!/bin/sh` script disguised as `.bat`. install.ps1 does `& $launcher …`,
     * which on pwsh-Linux is a plain execve on the file, so the shebang wins and the extension is
     * irrelevant. Executable bit is stamped via Apache Commons Compress (`java.util.zip.ZipEntry`
     * cannot carry a unix mode); Expand-Archive preserves it into the unpacked tree.
     *
     * The script records what install.ps1 delegated: `install devrig …` (new flow) vs `install
     * <agent>` (the would-be auto-register, which install.ps1 must NOT do) vs anything else. Mirrors
     * the POSIX buildFakeDevrigZip contract character-for-character on the expected output tokens
     * (`DEVRIG_INSTALL_DEVRIG`, `DEVRIG_INSTALL_CALLED`, `DEVRIG_RAN`, `DEVRIG_JAVA_HOME`).
     */
    private fun buildFakePwshDevrigZip(target: File) {
        val script = buildString {
            append("#!/bin/sh\n")
            append("if [ \"\$1\" = \"install\" ] && [ \"\$2\" = \"devrig\" ]; then\n")
            append("  echo \"DEVRIG_INSTALL_DEVRIG \$*\"\n")
            append("  exit 0\n")
            append("fi\n")
            append("case \"\$1\" in\n")
            append("  install)\n")
            append("    echo \"DEVRIG_INSTALL_CALLED jdk=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("  *)\n")
            append("    echo \"DEVRIG_RAN \$*\"\n")
            append("    echo \"DEVRIG_JAVA_HOME=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("esac\n")
        }.toByteArray()
        ZipArchiveOutputStream(FileOutputStream(target)).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("devrig-$version/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(ZipArchiveEntry("devrig-$version/bin/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(
                ZipArchiveEntry("devrig-$version/bin/devrig.bat").apply {
                    size = script.size.toLong()
                    unixMode = 0b111_101_101 // rwxr-xr-x — install.ps1 does NOT chmod after unpack
                },
            )
            zip.write(script)
            zip.closeArchiveEntry()
        }
    }

    /**
     * Variant fake devrig whose `install devrig` arm READS stdin and records what it saw:
     * `DEVRIG_STDIN_GOT_EOF` (an already-closed stdin — the correct result of install.ps1's `$null |`)
     * vs `DEVRIG_STDIN_GOT_DATA`. `read` on a closed stdin returns non-zero immediately, so the child
     * never blocks; without the close it would hang and the test would time out.
     */
    private fun buildStdinProbeDevrigZip(target: File) {
        val script = buildString {
            append("#!/bin/sh\n")
            append("if [ \"\$1\" = \"install\" ] && [ \"\$2\" = \"devrig\" ]; then\n")
            append("  if IFS= read -r _line; then echo \"DEVRIG_STDIN_GOT_DATA \$_line\"; else echo \"DEVRIG_STDIN_GOT_EOF\"; fi\n")
            append("  echo \"DEVRIG_INSTALL_DEVRIG \$*\"\n")
            append("  exit 0\n")
            append("fi\n")
            append("echo \"DEVRIG_RAN \$*\"\n")
            append("exit 0\n")
        }.toByteArray()
        ZipArchiveOutputStream(FileOutputStream(target)).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("devrig-$version/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(ZipArchiveEntry("devrig-$version/bin/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(
                ZipArchiveEntry("devrig-$version/bin/devrig.bat").apply {
                    size = script.size.toLong()
                    unixMode = 0b111_101_101
                },
            )
            zip.write(script)
            zip.closeArchiveEntry()
        }
    }

    /**
     * Fake JDK zip mirroring [buildFakeJdkTarGz]: top dir `jdk/` (matches `javaHome="jdk"`),
     * with an executable `bin/java` sh stub — the template accepts `bin/java` in place of
     * `bin/java.exe` on pwsh-on-Linux. Windows Corretto/Azul JDKs ship as `.zip` (not `.tar.gz`),
     * so this lane needs the zip form — install.ps1 checks `if ($fmt -ne 'zip')` and fails otherwise.
     */
    private fun buildFakeJdkZip(target: File) {
        val javaStub = "#!/bin/sh\necho 'java-stub 25'\nexit 0\n".toByteArray()
        ZipArchiveOutputStream(FileOutputStream(target)).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("jdk/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(ZipArchiveEntry("jdk/bin/").apply { unixMode = 0b111_101_101 })
            zip.closeArchiveEntry()
            zip.putArchiveEntry(
                ZipArchiveEntry("jdk/bin/java").apply {
                    size = javaStub.size.toLong()
                    unixMode = 0b111_101_101
                },
            )
            zip.write(javaStub)
            zip.closeArchiveEntry()
        }
    }
}
