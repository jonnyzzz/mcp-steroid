/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.installer.DevrigCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinates
import com.jonnyzzz.mcpSteroid.installer.LocalJdkArtifact
import com.jonnyzzz.mcpSteroid.installer.main as runInstallerGenerator
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Block 2 part 2b — validate the coordinate resolvers and the generated install.sh against the REAL
 * artifacts, using only files Gradle already put on disk. Gradle's :installer-gen:downloadAllJdks (the
 * sole real network fetch, cached) provides the 5 real JDK archives; :npx-kt:distZip provides the real
 * devrig package. Inside the test, every download URL points at the nginx side-car serving those local
 * files — the test never reaches a vendor CDN.
 */
class InstallerRealArtifactsTest {
    private val jdkDownloadDir = File(prop("test.integration.jdk.download.dir"))
    private val devrigZip = File(prop("test.integration.devrig.package.zip"))
    private val nginxImage = "nginx:alpine"
    private val installImage = "ubuntu:24.04"
    private val homeDir = "/home/tester one"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val committed: JdkCoordinates by lazy {
        val coordsFile = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("website/installer/jdk-coordinates.json")
        json.decodeFromString(coordsFile.toFile().readText())
    }

    /** No network: inspect the 5 Gradle-downloaded JDKs and assert the resolver reproduces the committed
     *  sha256 + javaHomeSubpath exactly — a regression guard that the committed coordinates match reality. */
    @Test
    fun `resolver reproduces the committed coordinates from the real downloaded JDKs`() {
        val artifacts = committed.platforms.map { (key, e) ->
            val file = File(jdkDownloadDir, e.url.substringAfterLast('/'))
            require(file.isFile) { "Gradle did not download $key: $file (run :installer-gen:downloadAllJdks)" }
            LocalJdkArtifact(key, file.toPath(), e.url, e.vendor, e.version, e.format)
        }
        val resolved = JdkCoordinateResolver.resolve(artifacts)
        committed.platforms.forEach { (key, exp) ->
            val got = resolved.platforms.getValue(key)
            require(exp.sha256 == got.sha256) { "sha256 drift for $key vs committed jdk-coordinates.json: ${exp.sha256} != ${got.sha256}" }
            require(exp.javaHomeSubpath == got.javaHomeSubpath) { "javaHomeSubpath drift for $key: '${exp.javaHomeSubpath}' != '${got.javaHomeSubpath}'" }
        }
        log("resolver reproduced committed sha256 + javaHomeSubpath for all ${resolved.platforms.size} platforms")
    }

    /** End-to-end: serve the REAL linux-x64 Corretto + REAL devrig zip from the side-car, generate
     *  install.sh from resolver output, install in ubuntu, and prove the bundled JDK is real Corretto 25
     *  AND that the real devrig actually launches under it. */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `install_sh installs the real JDK and runs the real devrig from the side-car`() =
        runWithCloseableStack { lifetime ->
            val devrigVersion = readDevrigVersion(devrigZip)
            // Use the JDK matching the container's CPU arch so bin/java runs natively — under default
            // Docker the container is the host arch, and an x86-64 JDK can't exec on an arm64 host.
            val hostArm64 = System.getProperty("os.arch").lowercase().let { it.contains("aarch64") || it.contains("arm64") }
            val platformKey = if (hostArm64) "linux-arm64" else "linux-x64"
            val cpuToken = if (hostArm64) "arm64" else "x64"
            val plat = committed.platforms.getValue(platformKey)
            val realJdk = File(jdkDownloadDir, plat.url.substringAfterLast('/'))
            require(realJdk.isFile) { "Gradle did not download the $platformKey JDK: $realJdk" }

            // Side-car serves ONLY the real local files (no CDN). Keep the real filenames simple for the URL.
            val fixturesDir = createWorkDir("real-fixtures")
            File(fixturesDir, "jdk.tar.gz").also { linkOrCopy(realJdk, it) }
            val servedDevrig = File(fixturesDir, "devrig.zip").also { linkOrCopy(devrigZip, it) }
            makeWorldReadable(fixturesDir)

            val nginx = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest()
                    .image(nginxImage)
                    .logPrefix("real-nginx")
                    .volumes(ContainerVolume(fixturesDir, "/usr/share/nginx/html", "ro")),
            )
            val nginxIp = nginx.queryContainerIp() ?: error("nginx side-car has no bridge IP")
            log("side-car serving real artifacts at http://$nginxIp/")

            // Resolve coordinates from the real local files; linux-x64 + devrig point at the side-car.
            val artifacts = committed.platforms.map { (key, e) ->
                val file = File(jdkDownloadDir, e.url.substringAfterLast('/'))
                val url = if (key == platformKey) "http://$nginxIp/jdk.tar.gz" else e.url
                LocalJdkArtifact(key, file.toPath(), url, e.vendor, e.version, e.format)
            }
            val jdkCoords = JdkCoordinateResolver.resolve(artifacts)
            val devrigCoords = DevrigCoordinateResolver.resolve(servedDevrig.toPath(), "http://$nginxIp/devrig.zip")

            val coordsDir = createWorkDir("real-coords")
            File(coordsDir, "jdk-coordinates.json").writeText(json.encodeToString(jdkCoords) + "\n")
            File(coordsDir, "devrig-coordinates.json").writeText(json.encodeToString(devrigCoords) + "\n")

            // Generate install.sh; --version must match the real devrig zip's top dir so binSubpath lines up.
            val genDir = createWorkDir("real-gen")
            runInstallerGenerator(
                arrayOf(
                    "--out-dir", genDir.absolutePath,
                    "--jdk-coordinates", File(coordsDir, "jdk-coordinates.json").absolutePath,
                    "--devrig-coordinates", File(coordsDir, "devrig-coordinates.json").absolutePath,
                    "--version", devrigVersion,
                )
            )
            require(File(genDir, "install.sh").isFile) { "generator did not produce install.sh" }
            makeWorldReadable(genDir)

            val install = startDockerContainerAndDispose(
                lifetime,
                StartContainerRequest()
                    .image(installImage)
                    .logPrefix("real-ubuntu")
                    .volumes(ContainerVolume(genDir, "/gen", "ro"))
                    .entryPoint(
                        "sh", "-c",
                        "apt-get update -qq && apt-get install -y -qq curl unzip >/dev/null 2>&1; " +
                            "mkdir -p \"$homeDir\"; sleep 3000",
                    ),
            )
            awaitToolsInstalled(install)
            verifyMockServes(install, nginxIp, "/jdk.tar.gz")
            verifyMockServes(install, nginxIp, "/devrig.zip")

            // Install (skip auto 'devrig install' — we only need the bundled JDK + launcher proven).
            runInstall(install, mapOf("HOME" to homeDir, "DEVRIG_OS" to "linux", "DEVRIG_CPU" to cpuToken, "DEVRIG_NO_AUTO_INSTALL" to "1"))
                .assertExitCode(0) { "install.sh failed:\n$this" }

            val jdkSha12 = jdkCoords.platforms.getValue(platformKey).sha256.take(12)
            val devrigSha12 = devrigCoords.devrig.sha256.take(12)
            val jdkHome = "$homeDir/.mcp-steroid/binaries/jdk-$platformKey-$devrigVersion-$jdkSha12/${plat.javaHomeSubpath}"

            // (1) The bundled JDK is the REAL Amazon Corretto 25 (run it).
            sh(install, "\"$jdkHome/bin/java\" -version 2>&1")
                .assertExitCode(0) { "bundled bin/java did not run:\n$this" }
                .assertOutputContains("Corretto", "25", message = "bundled JDK is not real Corretto 25")

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
        val top = z.entries().asSequence().map { it.name.substringBefore('/') }.first { it.startsWith("devrig-") }
        top.removePrefix("devrig-")
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
        System.getProperty(name) ?: error("required system property '$name' not set (configured in test-integration/build.gradle.kts)")
}
