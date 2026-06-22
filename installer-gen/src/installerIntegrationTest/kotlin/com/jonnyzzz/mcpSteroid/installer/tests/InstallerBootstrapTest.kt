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
 * Integration test for the generated installer: builds tiny fixtures (a fake devrig zip + a fake JDK
 * tar.gz), serves them over real HTTP from an nginx side-car, renders the install.sh from a SYNTHETIC
 * JdkModel pointing at the side-car (via the public [writeInstallerScripts] seam — no real 200 MB JDK
 * download), runs the GENERATED install.sh inside an ubuntu container, and asserts the full
 * download -> sha256-verify -> unpack-verbatim -> content-address -> launcher -> PATH-symlink ->
 * ready-prompt pipeline. The installer does NOT auto-register devrig with agents (that edits agent
 * configs — an explicit `devrig install` step); it just reports the binary is ready. The
 * minimal-but-meaningful lane (glibc/ubuntu); the per-vendor model + render contract are covered by the
 * hermetic unit tests.
 */
class InstallerBootstrapTest {
    private val version = "0.0.0-test"
    private val nginxImage = "nginx:alpine"
    private val installImage = "ubuntu:24.04"
    private val muslImage = "alpine:3.21"

    /** HOME with a space catches quoting bugs in install.sh / the launcher wrapper. */
    private val homeDir = "/home/tester one"

    /**
     * The project's defining platform constraint: musl/Alpine is NOT supported (the IntelliJ IDEs need
     * glibc). The generated install.sh must DETECT musl and fail fast — before any download — with a clear
     * message. Run it in a real Alpine (musl) container and assert the rejection. No nginx/fixtures needed:
     * the musl guard fires before the table lookup / preflight / download.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `generated install_sh refuses musl (alpine)`() = runWithCloseableStack { lifetime ->
        val genDir = createWorkDir("installer-musl-gen")
        // A minimal valid model (nothing is downloaded on the musl-reject path) → renders a real install.sh.
        val table = ALL_PLATFORMS.associateWith { JdkScriptEntry("https://example.com/jdk.tar.gz", "a".repeat(64), "tar.gz", "jdk") }
        writeInstallerScripts(
            genDir.toPath(), table,
            DevrigEntry("https://example.com/devrig.zip", "b".repeat(64), "d/bin/devrig", "d/bin/devrig.bat"),
            version,
        )
        makeWorldReadable(genDir)

        val install = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(muslImage)
                // The test-helper docker-exec transport runs `bash`, absent on Alpine — install it. (The
                // installer itself is still invoked as `sh`, exercising musl/busybox; bash is only the
                // harness's exec shell.)
                .logPrefix("installer-musl")
                .volumes(ContainerVolume(genDir, "/gen", "ro"))
                .entryPoint("sh", "-c", "apk add --no-cache bash >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000"),
        )
        awaitBashReady(install)

        // No DEVRIG_OS/CPU: Alpine auto-detects linux + musl, so install.sh must refuse before any download.
        val r = install.startProcessInContainer {
            args("sh", "/gen/install.sh").timeoutSeconds(120).description("install.sh on alpine/musl")
                .extraEnv(mapOf("HOME" to homeDir))
        }.awaitForProcessFinish()

        require(r.exitCode != 0) { "install.sh must FAIL on musl/Alpine, but exited 0:\n$r" }
        r.assertOutputContains("musl libc (Alpine) is not supported", message = "must explain the musl rejection")
        r.assertNoMessageInOutput("downloading") // refused before any download
        log("musl/Alpine rejection verified (exit ${r.exitCode})")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `generated install_sh end-to-end on ubuntu (glibc)`() = runWithCloseableStack { lifetime ->
        // ── 1. build fixtures into a temp dir ──
        val fixturesDir = createWorkDir("installer-fixtures")
        val devrigZip = File(fixturesDir, "devrig.zip").also { buildFakeDevrigZip(it) }
        val jdkTarGz = File(fixturesDir, "jdk.tar.gz").also { buildFakeJdkTarGz(it) }
        val devrigSha = sha256(devrigZip)
        val jdkSha = sha256(jdkTarGz)
        makeWorldReadable(fixturesDir) // nginx runs as a different uid

        // ── 2. start the nginx side-car FIRST (we need its bridge IP before baking URLs) ──
        val nginx = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(nginxImage)
                .logPrefix("installer-nginx")
                .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
        )
        val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP — cannot serve fixtures")
        log("nginx side-car serving fixtures at http://$nginxIp/")

        // ── 3. render install.sh from a synthetic model: all 5 platforms point at the one fake jdk.tar.gz
        //       (javaHome="jdk"), served by the side-car. This is the new seam — no real JDK download. ──
        val genDir = createWorkDir("installer-gen-out")
        val table = ALL_PLATFORMS.associateWith { JdkScriptEntry("http://$nginxIp/jdk.tar.gz", jdkSha, "tar.gz", "jdk") }
        // The fixture zip unpacks to devrig-<version>/, so that's the computed+asserted launcher subpath.
        val devrig = DevrigEntry(
            url = "http://$nginxIp/devrig.zip", sha256 = devrigSha,
            launcherPosix = "devrig-$version/bin/devrig", launcherWindows = "devrig-$version/bin/devrig.bat",
        )
        writeInstallerScripts(genDir.toPath(), table, devrig, version)
        require(File(genDir, "install.sh").isFile) { "did not produce install.sh in $genDir" }
        makeWorldReadable(genDir)

        // ── 4. start the install container (no JDK, space-in-HOME) ──
        val install = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest()
                .image(installImage)
                .logPrefix("installer-ubuntu")
                .volumes(ContainerVolume(genDir, "/gen", "ro"))
                .entryPoint(
                    "sh", "-c",
                    "apt-get update -qq && apt-get install -y -qq curl unzip >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000",
                ),
        )
        awaitToolsInstalled(install)
        verifyMockServes(install, nginxIp, "/devrig.zip")
        verifyMockServes(install, nginxIp, "/jdk.tar.gz")

        val devrigKey = "devrig-linux-x64-$version-${devrigSha.take(12)}"
        val jdkKey = "jdk-linux-x64-$version-${jdkSha.take(12)}"
        val expectedLauncher = "$homeDir/.mcp-steroid/binaries/$devrigKey/devrig-$version/bin/devrig"
        val expectedJdkHome = "$homeDir/.mcp-steroid/binaries/$jdkKey/jdk"

        // ── run #1: clean HOME → DOWNLOAD both, then DELEGATE launcher+PATH registration to
        //    `devrig install devrig` (the script no longer writes the wrapper). The fake devrig records the
        //    delegation; the real ensureBinLauncher is covered by npx-kt's own tests. ──
        val run1 = runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64"))
            .assertExitCode(0) { "install.sh run #1 failed:\n$this" }
            .assertOutputContains("downloading devrig", "downloading jdk", message = "run #1 (clean HOME) must download both")
            // The script ran the UNPACKED devrig with the correct, computed launcher path + bundled JDK.
            .assertOutputContains(
                "DEVRIG_INSTALL_DEVRIG", "--install-script=$expectedLauncher", "--jdk-home=$expectedJdkHome",
                message = "install.sh must delegate to 'devrig install devrig' with the computed launcher + jdk-home",
            )
            .assertOutputContains("devrig binary is ready", "devrig install", message = "must report ready + how to register with agents")
        run1.assertNoMessageInOutput("DEVRIG_INSTALL_CALLED") // must NOT auto-register with an AGENT

        // (a) content-addressed dirs exist
        sh(install, "ls -1 \"$homeDir/.mcp-steroid/binaries\"")
            .assertExitCode(0) { "could not list binaries dir:\n$this" }
            .assertOutputContains(devrigKey, jdkKey, message = "expected content-addressed dirs")

        // (b) JDK downloaded + unpacked verbatim (javaHome="jdk")
        sh(install, "test -x \"$homeDir/.mcp-steroid/binaries/$jdkKey/jdk/bin/java\" && echo JDK_JAVA_OK")
            .assertOutputContains("JDK_JAVA_OK", message = "JDK bin/java missing — not downloaded/unpacked")

        // (c) idempotent re-run reuses the content-addressed dirs and DOWNLOADS NOTHING
        val reRun = runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to "x64"))
            .assertExitCode(0) { "idempotent re-run failed:\n$this" }
            .assertOutputContains("already installed: $devrigKey", "already installed: $jdkKey", message = "re-run must report 'already installed'")
        reRun.assertNoMessageInOutput("downloading jdk")
        reRun.assertNoMessageInOutput("downloading devrig")

        log("ALL INSTALLER ASSERTIONS PASSED on ubuntu (glibc) — download + delegate to devrig install devrig")
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun runInstall(c: ContainerDriver, env: Map<String, String>): ProcessResult =
        c.startProcessInContainer {
            args("sh", "/gen/install.sh").timeoutSeconds(300).description("run generated install.sh").extraEnv(env)
        }.awaitForProcessFinish()

    private fun sh(c: ContainerDriver, script: String, env: Map<String, String> = mapOf("HOME" to homeDir)): ProcessResult =
        c.startProcessInContainer {
            args("sh", "-c", script).timeoutSeconds(120).description("sh -c").extraEnv(env)
        }.awaitForProcessFinish()

    /** Wait until the docker-exec transport (which runs `bash`) works — i.e. `apk add bash` finished. */
    private fun awaitBashReady(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        // Early probes are EXPECTED to fail (apk is still installing bash), so we retry rather than
        // rethrow per-iteration — but never silently: keep the last failure and surface it if we time
        // out, so a genuine docker/transport fault is diagnosable instead of hidden behind "apk failed?".
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            val r = try { sh(c, "echo BASH_READY") } catch (e: Exception) { lastError = e; null }
            if (r != null && r.exitCode == 0 && "BASH_READY" in r.stdout) { log("bash present in alpine"); return }
            Thread.sleep(2_000)
        }
        error("bash was not installed in the alpine container within the timeout (apk failed?)" +
            (lastError?.let { "; last probe error: $it" } ?: ""))
    }

    private fun awaitToolsInstalled(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val r = sh(c, "command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 && echo TOOLS_OK")
            if (r.exitCode == 0 && "TOOLS_OK" in r.stdout) { log("curl + unzip present"); return }
            Thread.sleep(2_000)
        }
        error("curl + unzip were not installed in the ubuntu container within the timeout (apt-get failed?)")
    }

    private fun verifyMockServes(install: ContainerDriver, nginxIp: String, path: String) {
        val r = sh(install, "curl -fsSL -o /dev/null http://$nginxIp$path && echo SERVED_$path")
        require(r.exitCode == 0 && "SERVED_$path" in r.stdout) {
            "nginx side-car does not serve $path:\nstdout=${r.stdout}\nstderr=${r.stderr}"
        }
        log("mock server serves $path")
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
     * Fake devrig dist zip. Top dir `devrig-<version>/`, with an executable POSIX-sh `bin/devrig`:
     *  - `install`     → prints `DEVRIG_INSTALL_CALLED jdk=<DEVRIG_JAVA_HOME>`, exits 0.
     *  - `mcp` / other → prints `DEVRIG_RAN <args>` AND `DEVRIG_JAVA_HOME=<the env it sees>`.
     */
    private fun buildFakeDevrigZip(target: File) {
        // Records what install.sh delegated: `install devrig …` (the new flow) vs `install <agent>` (the
        // would-be auto-register, which install.sh must NOT do) vs anything else.
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
        }
        // install.sh chmod +x's the launcher after unpack, so the archived mode bit need not survive.
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            zip.putNextEntry(ZipEntry("devrig-$version/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$version/bin/devrig")); zip.write(script.toByteArray()); zip.closeEntry()
        }
    }

    /** Fake JDK tar.gz. Top dir `jdk/` (matches javaHome="jdk"), with an executable `bin/java` sh stub. */
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
        putOctal(header, 100, mode.toLong(), 8)
        putOctal(header, 108, 0, 8)
        putOctal(header, 116, 0, 8)
        putOctal(header, 124, data.size.toLong(), 12)
        putOctal(header, 136, 0, 12)
        header[156] = typeFlag.code.toByte()
        putString(header, 257, "ustar", 6)
        header[263] = '0'.code.toByte(); header[264] = '0'.code.toByte()
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
        System.arraycopy(bytes, 0, buf, off, minOf(bytes.size, max - 1))
    }

    private fun putOctal(buf: ByteArray, off: Int, value: Long, len: Int) {
        val s = java.lang.Long.toOctalString(value).padStart(len - 1, '0')
        System.arraycopy(s.toByteArray(), 0, buf, off, len - 1)
        buf[off + len - 1] = 0
    }

    override fun close() {
        out.write(ByteArray(1024)) // two zero blocks terminate the archive
        out.flush()
    }
}
