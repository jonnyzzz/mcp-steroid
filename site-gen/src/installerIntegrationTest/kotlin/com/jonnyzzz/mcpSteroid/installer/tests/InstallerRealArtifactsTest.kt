/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.DevrigCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.LocalJdkArtifact
import com.jonnyzzz.mcpSteroid.installer.inspectJavaHomeSubpath
import com.jonnyzzz.mcpSteroid.installer.parseJdkArg
import com.jonnyzzz.mcpSteroid.installer.main as runInstallerGenerator
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.queryContainerIp
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * End-to-end against the REAL artifacts, using only files Gradle already put on disk: the 5 pinned JDK 25
 * archives (the `--jdk` specs in `test.installer.jdk.specs`) + the built :npx-kt devrig zip
 * (`test.installer.devrig.package.zip`). Serves the container-arch JDK + devrig from an nginx side-car,
 * generates install.sh by running the generator's main() with those specs (URL overridden to the side-car
 * for the served platform; `--devrig-zip` for the local devrig), installs in ubuntu, and proves the bundled
 * JDK is real Corretto 25 AND that the real devrig launches under it. No coordinate JSON: the generator
 * inspects the files ad-hoc.
 */
class InstallerRealArtifactsTest {
    private val devrigZip = File(prop("test.installer.devrig.package.zip"))
    private val nginxImage = "nginx:alpine"
    private val installImage = "ubuntu:24.04"
    private val homeDir = "/home/tester one"

    /** The 5 pinned JDK specs (platform|vendor|version|format|sha256|url|file) → resolved local artifacts. */
    private val artifacts: List<LocalJdkArtifact> by lazy {
        prop("test.installer.jdk.specs").trim().lines().filter { it.isNotBlank() }.map { parseJdkArg(it) }
    }

    private fun specOf(a: LocalJdkArtifact, url: String): String =
        listOf(a.platformKey, a.vendor, a.version, a.format, a.expectedSha256!!, url, a.file.toString()).joinToString("|")

    /** End-to-end: serve the REAL container-arch Corretto + REAL devrig zip from the side-car, generate
     *  install.sh, install in ubuntu, and prove the bundled JDK is real Corretto 25 AND the real devrig runs. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `install_sh installs the real JDK and runs the real devrig from the side-car`() =
        runWithCloseableStack { lifetime ->
            val devrigVersion = readDevrigVersion(devrigZip)
            // Use the JDK matching the container's CPU arch so bin/java runs natively.
            val hostArm64 = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
            val platformKey = if (hostArm64) "linux-arm64" else "linux-x64"
            val cpuToken = if (hostArm64) "arm64" else "x64"
            val served = artifacts.first { it.platformKey == platformKey }
            val realJdk = served.file.toFile()
            require(realJdk.isFile) { "Gradle did not download the $platformKey JDK: $realJdk (run :site-gen:downloadJdks)" }
            require(ZipFile(devrigZip).use { it.getEntry("devrig-$devrigVersion/bin/devrig") != null }) {
                "devrig zip $devrigZip has no devrig-$devrigVersion/bin/devrig entry (version-derivation drift)"
            }

            // Side-car serves ONLY the real local files (no CDN).
            val fixturesDir = createWorkDir("real-fixtures")
            File(fixturesDir, "jdk.tar.gz").also { linkOrCopy(realJdk, it) }
            val servedDevrig = File(fixturesDir, "devrig.zip").also { linkOrCopy(devrigZip, it) }
            makeWorldReadable(fixturesDir)

            val nginx = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(nginxImage).logPrefix("real-nginx")
                    .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
            )
            val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP")
            log("side-car serving real artifacts at http://$nginxIp/")

            // Generate install.sh from the real files: every platform's spec is passed (the generator inspects
            // all 5), but the served platform + devrig point at the side-car so install.sh fetches from it.
            val genDir = createWorkDir("real-gen")
            val jdkArgs = artifacts.flatMap { a ->
                val url = if (a.platformKey == platformKey) "http://$nginxIp/jdk.tar.gz" else a.publicUrl
                listOf("--jdk", specOf(a, url))
            }
            runInstallerGenerator(
                (listOf("--out-dir", genDir.absolutePath, "--version", devrigVersion) + jdkArgs +
                    listOf("--devrig-zip", servedDevrig.absolutePath, "--devrig-url", "http://$nginxIp/devrig.zip")).toTypedArray(),
            )
            require(File(genDir, "install.sh").isFile) { "generator did not produce install.sh" }
            makeWorldReadable(genDir)

            val install = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest().image(installImage).logPrefix("real-ubuntu")
                    .volumes(ContainerVolume(genDir, "/gen", "ro"))
                    .entryPoint(
                        "sh", "-c",
                        "apt-get update -qq && apt-get install -y -qq curl unzip >/dev/null 2>&1; mkdir -p \"$homeDir\"; sleep 3000",
                    ),
            )
            awaitToolsInstalled(install)
            val containerArch = sh(install, "uname -m").stdout.trim()
            val expectedArch = if (cpuToken == "arm64") "aarch64" else "x86_64"
            require(containerArch == expectedArch) {
                "container arch '$containerArch' != expected '$expectedArch' for $platformKey — host os.arch and container arch disagree"
            }
            verifyMockServes(install, nginxIp, "/jdk.tar.gz")
            verifyMockServes(install, nginxIp, "/devrig.zip")

            runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to cpuToken, "DEVRIG_NO_AUTO_INSTALL" to "1"))
                .assertExitCode(0) { "install.sh failed:\n$this" }

            // Content-addressed names use --version + the baked sha (= pinned sha for the JDK; computed for devrig).
            val jdkSha12 = served.expectedSha256!!.take(12)
            val devrigSha12 = DevrigCoordinateResolver.resolve(servedDevrig.toPath(), "http://$nginxIp/devrig.zip").devrig.sha256.take(12)
            val javaHomeSub = inspectJavaHomeSubpath(realJdk.toPath(), served.format, platformKey)
            val jdkHome = "$homeDir/.mcp-steroid/binaries/jdk-$platformKey-$devrigVersion-$jdkSha12/$javaHomeSub"

            // (1) The bundled JDK is the REAL Amazon Corretto 25 (run it).
            sh(install, "\"$jdkHome/bin/java\" -version 2>&1")
                .assertExitCode(0) { "bundled bin/java did not run:\n$this" }
                .assertOutputContains("Corretto-25", message = "bundled JDK is not real Corretto 25")

            // (2) The REAL devrig launches under that bundled JDK (ubuntu has no system Java) via the wrapper.
            sh(install, "test -d \"$homeDir/.mcp-steroid/binaries/devrig-$platformKey-$devrigVersion-$devrigSha12\" && echo DEVRIG_DIR_OK")
                .assertOutputContains("DEVRIG_DIR_OK", message = "devrig content-addressed dir missing")
            sh(install, "\"$homeDir/.mcp-steroid/bin/devrig\" --version 2>&1")
                .assertExitCode(0) { "real devrig --version did not run under the bundled JDK:\n$this" }
                .assertOutputContains(devrigVersion, message = "devrig --version did not report the bundled version")

            log("REAL-ARTIFACT install passed: bundled Corretto 25 runs + real devrig launches on it")
        }

    // ── helpers ──

    private fun runInstall(c: ContainerDriver, env: Map<String, String>): ProcessResult =
        c.startProcessInContainer {
            args("sh", "/gen/install.sh").timeoutSeconds(600).description("run generated install.sh").extraEnv(env)
        }.awaitForProcessFinish()

    private fun sh(c: ContainerDriver, script: String): ProcessResult =
        c.startProcessInContainer {
            args("sh", "-c", script).timeoutSeconds(180).description("sh -c").extraEnv(mapOf("HOME" to homeDir))
        }.awaitForProcessFinish()

    private fun awaitToolsInstalled(c: ContainerDriver) {
        val deadline = System.currentTimeMillis() + 4 * 60_000
        while (System.currentTimeMillis() < deadline) {
            val r = sh(c, "command -v curl >/dev/null 2>&1 && command -v unzip >/dev/null 2>&1 && echo TOOLS_OK")
            if (r.exitCode == 0 && "TOOLS_OK" in r.stdout) return
            Thread.sleep(2_000)
        }
        error("curl + unzip were not installed in the ubuntu container within the timeout")
    }

    private fun verifyMockServes(c: ContainerDriver, nginxIp: String, path: String) {
        val r = sh(c, "curl -fsSL -o /dev/null http://$nginxIp$path && echo SERVED_$path")
        check(r.exitCode == 0 && "SERVED_$path" in r.stdout) { "side-car does not serve $path:\n${r.stdout}\n${r.stderr}" }
    }

    private fun readDevrigVersion(zip: File): String = ZipFile(zip).use { z ->
        z.entries().asSequence().map { it.name.substringBefore('/') }.first { it.startsWith("devrig-") }.removePrefix("devrig-")
    }

    private fun linkOrCopy(src: File, dst: File) {
        try {
            Files.createLink(dst.toPath(), src.toPath())
        } catch (e: Exception) {
            System.err.println("[InstallerRealArtifactsTest] hardlink failed (${e.message}); copying ${src.name}")
            Files.copy(src.toPath(), dst.toPath())
        }
    }

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

    private fun log(msg: String) = println("[InstallerRealArtifactsTest] $msg")

    private fun prop(name: String): String =
        System.getProperty(name) ?: error("required system property '$name' not set (configured in site-gen/build.gradle.kts)")
}
