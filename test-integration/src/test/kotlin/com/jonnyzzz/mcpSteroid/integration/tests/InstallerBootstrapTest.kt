/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.installer.main as runInstallerGenerator
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.queryContainerIp
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P4 integration test for the version.json-driven, generated installer (docs/installer-v8-design.md §8).
 *
 * Drives everything in Kotlin via the test-helper Docker API — no shell driver scripts. Builds tiny
 * fixtures (a fake devrig dist zip + a fake JDK tar.gz), serves them over real HTTP from an nginx
 * side-car, runs the GENERATED install.sh inside an ubuntu container, and asserts the full
 * download → sha256-verify → unpack-verbatim → content-address → launcher → PATH-symlink → auto-install
 * pipeline.
 *
 * The generator (:installer-gen) is invoked directly via its main() so the baked
 * devrig binSubpath = devrig-<version>/bin/devrig matches the fixture archive top dir.
 */
class InstallerBootstrapTest {
    private val version = "0.0.0-test"
    private val nginxImage = "nginx:alpine"
    private val installImage = "ubuntu:24.04"

    /** HOME with a space catches quoting bugs in install.sh / the launcher wrapper. */
    private val homeDir = "/home/tester one"

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `generated install_sh downloads, verifies, unpacks, links and auto-installs over a mock server`() =
        runWithCloseableStack { lifetime ->
            // ── 1. build fixtures into a temp dir ──
            val fixturesDir = createWorkDir("installer-fixtures")
            val devrigZip = File(fixturesDir, "devrig.zip").also { buildFakeDevrigZip(it) }
            val jdkTarGz = File(fixturesDir, "jdk.tar.gz").also { buildFakeJdkTarGz(it) }
            val devrigSha = sha256(devrigZip)
            val jdkSha = sha256(jdkTarGz)
            // The host fixtures dir is read by nginx running as a different uid — make it world-readable.
            makeWorldReadable(fixturesDir)

            // ── 4. start the nginx side-car FIRST (we need its bridge IP before writing coordinates) ──
            val nginx = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest()
                    .image(nginxImage)
                    .logPrefix("installer-nginx")
                    .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
            )
            val nginxIp = nginx.queryContainerIp()
                ?: error("nginx side-car has no bridge IP — cannot serve fixtures")
            log("nginx side-car serving fixtures at http://$nginxIp/")

            // ── 2. write TEMP coordinate files pointing at the mock server ──
            val coordsDir = createWorkDir("installer-coords")
            val jdkCoords = File(coordsDir, "jdk-coordinates.json")
                .also { it.writeText(jdkCoordinatesJson(nginxIp, jdkSha)) }
            val devrigCoords = File(coordsDir, "devrig-coordinates.json")
                .also { it.writeText(devrigCoordinatesJson(nginxIp, devrigSha)) }

            // ── 3. run the generator to produce install.sh into a temp dir ──
            val genDir = createWorkDir("installer-gen-out")
            runInstallerGenerator(
                arrayOf(
                    "--out-dir", genDir.absolutePath,
                    "--jdk-coordinates", jdkCoords.absolutePath,
                    "--devrig-coordinates", devrigCoords.absolutePath,
                    "--version", version,
                )
            )
            val installSh = File(genDir, "install.sh")
            assertTrue(installSh.isFile) { "generator did not produce install.sh at $installSh" }
            makeWorldReadable(genDir)

            // ── 5. start the install container (ubuntu, no JDK, space-in-HOME) ──
            val install = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest()
                    .image(installImage)
                    .logPrefix("installer-ubuntu")
                    .volumes(ContainerVolume(genDir, "/gen", "ro"))
                    .entryPoint(
                        "sh", "-c",
                        "apt-get update -qq && apt-get install -y -qq curl unzip >/dev/null 2>&1; " +
                            "mkdir -p \"$homeDir\"; sleep 3000",
                    ),
            )

            // Wait until apt-get has installed curl+unzip (entrypoint runs them before `sleep 3000`).
            awaitToolsInstalled(install)

            // ── verify nginx really serves the archives (proves the side-car + URL wiring) ──
            // Done from the install container via curl (ubuntu has bash+curl; nginx:alpine has no bash for exec).
            verifyMockServes(install, nginxIp, "/devrig.zip")
            verifyMockServes(install, nginxIp, "/jdk.tar.gz")

            val devrigSha12 = devrigSha.take(12)
            val jdkSha12 = jdkSha.take(12)
            val devrigKey = "devrig-linux-x64-$version-$devrigSha12"
            val jdkKey = "jdk-linux-x64-$version-$jdkSha12"

            // install.sh only symlinks into a writable PATH dir UNDER $HOME (never $BIN_DIR). Pre-create
            // such a dir and put it on PATH for the run so the symlink branch fires deterministically (d).
            val homeBin = "$homeDir/.local/bin"
            sh(install, "mkdir -p \"$homeBin\"").assertExitCode(0) { "could not create $homeBin:\n$this" }
            val runPath = "$homeBin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

            // ── run #1: default (auto-install ON) ──
            val run1 = runInstall(
                install,
                env = mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64", "PATH" to runPath),
            )
            // (a) install.sh exits 0
            run1.assertExitCode(0) { "install.sh run #1 failed:\n$this" }
            val run1All = run1.stdout + "\n" + run1.stderr

            // (e) AUTO-INSTALL: by default install.sh ran 'devrig install'
            assertTrue("DEVRIG_INSTALL_CALLED" in run1All) {
                "run #1 (default) must auto-run 'devrig install' — DEVRIG_INSTALL_CALLED not found:\n$run1All"
            }

            // (a) content-addressed dirs exist
            val binariesLs = sh(install, "ls -1 \"$homeDir/.mcp-steroid/binaries\"")
                .assertExitCode(0) { "could not list binaries dir:\n$this" }.stdout
            assertTrue(binariesLs.lines().any { it == devrigKey }) {
                "expected devrig content-addressed dir '$devrigKey', got:\n$binariesLs"
            }
            assertTrue(binariesLs.lines().any { it == jdkKey }) {
                "expected jdk content-addressed dir '$jdkKey', got:\n$binariesLs"
            }

            // (b) bundled JDK was really downloaded + unpacked verbatim (javaHomeSubpath=jdk)
            sh(install, "test -x \"$homeDir/.mcp-steroid/binaries/$jdkKey/jdk/bin/java\" && echo JDK_JAVA_OK")
                .assertExitCode(0) { "bundled JDK bin/java missing — not downloaded/unpacked:\n$this" }
                .let { assertTrue("JDK_JAVA_OK" in it.stdout) { "JDK bin/java not executable:\n${it.stdout}" } }

            // (c) bin/devrig wrapper exists and, when run, sets DEVRIG_JAVA_HOME to the bundled jdk
            sh(install, "test -x \"$homeDir/.mcp-steroid/bin/devrig\" && echo WRAPPER_OK")
                .assertExitCode(0) { "bin/devrig wrapper missing:\n$this" }
            val mcpRun = sh(install, "\"$homeDir/.mcp-steroid/bin/devrig\" mcp")
                .assertExitCode(0) { "running the devrig wrapper failed:\n$this" }
            val mcpAll = mcpRun.stdout + "\n" + mcpRun.stderr
            assertTrue("DEVRIG_RAN mcp" in mcpAll) { "wrapper did not exec the real devrig:\n$mcpAll" }
            // The wrapper must export DEVRIG_JAVA_HOME pointing at the bundled JDK (the recorder echoes it).
            val expectedJavaHome = "$homeDir/.mcp-steroid/binaries/$jdkKey/jdk"
            assertTrue("DEVRIG_JAVA_HOME=$expectedJavaHome" in mcpAll) {
                "wrapper must set DEVRIG_JAVA_HOME to the bundled jdk ($expectedJavaHome):\n$mcpAll"
            }

            // (d) PATH symlink: a 'devrig' symlink was created in a writable PATH dir under HOME and resolves
            //     to ~/.mcp-steroid/bin/devrig; 'command -v devrig' finds it. We put $HOME/.local/bin on the
            //     install-time PATH (above), so install.sh links there deterministically.
            assertSymlinkCreated(install, homeBin, runPath)

            // (g) idempotent: re-running reuses existing dirs ('already installed')
            val run2 = runInstall(install, env = mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64"))
            run2.assertExitCode(0) { "idempotent re-run failed:\n$this" }
            val run2All = run2.stdout + "\n" + run2.stderr
            assertTrue("already installed: $devrigKey" in run2All && "already installed: $jdkKey" in run2All) {
                "idempotent re-run must report 'already installed' for both artifacts:\n$run2All"
            }

            // ── (f) DEVRIG_NO_AUTO_INSTALL: a fresh HOME with that env must NOT auto-install ──
            val freshHome = "/home/tester two"
            sh(install, "mkdir -p \"$freshHome\"").assertExitCode(0) { "could not create fresh HOME:\n$this" }
            val run3 = runInstall(
                install,
                env = mapOf(
                    "HOME" to freshHome,
                    "DEVRIG_OS" to "linux",
                    "DEVRIG_CPU" to "x64",
                    "DEVRIG_NO_AUTO_INSTALL" to "1",
                ),
            )
            run3.assertExitCode(0) { "DEVRIG_NO_AUTO_INSTALL run failed:\n$this" }
            val run3All = run3.stdout + "\n" + run3.stderr
            assertFalse("DEVRIG_INSTALL_CALLED" in run3All) {
                "DEVRIG_NO_AUTO_INSTALL must NOT run 'devrig install':\n$run3All"
            }
            assertTrue("DEVRIG_NO_AUTO_INSTALL set" in run3All && "skipping" in run3All.lowercase()) {
                "DEVRIG_NO_AUTO_INSTALL run must log that auto-install was skipped:\n$run3All"
            }

            log("ALL INSTALLER ASSERTIONS PASSED (a)-(g)")
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun runInstall(c: ContainerDriver, env: Map<String, String>): ProcessResult =
        c.startProcessInContainer {
            args("sh", "/gen/install.sh")
                .timeoutSeconds(300)
                .description("run generated install.sh")
                .extraEnv(env)
        }.awaitForProcessFinish()

    /** Run a `sh -c <script>` inside the container with the given env (HOME defaults to the spaced home). */
    private fun sh(
        c: ContainerDriver,
        script: String,
        env: Map<String, String> = mapOf("HOME" to homeDir),
    ): ProcessResult =
        c.startProcessInContainer {
            args("sh", "-c", script)
                .timeoutSeconds(120)
                .description("sh -c")
                .extraEnv(env)
        }.awaitForProcessFinish()

    private fun awaitToolsInstalled(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val r = sh(c, "command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 && echo TOOLS_OK")
            if (r.exitCode == 0 && "TOOLS_OK" in r.stdout) {
                log("curl + unzip present in install container")
                return
            }
            Thread.sleep(2_000)
        }
        error("curl + unzip were not installed in the ubuntu container within the timeout (apt-get failed?)")
    }

    private fun verifyMockServes(install: ContainerDriver, nginxIp: String, path: String) {
        val r = sh(install, "curl -fsSL -o /dev/null http://$nginxIp$path && echo SERVED_$path")
        check(r.exitCode == 0 && "SERVED_$path" in r.stdout) {
            "nginx side-car does not serve $path (curl from install container):\nstdout=${r.stdout}\nstderr=${r.stderr}"
        }
        log("mock server serves $path")
    }

    /**
     * (d) Assert install.sh created a 'devrig' symlink in [homeBin] (a writable PATH dir under HOME),
     * that it resolves to ~/.mcp-steroid/bin/devrig, and that `command -v devrig` finds it on [runPath].
     */
    private fun assertSymlinkCreated(c: ContainerDriver, homeBin: String, runPath: String) {
        val r = sh(
            c,
            "set -e; " +
                "test -L \"$homeBin/devrig\"; " +
                "tgt=\$(readlink \"$homeBin/devrig\"); " +
                "[ \"\$tgt\" = \"\$HOME/.mcp-steroid/bin/devrig\" ] && echo \"SYMLINK_OK \$tgt\"; " +
                "command -v devrig >/dev/null 2>&1 && echo CMDV_OK",
            env = mapOf("HOME" to homeDir, "PATH" to runPath),
        )
        val all = r.stdout + "\n" + r.stderr
        assertTrue(r.exitCode == 0 && "SYMLINK_OK" in all && "CMDV_OK" in all) {
            "PATH symlink not created in $homeBin / not resolvable via command -v devrig:\n$all"
        }
        log("PATH symlink verified: $all")
    }

    private fun log(msg: String) = println("[InstallerBootstrapTest] $msg")

    private fun createWorkDir(prefix: String): File {
        val d = File.createTempFile(prefix, "").let { it.delete(); File(it.absolutePath + "-dir") }
        d.mkdirs()
        return d
    }

    private fun makeWorldReadable(dir: File) {
        dir.walkTopDown().forEach {
            it.setReadable(true, false)
            if (it.isDirectory) it.setExecutable(true, false)
        }
        dir.setExecutable(true, false)
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Fake devrig dist zip. Top dir `devrig-<version>/`, containing an executable POSIX-sh `bin/devrig`:
     *  - `install`        → prints `DEVRIG_INSTALL_CALLED jdk=<DEVRIG_JAVA_HOME>` to stdout, exits 0.
     *  - `mcp` / other    → prints `DEVRIG_RAN <args>` AND `DEVRIG_JAVA_HOME=<the env it sees>`.
     */
    private fun buildFakeDevrigZip(target: File) {
        val script = buildString {
            append("#!/bin/sh\n")
            append("# fake devrig used by InstallerBootstrapTest — records what the launcher wrapper passed.\n")
            append("case \"\$1\" in\n")
            append("  install)\n")
            append("    echo \"DEVRIG_INSTALL_CALLED jdk=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("  *)\n")
            append("    echo \"DEVRIG_RAN \$*\"\n")
            append("    echo \"DEVRIG_JAVA_HOME=\${DEVRIG_JAVA_HOME:-}\"\n")
            append("    exit 0 ;;\n")
            append("esac\n")
        }
        // The JDK's ZipEntry has no public unix-mode setter, so the +x bit is NOT preserved in the zip.
        // That is fine: the generated install.sh does `chmod +x "$launcher"` after unpacking the devrig
        // tree, so the launcher path becomes executable regardless of the archived mode.
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("devrig-$version/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/devrig"))
            zip.write(script.toByteArray())
            zip.closeEntry()
        }
    }

    /**
     * Fake JDK tar.gz. Top dir `jdk/` (matches javaHomeSubpath="jdk"), containing an executable
     * `bin/java` sh stub printing `java-stub 25`.
     */
    private fun buildFakeJdkTarGz(target: File) {
        val javaStub = "#!/bin/sh\necho 'java-stub 25'\nexit 0\n".toByteArray()
        GZIPOutputStream(FileOutputStream(target)).use { gz ->
            TarWriter(gz).use { tar ->
                tar.putDir("jdk/")
                tar.putDir("jdk/bin/")
                tar.putFile("jdk/bin/java", javaStub, mode = 0b111_101_101) // rwxr-xr-x
            }
        }
    }

    // ── coordinate files (all 5 platforms share the POSIX fixtures for the POSIX lane) ──

    private fun jdkCoordinatesJson(nginxIp: String, jdkSha: String): String {
        val url = "http://$nginxIp/jdk.tar.gz"
        fun entry() = """
            {
              "vendor": "test-vendor",
              "version": "$version",
              "url": "$url",
              "sha256": "$jdkSha",
              "format": "tar.gz",
              "javaHomeSubpath": "jdk"
            }
        """.trimIndent()
        return """
            {
              "schema": 1,
              "platforms": {
                "linux-x64": ${entry()},
                "linux-arm64": ${entry()},
                "macos-arm64": ${entry()},
                "windows-x64": ${entry()},
                "windows-arm64": ${entry()}
              }
            }
        """.trimIndent()
    }

    private fun devrigCoordinatesJson(nginxIp: String, devrigSha: String): String = """
        {
          "schema": 1,
          "devrig": {
            "url": "http://$nginxIp/devrig.zip",
            "sha256": "$devrigSha",
            "size": 0,
            "format": "zip"
          }
        }
    """.trimIndent()
}

/**
 * Minimal POSIX (ustar) tar writer — the JDK has no built-in tar. Enough for a few small files with
 * stored unix permission bits so the unpacked `bin/java` keeps its +x bit (install.sh checks `-x`).
 */
private class TarWriter(private val out: java.io.OutputStream) : AutoCloseable {
    fun putDir(name: String) = writeEntry(name, ByteArray(0), typeFlag = '5', mode = 0b111_101_101)
    fun putFile(name: String, data: ByteArray, mode: Int) = writeEntry(name, data, typeFlag = '0', mode = mode)

    private fun writeEntry(name: String, data: ByteArray, typeFlag: Char, mode: Int) {
        val header = ByteArray(512)
        putString(header, 0, name, 100)
        putOctal(header, 100, mode.toLong(), 8)            // mode
        putOctal(header, 108, 0, 8)                         // uid
        putOctal(header, 116, 0, 8)                         // gid
        putOctal(header, 124, data.size.toLong(), 12)       // size
        putOctal(header, 136, 0, 12)                        // mtime
        header[156] = typeFlag.code.toByte()                // typeflag
        // ustar magic + version
        putString(header, 257, "ustar", 6)
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte()
        // checksum: spaces first, compute, then write octal
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xff)
        putOctal(header, 148, sum.toLong(), 7)
        header[155] = ' '.code.toByte()

        out.write(header)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun putString(buf: ByteArray, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray()
        val n = minOf(bytes.size, max - 1)
        System.arraycopy(bytes, 0, buf, off, n)
    }

    private fun putOctal(buf: ByteArray, off: Int, value: Long, len: Int) {
        // len-1 octal digits, zero-padded, NUL-terminated
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        val bytes = s.toByteArray()
        System.arraycopy(bytes, 0, buf, off, len - 1)
        buf[off + len - 1] = 0
    }

    override fun close() {
        // two zero blocks terminate the archive
        out.write(ByteArray(1024))
        out.flush()
    }
}
