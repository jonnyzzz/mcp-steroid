@file:Suppress(
    "GrazieInspection",
    "GrazieInspectionRunner",
    "HasPlatformType",
    "SpellCheckingInspection",
    "UnusedReceiverParameter",
)

import de.undercouch.gradle.tasks.download.Download
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.attributes.Usage
import org.gradle.internal.os.OperatingSystem
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream

buildscript {
    dependencies {
        // Used by extractSevenZipBootstrap below to extract the Unix `7zz` from
        // 7-zip.org tar.xz archives at build time. Gradle's built-in `resources.{gzip,bzip2}`
        // helpers don't include xz, so we do the decoding ourselves.
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(25)
}

// `src/buildsrc-shared/kotlin/` carries the IDE compatibility matrix
// (`McpSteroidIdeTargets`), the resolver, the downloader, the unpacker,
// and the LocalIdeProvisioner that ij-plugin/build.gradle.kts calls at
// script-evaluation time. The same path is added as a source dir in
// `buildSrc/build.gradle.kts`, so Gradle scripts and this module's CLI
// compile the same .kt files independently — one source of truth, two
// compiled copies (different classloaders, different Kotlin versions).
// Transitive deps used by the shared code (kotlinx-serialization, slf4j,
// commons-compress, xz) must stay in lockstep with `buildSrc/build.gradle.kts`.
sourceSets.main {
    kotlin.srcDir("src/buildsrc-shared/kotlin")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("com.jonnyzzz.mcpSteroid.ideDownloader.MainKt")
}

dependencies {
    // kotlinx pins read from root gradle.properties so KotlinxRuntimeProbe is
    // compiled against the SAME versions production modules link against;
    // otherwise a paired bump there could leave the probe shipping stale
    // bytecode and miss the very drift it's meant to catch.
    val kotlinxSerialization = providers.gradleProperty("mcp.kotlinx.serialization.version").get()
    val kotlinxCoroutines = providers.gradleProperty("mcp.kotlinx.coroutines.version").get()
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerialization")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinxCoroutines")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.slf4j:slf4j-api:2.0.17")
    // Runtime xz support for IdeUnpacker fallback paths that might handle .tar.xz directly.
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("ch.qos.logback:logback-classic:1.5.32")
}

tasks.test {
    useJUnit {
        excludeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
    // OS-specific skip lives inside each test (`Assume.assumeTrue` / `assumeFalse`).
    // SevenZipLocatorTest needs the Windows-bundled resources → Windows-only.
    // IdeUnpackerSecurityTest hits tar path-separator behavior that differs on
    // Windows (#78) → non-Windows-only.
}

tasks.register<Test>("liveNetworkTest") {
    description = "Runs intellij-downloader tests that intentionally hit public vendor feeds."
    group = "verification"
    useJUnit {
        includeCategories("com.jonnyzzz.mcpSteroid.ideDownloader.LiveNetwork")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

// ────────────────────────────────────────────────────────────────────────────
// Bundled 7-Zip Windows payload — LGPL v2.1+, https://www.7-zip.org/license.txt
//
// We ship the official Windows x64 `7z.exe` + `7z.dll` + `License.txt` tuple.
// The pinned 7-Zip release keeps NSIS and LZMA2 support inside `7z.dll`; there
// are no `Formats/Nsis.dll` or `Codecs/Lzma2.dll` plugin files in this payload.
//
// **Cross-platform build chain.** A devrig distZip built on ANY supported host
// carries the same `7z.exe` + `7z.dll` + `License.txt` so the resulting package
// is OS-agnostic (Mac/Linux build → runs on Windows just fine). The host
// only differs in which off-the-shelf 7-Zip variant is used to bootstrap:
//
//   Linux / macOS:    download `7z<ver>-<platform>.tar.xz`    → extract `7zz`   (NSIS-capable)
//   Windows:          download `7zr.exe` + `7z<ver>-extra.7z` → extract `7za.exe` (NSIS-capable)
//   Common stage 2:   download `7z<ver>-x64.exe`              (NSIS installer)
//   Common stage 3:   use the host's bootstrap extractor to unpack stage 2     → bundled
//                     `7z.exe` + `7z.dll` + `License.txt`
//
// Why two chains: `7zz` ships only as a Unix tarball; the reduced `7zr.exe`
// doesn't support NSIS, so Windows needs the `7zr.exe → 7za.exe → NSIS` hop.
// Both chains converge on stage 3 and produce byte-identical bundled output.
//
// License attribution: see `THIRD_PARTY_NOTICES.md`.
// ────────────────────────────────────────────────────────────────────────────

val sevenZipVersion = "2301"
val sevenZipBaseUrl = "https://www.7-zip.org/a"
val isWindowsHost = OperatingSystem.current().isWindows

val sevenZipBootstrapDir = layout.buildDirectory.dir("7z-bootstrap")
val sevenZipBootstrapExtractedDir = sevenZipBootstrapDir.map { it.dir("extracted") }
val sevenZipDownloadDir = layout.buildDirectory.dir("7z-download")
val sevenZipWindowsStagingDir = layout.buildDirectory.dir("7z-windows-staging")
val sevenZipResourceDir = layout.buildDirectory.dir("7z-extracted")

data class UnixBootstrapPlatform(val id: String, val archiveSuffix: String)

@Suppress("SpellCheckingInspection")
fun resolveUnixBootstrapPlatform(): UnixBootstrapPlatform? {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.isMacOsX -> UnixBootstrapPlatform("mac", "mac.tar.xz")
        os.isLinux -> when (arch) {
            "aarch64", "arm64" -> UnixBootstrapPlatform("linux-arm64", "linux-arm64.tar.xz")
            else -> UnixBootstrapPlatform("linux-x64", "linux-x64.tar.xz")
        }
        os.isWindows -> null    // Windows uses 7zr.exe → 7za.exe instead
        else -> error("Unsupported build host for 7-Zip bootstrap: ${os.name} ($arch)")
    }
}

val unixHostBootstrap: UnixBootstrapPlatform? = resolveUnixBootstrapPlatform()

val downloadConnectTimeoutMs = 30_000
val downloadReadTimeoutMs = 15 * 60_000
val downloadRetryCount = 5

fun Download.configureReliableDownload() {
    onlyIfModified(true)
    connectTimeout(downloadConnectTimeoutMs)
    readTimeout(downloadReadTimeoutMs)
    retries(downloadRetryCount)
    tempAndMove(true)
}

// === Linux / macOS chain — 7zz from per-platform tar.xz ===
//
// Registered only on Unix hosts: `de.undercouch.download` validates `src(...)`
// strings during graph validation (before any onlyIf can short-circuit), so
// we gate registration on the non-null platform.
val downloadUnixSevenZzTarball = unixHostBootstrap?.let { platform ->
    tasks.register<Download>("downloadUnixSevenZzTarball") {
        description = "Stage 1U-a — download 7z${sevenZipVersion}-${platform.archiveSuffix} (Unix 7zz tarball)."
        group = "build setup"
        val destFile = sevenZipBootstrapDir.get().asFile
            .resolve("7z${sevenZipVersion}-${platform.archiveSuffix}")
        src("$sevenZipBaseUrl/7z${sevenZipVersion}-${platform.archiveSuffix}")
        dest(destFile)
        configureReliableDownload()
        onlyIf { !destFile.exists() }
    }
}

val extractUnixSevenZz = tasks.register("extractUnixSevenZz") {
    description = "Stage 1U-b — extract `7zz` (NSIS-capable Unix binary) from the host tarball."
    group = "build setup"
    downloadUnixSevenZzTarball?.let { dependsOn(it) }
    onlyIf { unixHostBootstrap != null }
    val platform = unixHostBootstrap
    val tarballPath = sevenZipBootstrapDir.map { dir ->
        // Stable input path even when platform is null so graph validation succeeds;
        // the file is only ever read inside the doLast (gated by the onlyIf above).
        dir.asFile.resolve("7z${sevenZipVersion}-${platform?.archiveSuffix ?: "unused"}")
    }
    inputs.file(tarballPath)
    outputs.dir(sevenZipBootstrapExtractedDir)

    doLast {
        val outDir = sevenZipBootstrapExtractedDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val tarXz = tarballPath.get()
        BufferedInputStream(tarXz.inputStream()).use { fileStream ->
            XZInputStream(fileStream).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val keepName = when (entry.name) {
                            "7zz", "./7zz" -> "7zz"
                            "License.txt", "./License.txt" -> "License.txt"
                            else -> null
                        } ?: continue
                        val outFile = outDir.resolve(keepName)
                        outFile.outputStream().use { sink -> tar.copyTo(sink) }
                        if (keepName == "7zz") outFile.setExecutable(true, false)
                    }
                }
            }
        }
        require(outDir.resolve("7zz").isFile) {
            "Did not find `7zz` inside ${tarXz.name} — 7-Zip bootstrap archive layout may have changed"
        }
    }
}

// === Windows chain — 7zr.exe → 7za.exe ===
val downloadWindowsSevenZr = if (isWindowsHost) tasks.register<Download>("downloadWindowsSevenZr") {
    description = "Stage 1W-a — download 7zr.exe (Windows reduced single-file .7z extractor)."
    group = "build setup"
    val destFile = sevenZipBootstrapDir.get().asFile.resolve("7zr.exe")
    src("$sevenZipBaseUrl/7zr.exe")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
} else null

val downloadWindowsSevenZipExtra = if (isWindowsHost) tasks.register<Download>("downloadWindowsSevenZipExtra") {
    description = "Stage 1W-b — download 7z${sevenZipVersion}-extra.7z (ships NSIS-capable 7za.exe)."
    group = "build setup"
    val destFile = sevenZipBootstrapDir.get().asFile.resolve("7z${sevenZipVersion}-extra.7z")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-extra.7z")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
} else null

val extractWindowsSevenZa = tasks.register("extractWindowsSevenZa") {
    description = "Stage 1W-c — use 7zr.exe to extract 7za.exe from the extra archive."
    group = "build setup"
    downloadWindowsSevenZr?.let { dependsOn(it) }
    downloadWindowsSevenZipExtra?.let { dependsOn(it) }
    onlyIf { isWindowsHost }
    val sevenZr = sevenZipBootstrapDir.map { it.asFile.resolve("7zr.exe") }
    val extraArchive = sevenZipBootstrapDir.map { it.asFile.resolve("7z${sevenZipVersion}-extra.7z") }
    inputs.file(sevenZr)
    inputs.file(extraArchive)
    outputs.dir(sevenZipBootstrapExtractedDir)

    doLast {
        val outDir = sevenZipBootstrapExtractedDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        providers.exec {
            commandLine(
                sevenZr.get().absolutePath, "x", extraArchive.get().absolutePath,
                "-y", "-o${outDir.absolutePath}",
            )
        }.result.get().assertNormalExitValue()
        val sevenZa = outDir.resolve("7za.exe")
        require(sevenZa.isFile) {
            "Did not find `7za.exe` inside ${extraArchive.get().name} — extra archive layout may have changed"
        }
    }
}

// === Common stage 2 — download the NSIS installer (same on every host) ===
val downloadSevenZipWindowsInstaller = tasks.register<Download>("downloadSevenZipWindowsInstaller") {
    description = "Stage 2 — download the pinned 7-Zip Windows installer (NSIS)."
    group = "build setup"
    val destFile = sevenZipDownloadDir.get().asFile.resolve("7z${sevenZipVersion}-x64.exe")
    src("$sevenZipBaseUrl/7z${sevenZipVersion}-x64.exe")
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
}

// === Common stage 3 — use whichever bootstrap is on this host to unpack the NSIS installer ===
val extractSevenZipResources = tasks.register("extractSevenZipResources") {
    description = "Stage 3 — extract the bundled 7-Zip Windows resources for consumers."
    group = "build setup"
    if (isWindowsHost) dependsOn(extractWindowsSevenZa) else dependsOn(extractUnixSevenZz)
    dependsOn(downloadSevenZipWindowsInstaller)

    val bootstrapExecutable = sevenZipBootstrapExtractedDir.map {
        it.asFile.resolve(if (isWindowsHost) "7za.exe" else "7zz")
    }
    val windowsInstaller = sevenZipDownloadDir.map { it.asFile.resolve("7z${sevenZipVersion}-x64.exe") }
    inputs.file(bootstrapExecutable)
    inputs.file(windowsInstaller)
    inputs.property("sevenZipPayloadLayout", "windows-x64-exe-dll-license-v1")
    outputs.dir(sevenZipResourceDir)

    doLast {
        val extractor = bootstrapExecutable.get()
        require(extractor.isFile) { "Bootstrap extractor is missing: $extractor" }
        if (!isWindowsHost) {
            require(extractor.canExecute()) { "Bootstrap extractor $extractor must be executable" }
        }
        val installer = windowsInstaller.get()
        require(installer.isFile) { "7-Zip Windows installer is missing: $installer" }

        val stagingDir = sevenZipWindowsStagingDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        providers.exec {
            commandLine(extractor.absolutePath, "x", installer.absolutePath, "-y", "-o${stagingDir.absolutePath}")
        }.result.get().assertNormalExitValue()

        val root = sevenZipResourceDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val winDir = root.resolve("7z/win-x64").apply { mkdirs() }
        val payload = mapOf(
            "7z.exe" to "7z.exe",
            "7z.dll" to "7z.dll",
            "License.txt" to "License.txt",
        )
        payload.forEach { (sourceName, targetName) ->
            val source = stagingDir.resolve(sourceName)
            require(source.isFile) {
                "Did not find `$sourceName` inside ${installer.name} — 7-Zip Windows installer layout may have changed"
            }
            source.copyTo(winDir.resolve(targetName), overwrite = true)
        }

        winDir.resolve("License.txt").copyTo(root.resolve("7z/License.txt"), overwrite = true)
    }
}

// Outgoing: the unpacked 7-Zip Windows payload tree
// (`7z/win-x64/{7z.exe,7z.dll,License.txt}` plus shared `7z/License.txt`) for
// consumers that want to bundle the binaries in their own distribution instead
// of pulling them off the classpath. See SevenZipLocator's doc for the
// classpath-resource consumer path that already exists.
val sevenZipBinariesElements = configurations.create("sevenZipBinariesElements") {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "seven-zip-binaries"))
    }
}

artifacts {
    add(sevenZipBinariesElements.name, sevenZipResourceDir.map { it.asFile }) {
        builtBy(extractSevenZipResources)
    }
}

// Two distribution channels for the bundled 7-Zip payload:
//
// 1. :npx-kt's distZip places `7z/7z.exe` + `7z/7z.dll` + `7z/License.txt` at
//    the dist root via the `sevenZipBinariesElements` configuration above.
//    DevrigRoot.sevenZipBinary() returns that path for production devrig runs
//    — no extract-on-first-use, the binary sits next to the launcher.
//
// 2. The intellij-downloader.jar ALSO carries the same payload under its
//    classpath at `7z/win-x64/{7z.exe,7z.dll,License.txt}`. This serves the
//    Gradle-build / test-infra path: LocalIdeProvisioner.resolveAndUnpackLocally
//    runs at Gradle config time when IPGP resolves the IDE classpath — and
//    on Windows that path needs `7z.exe` to unpack the NSIS installer BEFORE
//    `:intellij-downloader:extractSevenZipResources` has had a chance to
//    run as a task. SevenZipLocator (in buildsrc-shared) handles this case
//    by extracting from the JAR to `~/.cache/mcp-steroid/7z/` once per dev
//    machine. The cache extraction is invisible to devrig users (DevrigRoot
//    path wins for them).
sourceSets.named("main") {
    resources.srcDir(extractSevenZipResources)
}
tasks.named("processResources") {
    dependsOn(extractSevenZipResources)
    doFirst {
        delete(layout.buildDirectory.dir("resources/main/7z"))
    }
}
