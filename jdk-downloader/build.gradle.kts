import de.undercouch.gradle.tasks.download.Download
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.RelativePath
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("de.undercouch.download")
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

val correttoVersion = "21.0.11.10.1"
val correttoBaseUrl = "https://corretto.aws/downloads/resources/$correttoVersion"
// Corretto publishes the PGP key for this release under the signing key id.
val correttoPublicKeyUrl = "$correttoBaseUrl/A122542AB04F24E3.pub"

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

// Resolvable: consumes :pgp-verifier's installDist directory through
// the same "install-dist" Usage attribute :ocr-tesseract already
// exposes. The verifier runs as an external process so :jdk-downloader's
// buildscript classpath stays small.
val pgpVerifierInstallDist by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "install-dist"))
    }
}

dependencies {
    pgpVerifierInstallDist(project(":pgp-verifier"))
}

val pgpVerifierLauncher = pgpVerifierInstallDist.elements.map { elements ->
    val installDir = elements.single().asFile
    installDir.resolve("bin").resolve(
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) "pgp-verifier.bat" else "pgp-verifier"
    )
}

data class CorrettoPlatform(
    val id: String,
    val archiveSuffix: String,
)

data class JdkManifestRow(
    val os: String,
    val cpu: String,
    val downloadUrl: String,
)

data class JdkOverlapFileSnapshot(
    val platform: String,
    val relativePath: String,
    val size: Long,
    val sha256: String,
)

data class JdkOverlapSharedPath(
    val relativePath: String,
    val size: Long,
    val platforms: List<String>,
)

fun jdkOverlapHashFile(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun sha512(file: File): String {
    val digest = MessageDigest.getInstance("SHA-512")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead < 0) break
            if (bytesRead > 0) digest.update(buffer, 0, bytesRead)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun stableJson(value: Any?): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"

fun jdkOverlapRelativePath(root: Path, file: Path): String =
    root.relativize(file).toString().replace(File.separatorChar, '/')

fun jdkOverlapScanPlatform(platformId: String, root: Path): List<JdkOverlapFileSnapshot> {
    require(Files.isDirectory(root)) {
        "missing extracted JDK tree for $platformId: $root; run :jdk-downloader:extractAllJdks first"
    }
    return Files.walk(root, FileVisitOption.FOLLOW_LINKS).use { stream ->
        stream.parallel()
            .filter { file -> Files.isRegularFile(file) }
            .map { file ->
                JdkOverlapFileSnapshot(
                    platform = platformId,
                    relativePath = jdkOverlapRelativePath(root, file),
                    size = Files.size(file),
                    sha256 = jdkOverlapHashFile(file),
                )
            }
            .toList()
    }
}

fun jdkOverlapFormatCount(value: Int): String = String.format(Locale.US, "%,d", value)

fun jdkOverlapFormatBytes(bytes: Long): String {
    val absBytes = abs(bytes.toDouble())
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
        absBytes >= gib -> String.format(Locale.US, "%.1f GB", bytes / gib)
        absBytes >= mib -> String.format(Locale.US, "%.1f MB", bytes / mib)
        absBytes >= kib -> String.format(Locale.US, "%.1f KB", bytes / kib)
        else -> "$bytes B"
    }
}

fun jdkOverlapFormatPercent(part: Long, total: Long): String =
    if (total == 0L) "0.0%" else String.format(Locale.US, "%.1f%%", part * 100.0 / total)

fun jdkOverlapPlatformSummary(platforms: List<String>, platformIds: List<String>): String =
    if (platforms.size == platformIds.size) {
        "${platforms.size} platforms"
    } else {
        "${platforms.size}: ${platforms.joinToString(", ")}"
    }

val correttoPlatforms = listOf(
    CorrettoPlatform("linux-amd64", "linux-x64.tar.gz"),
    CorrettoPlatform("linux-arm", "linux-aarch64.tar.gz"),
    CorrettoPlatform("mac-arm", "macosx-aarch64.tar.gz"),
    CorrettoPlatform("windows-amd64", "windows-x64-jdk.zip"),
)

val jdkManifestRows = mapOf(
    "linux-amd64" to JdkManifestRow(
        os = "linux",
        cpu = "x86-64",
        downloadUrl = "$correttoBaseUrl/amazon-corretto-$correttoVersion-linux-x64.tar.gz",
    ),
    "linux-arm" to JdkManifestRow(
        os = "linux",
        cpu = "arm",
        downloadUrl = "$correttoBaseUrl/amazon-corretto-$correttoVersion-linux-aarch64.tar.gz",
    ),
    "mac-arm" to JdkManifestRow(
        os = "macOs",
        cpu = "arm",
        downloadUrl = "$correttoBaseUrl/amazon-corretto-$correttoVersion-macosx-aarch64.tar.gz",
    ),
    "windows-amd64" to JdkManifestRow(
        os = "windows",
        cpu = "x86-64",
        downloadUrl = "$correttoBaseUrl/amazon-corretto-$correttoVersion-windows-x64-jdk.zip",
    ),
)
require(jdkManifestRows.keys == correttoPlatforms.map { it.id }.toSet()) {
    "jdkManifestRows out of sync with correttoPlatforms — keep both in lockstep"
}

val correttoDownloadDir = layout.buildDirectory.dir("jdk-download")
val correttoExtractDir = layout.buildDirectory.dir("jdk-extracted")
val jdkDownloaderTaskGroup = "jdk downloader"

val downloadAllJdks by tasks.registering {
    group = jdkDownloaderTaskGroup
    description = "Download all pinned Amazon Corretto JDK archives and signature verification files."
}

val downloadCorrettoPublicKey by tasks.registering(Download::class) {
    group = jdkDownloaderTaskGroup
    description = "Download the Amazon Corretto public key used to verify JDK signatures."
    val destFile = correttoDownloadDir.get().asFile.resolve("corretto-pubkey.asc")
    src(correttoPublicKeyUrl)
    dest(destFile)
    configureReliableDownload()
    onlyIf { !destFile.exists() }
}

val extractAllJdks by tasks.registering {
    group = jdkDownloaderTaskGroup
    description = "Extract all pinned Amazon Corretto JDK archives."
    dependsOn(downloadAllJdks)
    outputs.dir(correttoExtractDir)
    outputs.upToDateWhen { false }

    doLast {
        correttoPlatforms.forEach { platform ->
            val root = correttoExtractDir.get().dir(platform.id).asFile
            listOf("legal", "release", "version.txt").forEach { rel ->
                require(root.resolve(rel).exists()) {
                    "missing $rel inside ${platform.id} after extract — " +
                        "Corretto layout may have changed for ${platform.archiveSuffix}"
                }
            }
            if (!root.resolve("README.md").isFile) {
                logger.warn("README.md not found in ${platform.id} extracted tree")
            }
        }
    }
}

val analyzeJdkOverlap by tasks.registering {
    group = "verification"
    description = "Report files duplicated across extracted JDK platforms."
    dependsOn(extractAllJdks)

    val reportFile = layout.buildDirectory.file("jdk-overlap-report.txt")
    outputs.file(reportFile)
    correttoPlatforms.forEach { platform ->
        inputs.dir(correttoExtractDir.map { it.dir(platform.id) })
    }

    doLast {
        val platformIds = correttoPlatforms.map { it.id }

        val snapshots = platformIds
            .flatMap { platform ->
                val root = correttoExtractDir.get().dir(platform).asFile.toPath()
                jdkOverlapScanPlatform(platform, root)
            }
            .sortedWith(compareBy<JdkOverlapFileSnapshot> { it.relativePath }.thenBy { it.platform })
        val snapshotsByRelativePath = snapshots.groupBy { it.relativePath }
        val snapshotsBySharedContent = snapshots.groupBy { it.relativePath to it.sha256 }
        val sharedPaths = snapshotsBySharedContent.values
            .mapNotNull { group ->
                val platforms = group.map { it.platform }.distinct().sorted()
                if (platforms.size < 2) {
                    null
                } else {
                    val sizes = group.map { it.size }.distinct()
                    require(sizes.size == 1) {
                        "SHA-256 collision or inconsistent file size for ${group.first().relativePath}"
                    }
                    JdkOverlapSharedPath(
                        relativePath = group.first().relativePath,
                        size = sizes.single(),
                        platforms = platforms,
                    )
                }
            }
            .sortedWith(
                compareByDescending<JdkOverlapSharedPath> { it.size }
                    .thenByDescending { it.platforms.size }
                    .thenBy { it.relativePath }
            )

        val presentInAllPlatforms = snapshotsByRelativePath.values
            .filter { files -> files.map { it.platform }.distinct().size == platformIds.size }
        val presentInAllBytes = presentInAllPlatforms.sumOf { files -> files.maxOf { it.size } }
        val identicalInAll = sharedPaths.filter { it.platforms.size == platformIds.size }
        val identicalInAllBytes = identicalInAll.sumOf { it.size }
        val identicalInThree = sharedPaths.filter { it.platforms.size == 3 }
        val identicalInThreeBytes = identicalInThree.sumOf { it.size }
        val identicalInTwo = sharedPaths.filter { it.platforms.size == 2 }
        val identicalInTwoBytes = identicalInTwo.sumOf { it.size }
        val uniqueToOnePlatform = snapshotsByRelativePath.values
            .filter { files -> files.map { it.platform }.distinct().size == 1 }
            .map { files -> files.single() }
            .sortedWith(compareByDescending<JdkOverlapFileSnapshot> { it.size }.thenBy { it.relativePath })
        val uniqueToOnePlatformBytes = uniqueToOnePlatform.sumOf { it.size }
        val totalBytes = snapshots.sumOf { it.size }
        val saveableBytes = sharedPaths.sumOf { shared -> shared.size * (shared.platforms.size - 1) }
        val uniqueByPlatform = uniqueToOnePlatform.groupBy { it.platform }

        val reportText = buildString {
            appendLine("Corretto JDK 21 file overlap report")
            appendLine("====================================")
            appendLine("Pinned version: $correttoVersion")
            appendLine("Platforms analyzed: ${platformIds.joinToString(", ")}")
            appendLine()
            appendLine("Summary")
            appendLine("-------")
            appendLine("Total unique relative paths:          ${jdkOverlapFormatCount(snapshotsByRelativePath.size).padStart(10)}")
            appendLine(
                "Paths present in all ${platformIds.size} platforms:      " +
                    "${jdkOverlapFormatCount(presentInAllPlatforms.size).padStart(10)}  " +
                    "(size: ${jdkOverlapFormatBytes(presentInAllBytes)})"
            )
            appendLine(
                "Paths identical across all ${platformIds.size}:          " +
                    "${jdkOverlapFormatCount(identicalInAll.size).padStart(10)}  " +
                    "(size: ${jdkOverlapFormatBytes(identicalInAllBytes)}, " +
                    "${jdkOverlapFormatPercent(identicalInAllBytes, presentInAllBytes)} of \"in all ${platformIds.size}\")"
            )
            appendLine(
                "Paths identical across 3 platforms:      " +
                    "${jdkOverlapFormatCount(identicalInThree.size).padStart(10)}  " +
                    "(size: ${jdkOverlapFormatBytes(identicalInThreeBytes)})"
            )
            appendLine(
                "Paths identical across 2 platforms:      " +
                    "${jdkOverlapFormatCount(identicalInTwo.size).padStart(10)}  " +
                    "(size: ${jdkOverlapFormatBytes(identicalInTwoBytes)})"
            )
            appendLine(
                "Paths unique to a single platform:       " +
                    "${jdkOverlapFormatCount(uniqueToOnePlatform.size).padStart(10)}  " +
                    "(size: ${jdkOverlapFormatBytes(uniqueToOnePlatformBytes)})"
            )
            appendLine(
                "Bytes saveable by shared-overlay:        " +
                    "${jdkOverlapFormatBytes(saveableBytes)} / ${jdkOverlapFormatBytes(totalBytes)} total  " +
                    "(${jdkOverlapFormatPercent(saveableBytes, totalBytes)})"
            )
            appendLine()
            appendLine("Top 50 shared paths by size")
            appendLine("---------------------------")
            sharedPaths.take(50).forEach { shared ->
                appendLine(
                    "${jdkOverlapFormatBytes(shared.size).padStart(10)}  ${shared.relativePath}  " +
                        "(identical in ${jdkOverlapPlatformSummary(shared.platforms, platformIds)})"
                )
            }
            if (sharedPaths.isEmpty()) {
                appendLine("No byte-identical files were found in 2 or more platforms.")
            }
            appendLine()
            appendLine("Per-platform deltas (files unique to each platform)")
            appendLine("---------------------------------------------------")
            platformIds.forEach { platformId ->
                val uniqueFiles = uniqueByPlatform[platformId].orEmpty()
                val uniqueBytes = uniqueFiles.sumOf { it.size }
                appendLine(
                    "${platformId.padEnd(13)}: ${jdkOverlapFormatCount(uniqueFiles.size).padStart(5)} " +
                        "unique files  (size: ${jdkOverlapFormatBytes(uniqueBytes)})"
                )
                uniqueFiles.take(50).forEach { file ->
                    appendLine("   ${jdkOverlapFormatBytes(file.size).padStart(10)}  ${file.relativePath}")
                }
                if (uniqueFiles.size > 50) {
                    appendLine("   ... ${jdkOverlapFormatCount(uniqueFiles.size - 50)} more")
                }
                appendLine()
            }
            appendLine("Method")
            appendLine("------")
            appendLine("SHA-256 hashed on file content only with 64 KB read buffers.")
            appendLine("Relative path = path under build/jdk-extracted/<platform>/.")
            appendLine("Empty files (size 0) are reported the same as any other content.")
            appendLine("Symlinks are followed while walking and hashing platform trees.")
            appendLine("Reports are deterministic — identical extracts produce identical output.")
            appendLine("Path-presence size rows count one canonical copy per relative path, using the largest platform copy when content differs.")
            appendLine("Shared-overlay savings count one canonical copy per identical relativePath + SHA-256 group and remove duplicate platform copies.")
        }

        reportFile.get().asFile.writeText(reportText)
        logger.lifecycle("Wrote overlap report: ${reportFile.get().asFile.absolutePath}")
    }
}

correttoPlatforms.forEach { platform ->
    val archiveFileName = "amazon-corretto-$correttoVersion-${platform.archiveSuffix}"
    val downloadTask = tasks.register<Download>("downloadJdk_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Download Amazon Corretto JDK $correttoVersion for ${platform.id}."
        val destFile = correttoDownloadDir.get().asFile.resolve(archiveFileName)
        src("$correttoBaseUrl/$archiveFileName")
        dest(destFile)
        configureReliableDownload()
        // Corretto artefacts are immutable for a pinned version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
    val downloadSigTask = tasks.register<Download>("downloadJdkSig_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Download the Amazon Corretto JDK $correttoVersion detached PGP signature for ${platform.id}."
        val destFile = correttoDownloadDir.get().asFile.resolve("$archiveFileName.sig")
        src("$correttoBaseUrl/$archiveFileName.sig")
        dest(destFile)
        configureReliableDownload()
        // Corretto signatures are immutable for a pinned version; skip if already on disk.
        onlyIf { !destFile.exists() }
    }
    downloadAllJdks.configure { dependsOn(downloadTask, downloadSigTask, downloadCorrettoPublicKey) }

    val verifyTask = tasks.register<Exec>("verifyJdk_${platform.id}") {
        group = "verification"
        description = "Verify the PGP signature for Amazon Corretto JDK $correttoVersion on ${platform.id}."
        dependsOn(downloadTask, downloadSigTask, downloadCorrettoPublicKey)
        inputs.file(downloadTask.map { it.outputs.files.singleFile })
        inputs.file(downloadSigTask.map { it.outputs.files.singleFile })
        inputs.file(downloadCorrettoPublicKey.map { it.outputs.files.singleFile })
        inputs.file(pgpVerifierLauncher)
        doFirst {
            val archive = downloadTask.get().outputs.files.singleFile
            val signature = downloadSigTask.get().outputs.files.singleFile
            val publicKey = downloadCorrettoPublicKey.get().outputs.files.singleFile
            val launcher = pgpVerifierLauncher.get()
            commandLine(
                launcher.absolutePath,
                archive.absolutePath,
                signature.absolutePath,
                publicKey.absolutePath,
            )
        }
        doLast {
            logger.lifecycle("[jdk-downloader] verified ${platform.id} signature via pgp-verifier")
        }
    }

    val extractTask = tasks.register<Sync>("extractJdk_${platform.id}") {
        group = jdkDownloaderTaskGroup
        description = "Extract Amazon Corretto JDK $correttoVersion for ${platform.id}."
        dependsOn(downloadTask, verifyTask)
        val archiveProvider = downloadTask.map { it.outputs.files.singleFile }
        val isZip = platform.archiveSuffix.endsWith(".zip")
        val tree = archiveProvider.map { archive ->
            if (isZip) zipTree(archive) else tarTree(resources.gzip(archive))
        }
        from(tree) {
            if (platform.id == "mac-arm") {
                // Apple-bundle leftovers that sit outside Contents/Home are not part of
                // the JDK root consumed by downstream distributions.
                exclude("amazon-corretto-21.jdk/Contents/MacOS/**")
                exclude("amazon-corretto-21.jdk/Contents/Info.plist")
                exclude("amazon-corretto-21.jdk/PkgInfo")
            }
            eachFile {
                val parts = relativePath.segments
                if (parts.isEmpty()) {
                    exclude()
                    return@eachFile
                }
                val stripDepth = if (platform.id == "mac-arm") 3 else 1
                if (parts.size <= stripDepth) {
                    exclude()
                    return@eachFile
                }
                relativePath = RelativePath(true, *parts.drop(stripDepth).toTypedArray())
            }
            if (!isZip) {
                filesMatching("**/bin/*") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/jspawnhelper") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/**/*.so") {
                    permissions { unix("rwxr-xr-x") }
                }
                filesMatching("**/lib/**/*.dylib") {
                    permissions { unix("rwxr-xr-x") }
                }
            }
            includeEmptyDirs = false
        }
        into(correttoExtractDir.map { it.dir(platform.id) })
    }
    extractAllJdks.configure { dependsOn(extractTask) }
}

val jdkArchiveFiles = correttoPlatforms.map { platform ->
    tasks.named<Download>("downloadJdk_${platform.id}").map { it.outputs.files.singleFile }
}
val jdkManifestFile = layout.buildDirectory.file("jdk-manifest.json")

val generateJdkManifest by tasks.registering {
    group = "build"
    description = "Emit JDK URL + SHA-512 entries for the npx-kt version.json"
    dependsOn(correttoPlatforms.map { tasks.named("downloadJdk_${it.id}") })
    inputs.property("correttoVersion", correttoVersion)
    inputs.files(jdkArchiveFiles).withPropertyName("jdkArchives")
    outputs.file(jdkManifestFile)

    doLast {
        val entries = correttoPlatforms.map { platform ->
            val row = jdkManifestRows.getValue(platform.id)
            val archive = tasks.named<Download>("downloadJdk_${platform.id}").get().outputs.files.singleFile
            linkedMapOf(
                "name" to "jdk",
                "os" to row.os,
                "cpu" to row.cpu,
                "downloadUrl" to row.downloadUrl,
                "sha-512" to sha512(archive),
            )
        }
        val file = jdkManifestFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(stableJson(entries))
    }
}

val jdkManifestElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-manifest"))
    }
}

artifacts {
    add(jdkManifestElements.name, jdkManifestFile) {
        builtBy(generateJdkManifest)
    }
}

val verifyAllJdks by tasks.registering {
    group = "verification"
    description = "Verify the PGP signature of every Corretto JDK archive."
    dependsOn(correttoPlatforms.map { tasks.named("verifyJdk_${it.id}") })
}

val posixAuditPlatforms = correttoPlatforms.filter { it.id != "windows-amd64" }

val auditJdkPermissions by tasks.registering {
    group = "verification"
    description = "Audit POSIX +x bits on extracted JDK trees (Linux + macOS)."

    doLast {
        logger.lifecycle("[jdk-downloader] permission audit skipped: windows-amd64 (no POSIX modes)")
    }
}

posixAuditPlatforms.forEach { platform ->
    val extract = tasks.named("extractJdk_${platform.id}")
    val audit = tasks.register("auditJdkPermissions_${platform.id}") {
        group = "verification"
        description = "Audit POSIX +x bits on extracted JDK tree for ${platform.id}."
        dependsOn(extract)

        val rootProvider = correttoExtractDir.map { it.dir(platform.id) }
        inputs.dir(rootProvider)

        doLast {
            val root = rootProvider.get().asFile
            val failures = mutableListOf<String>()

            val binDir = root.resolve("bin")
            require(binDir.isDirectory) { "missing bin/ in ${platform.id} extract" }
            requireNotNull(binDir.listFiles()) { "could not list bin/ in ${platform.id} extract" }
                .filter { it.isFile }
                .forEach { file ->
                    if (!file.canExecute()) failures += "bin/${file.name}"
                }

            val jspawnhelper = root.resolve("lib/jspawnhelper")
            require(jspawnhelper.isFile) { "missing lib/jspawnhelper in ${platform.id} extract" }
            if (!jspawnhelper.canExecute()) failures += "lib/jspawnhelper"

            val sharedLibraryExtension = if (platform.id.startsWith("mac-")) ".dylib" else ".so"
            val libDir = root.resolve("lib")
            require(libDir.isDirectory) { "missing lib/ in ${platform.id} extract" }
            val sharedLibraries = libDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(sharedLibraryExtension) }
                .toList()
            require(sharedLibraries.isNotEmpty()) {
                "missing *$sharedLibraryExtension shared libraries in ${platform.id} extract"
            }

            val expectedLibjvm = if (platform.id.startsWith("mac-")) "libjvm.dylib" else "libjvm.so"
            val libjvm = root.resolve("lib/server/$expectedLibjvm")
            require(libjvm.isFile) { "missing lib/server/$expectedLibjvm in ${platform.id} extract" }

            sharedLibraries.forEach { file ->
                if (!file.canExecute()) {
                    failures += file.toRelativeString(root).replace(File.separatorChar, '/')
                }
            }

            require(failures.isEmpty()) {
                "POSIX +x bit was NOT preserved on ${failures.size} file(s) in " +
                    "${platform.id}:\n  - " + failures.joinToString("\n  - ") + "\n" +
                    "Gradle's tarTree silently lost the executable bit; the extract task " +
                    "needs an explicit filesMatching { permissions { unix(...) } } rule " +
                    "for the listed paths."
            }
            logger.lifecycle("[jdk-downloader] permission audit passed: ${platform.id}")
        }
    }
    auditJdkPermissions.configure { dependsOn(audit) }
}

val correttoPlatformElements: Map<String, Configuration> =
    correttoPlatforms.associate { platform ->
        val configName = "jdk${platform.id.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }}Elements"
        val config = configurations.create(configName) {
            isCanBeConsumed = true
            isCanBeResolved = false
            attributes {
                attribute(
                    Usage.USAGE_ATTRIBUTE,
                    objects.named(Usage::class, "jdk-${platform.id}"),
                )
            }
        }

        val builderTask = if (platform.id == "windows-amd64") {
            tasks.named("extractJdk_${platform.id}")
        } else {
            tasks.named("auditJdkPermissions_${platform.id}")
        }
        artifacts.add(config.name, correttoExtractDir.map { it.dir(platform.id) }) {
            builtBy(builderTask)
        }

        platform.id to config
    }

val verifyJdkConfigurations by tasks.registering {
    group = "verification"
    description = "Smoke-test that every jdk-<platform> consumable configuration resolves"
    doLast {
        correttoPlatforms.forEach { platform ->
            val cfgName = correttoPlatformElements.getValue(platform.id).name
            val cfg = project.configurations.getByName(cfgName)
            require(cfg.isCanBeConsumed) { "$cfgName must be consumable" }
            require(!cfg.isCanBeResolved) { "$cfgName must NOT be resolvable" }
            require(cfg.attributes.contains(Usage.USAGE_ATTRIBUTE)) {
                "$cfgName must declare Usage attribute"
            }
            val usage = cfg.attributes.getAttribute(Usage.USAGE_ATTRIBUTE)
            require(usage?.name == "jdk-${platform.id}") {
                "$cfgName Usage mismatch: got $usage, expected jdk-${platform.id}"
            }
            require(cfg.outgoing.artifacts.isNotEmpty()) {
                "$cfgName has no outgoing artifacts"
            }
        }
        logger.lifecycle("[jdk-downloader] all 4 jdk-<platform> configurations look healthy")
    }
}

extractAllJdks.configure { dependsOn(verifyAllJdks, auditJdkPermissions) }

// ── JDK 25 installer coordinates: download all 5 platforms + GENERATE jdk-coordinates.json ──
// Separate from the legacy Corretto-21 path above (which feeds :npx-kt version.json via jdkManifestElements
// and must stay). This pipeline owns the installer's 5-platform JDK 25 set (Corretto x4 + Azul win-arm64),
// downloads every archive (incremental: immutable pinned URLs, skip-if-present), and reuses :installer-gen's
// resolver to infer javaHomeSubpath + verify sha256 + emit jdk-coordinates.json.

// Resolvable classpath for the installer-gen resolver CLI (ResolverMainKt) — reuse, no duplicated logic.
val installerGenRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    installerGenRuntime(project(":installer-gen"))
}

// Pinned source of truth (the only file the daily refresh edits). Parsed at config time for the URLs.
@Suppress("UNCHECKED_CAST")
val jdk25Pinned: Map<String, Map<String, Any?>> = run {
    val f = file("jdk25-pinned.json")
    val root = JsonSlurper().parse(f) as? Map<String, Any?>
        ?: error("jdk25-pinned.json is not a JSON object: $f")
    (root["platforms"] as? Map<String, Map<String, Any?>>)
        ?: error("jdk25-pinned.json: missing 'platforms' object ($f)")
}

val jdk25DownloadDir = layout.buildDirectory.dir("jdk25-download")
val jdk25CoordinatesFile = layout.buildDirectory.file("jdk25-coordinates/jdk-coordinates.json")

val downloadAllJdk25 by tasks.registering {
    group = jdkDownloaderTaskGroup
    description = "Download all 5 pinned JDK 25 packages (Corretto x4 + Azul win-arm64) for the installer coordinates."
}

jdk25Pinned.forEach { (key, entry) ->
    val url = (entry["url"] as? String) ?: error("jdk25-pinned.json: platform '$key' has no url")
    val fileName = url.substringAfterLast('/')
    val dl = tasks.register<Download>("downloadJdk25_$key") {
        group = jdkDownloaderTaskGroup
        description = "Download the $key JDK 25 package ($fileName)."
        val destFile = jdk25DownloadDir.get().asFile.resolve(fileName)
        src(url)
        dest(destFile)
        configureReliableDownload()
        onlyIf { !destFile.exists() } // immutable pinned URL
    }
    downloadAllJdk25.configure { dependsOn(dl) }
}

val jdk25Archives = jdk25Pinned.keys.map { key ->
    tasks.named<Download>("downloadJdk25_$key").map { it.outputs.files.singleFile }
}

val generateJdk25Coordinates by tasks.registering(JavaExec::class) {
    group = jdkDownloaderTaskGroup
    description = "Generate jdk-coordinates.json from the downloaded JDK 25 archives (inferred javaHomeSubpath + verified sha256)."
    dependsOn(downloadAllJdk25)
    classpath = installerGenRuntime
    mainClass.set("com.jonnyzzz.mcpSteroid.installer.resolver.ResolverMainKt")

    val pinned = file("jdk25-pinned.json")
    inputs.file(pinned).withPropertyName("pinned")
    inputs.files(jdk25Archives).withPropertyName("jdk25Archives")
    outputs.file(jdk25CoordinatesFile)

    args(
        "jdk",
        "--source", pinned.absolutePath,
        "--download-dir", jdk25DownloadDir.get().asFile.absolutePath,
        "--out", jdk25CoordinatesFile.get().asFile.absolutePath,
    )
}

// Consumables for :installer-gen (generateInstaller) + :test-integration (side-car + metadata test).
val jdkCoordinatesElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-coordinates")) }
}
val jdk25DownloadElements by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class, "jdk-download-dir")) }
}
artifacts {
    add(jdkCoordinatesElements.name, jdk25CoordinatesFile) { builtBy(generateJdk25Coordinates) }
    add(jdk25DownloadElements.name, jdk25DownloadDir) { builtBy(downloadAllJdk25) }
}
