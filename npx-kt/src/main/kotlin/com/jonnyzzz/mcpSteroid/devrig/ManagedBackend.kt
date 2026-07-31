/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeDistribution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndDownload
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.unpackIdeArchive
import com.jonnyzzz.mcpSteroid.ideDownloader.writeIdeStartupConfigFiles
import com.jonnyzzz.mcpSteroid.ideDownloader.writeIdeUserStartupConfigFiles
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.jvm.optionals.getOrNull
import kotlin.streams.asSequence
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipFile

private const val FAILED_START_CLEANUP_MAX_SCANS = 20
private const val FAILED_START_CLEANUP_QUIET_SCANS = 3
private const val FAILED_START_CLEANUP_SCAN_DELAY_MILLIS = 100L

@Serializable
data class BackendDescriptor(
    val schemaVersion: Int = 1,
    val id: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String? = null,
    val bundleDirName: String,
    val launcherPath: String,
    val downloadedAt: String,
    val sourceArchiveSha256: String? = null,
)

data class DownloadResult(
    val id: String,
    val descriptor: BackendDescriptor,
    val backendDir: Path,
    val vmOptionsPath: Path,
)

private data class PreparedBackendInstall(
    val bundleDirName: String,
    val launcher: LauncherResolution,
    val downloadArtifact: BackendDownloadArtifact,
    val downloadedAt: String?,
)

data class StartResult(
    val id: String,
    val pid: Long,
    val ideaLogPath: Path,
    val configPath: Path,
    val alreadyRunning: Boolean = false,
)

data class StopResult(
    val id: String,
    val pid: Long?,
    val outcome: String,
    val message: String? = null,
)

enum class ManagedBackendState {
    INSTALLED,
    RUNNING,
    UNREACHABLE,
}

data class ManagedBackendInfo(
    val id: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String?,
    val installPath: Path,
    val cachePath: Path,
    val runningPid: Long?,
    val state: ManagedBackendState,
)

data class BackendDownloadResolution(
    val product: IdeProduct,
    val version: String,
    val build: String,
    /** @see com.jonnyzzz.mcpSteroid.ideDownloader.IdeArchiveResolution.buildIsBaseline */
    val buildIsBaseline: Boolean = false,
    val url: String,
    val checksumUrl: String? = null,
    val expectedSha256: String? = null,
)

data class BackendDownloadArtifact(
    val sourceArchiveSha256: String?,
    val archivePath: Path? = null,
)

interface ManagedBackendDownloader {
    suspend fun resolve(id: BackendId): BackendDownloadResolution

    suspend fun downloadAndUnpack(
        resolution: BackendDownloadResolution,
        targetDir: Path,
    ): BackendDownloadArtifact
}

interface BundledPluginResolver {
    fun resolveBundledPluginZip(): Path
}

data class ProcessSnapshot(
    val pid: Long,
    val command: String?,
    val arguments: List<String> = emptyList(),
    val startInstant: Instant? = null,
)

enum class ManagedProcessTerminationOutcome {
    TERMINATED,
    NOT_RUNNING,
    IDENTITY_CHANGED,
    FAILED,
}

@Serializable
data class ManagedBackendProcessState(
    val schemaVersion: Int = 1,
    val pid: Long,
    val startInstant: String? = null,
)

interface ManagedProcessInspector {
    fun isAlive(pid: Long): Boolean
    fun allProcesses(): List<ProcessSnapshot>
    fun snapshot(pid: Long): ProcessSnapshot? = allProcesses().firstOrNull { it.pid == pid }
    fun terminateIfMatches(expected: ProcessSnapshot): ManagedProcessTerminationOutcome =
        terminateProcessIfMatches(expected)
}

object DefaultManagedProcessInspector : ManagedProcessInspector {
    override fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).getOrNull()?.isAlive == true

    override fun allProcesses(): List<ProcessSnapshot> =
        ProcessHandle.allProcesses().use { stream ->
            stream.asSequence()
                .map { handle ->
                    val info = handle.info()
                    ProcessSnapshot(
                        pid = handle.pid(),
                        command = info.command().orElse(null),
                        arguments = info.arguments().orElse(emptyArray()).toList(),
                        startInstant = info.startInstant().orElse(null),
                    )
                }
                .toList()
        }

    override fun snapshot(pid: Long): ProcessSnapshot? {
        val handle = ProcessHandle.of(pid).getOrNull() ?: return null
        val info = handle.info()
        return ProcessSnapshot(
            pid = pid,
            command = info.command().orElse(null),
            arguments = info.arguments().orElse(emptyArray()).toList(),
            startInstant = info.startInstant().orElse(null),
        )
    }
}

class ManagedBackendLockException(message: String) : RuntimeException(message)

class ManagedBackendValidationException(message: String) : RuntimeException(message)

interface ManagedBackendService {
    suspend fun download(id: BackendId): DownloadResult
    suspend fun start(id: BackendId): StartResult
    suspend fun stop(id: BackendId): StopResult
}

class ClasspathBundledPluginResolver : BundledPluginResolver {
    override fun resolveBundledPluginZip(): Path {
        val pluginZip = DevrigRoot.ijPluginZip().toAbsolutePath().normalize()
        require(Files.isRegularFile(pluginZip)) {
            "Bundled ij-plugin.zip is missing: $pluginZip. " +
                "Build and launch devrig from :npx-kt:installDist so the bundled plugin is available."
        }
        return pluginZip
    }
}

private fun unpackPluginZip(source: Path, target: Path) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    Files.createDirectories(normalizedTarget)
    ZipFile.builder().setPath(source).get().use { zip ->
        val entries = zip.entries
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val destination = normalizedTarget.resolve(entry.name).normalize()
            require(destination.startsWith(normalizedTarget)) {
                "Plugin ZIP entry escapes target directory: ${entry.name}"
            }
            if (entry.isDirectory) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                }
                if (entry.unixMode and 0b001_000_000 != 0) {
                    destination.toFile().setExecutable(true, false)
                }
            }
        }
    }
}

fun migrateLegacyArchives(homePaths: HomePaths) {
    val legacyDir = homePaths.cachesDir.resolve("_archives")
    if (!Files.isDirectory(legacyDir)) return

    Files.createDirectories(homePaths.downloadsDir)
    Files.list(legacyDir).use { stream ->
        stream.asSequence().forEach { source ->
            val destination = homePaths.downloadsDir.resolve(source.fileName)
            if (!Files.exists(destination)) {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }

    val isEmpty = Files.list(legacyDir).use { stream -> stream.findAny().isEmpty }
    if (isEmpty) {
        Files.deleteIfExists(legacyDir)
    }
}

class DefaultManagedBackendDownloader(
    private val archiveDownloadDir: Path,
    private val os: HostOs = resolveHostOs(),
) : ManagedBackendDownloader {
    override suspend fun resolve(id: BackendId): BackendDownloadResolution = withContext(Dispatchers.IO) {
        val archive = resolveBackendArchive(product = id.product, os = os, version = id.version)
        BackendDownloadResolution(
            product = archive.product,
            version = archive.version,
            build = archive.build,
            buildIsBaseline = archive.buildIsBaseline,
            url = archive.url,
            checksumUrl = archive.checksumUrl,
            expectedSha256 = archive.expectedSha256,
        )
    }

    override suspend fun downloadAndUnpack(
        resolution: BackendDownloadResolution,
        targetDir: Path,
    ): BackendDownloadArtifact = withContext(Dispatchers.IO) {
        val distribution = IdeDistribution.FromUrl(
            product = resolution.product,
            url = resolution.url,
            checksumUrl = resolution.checksumUrl,
            expectedSha256 = resolution.expectedSha256,
        )

        Files.createDirectories(archiveDownloadDir)
        val archive = distribution.resolveAndDownload(archiveDownloadDir.toFile(), os = os)
        unpackIdeArchive(archive, targetDir.toFile(), sevenZipBinary = DevrigRoot.sevenZipBinary())
        // Prefix the unpacked IDE bundle dir with the download's hash, mirroring the cached archive name
        // (`<hash>-<filename>` from IdeDownloader). The hash is the 16-char prefix of the archive file name;
        // this keeps the unpacked tree traceable to its exact binary and consistently named with its download.
        prefixBundleDirWithArchiveHash(targetDir, archive.name.substringBefore('-', missingDelimiterValue = ""))
        BackendDownloadArtifact(
            sourceArchiveSha256 = sha256(archive.toPath()),
            archivePath = archive.toPath().toAbsolutePath().normalize(),
        )
    }
}

/**
 * Renames the single unpacked IDE bundle dir under [targetDir] to `<hash>-<name>`, mirroring the download
 * archive's `<hash>-<filename>` naming so the bundle on disk is traceable to its exact binary. No-op when
 * [hash] is blank or the layout isn't a single top-level dir (resolveBundleDir then validates it).
 */
private fun prefixBundleDirWithArchiveHash(targetDir: Path, hash: String) {
    if (hash.isEmpty()) return
    val dirs = Files.list(targetDir).use { stream ->
        stream.asSequence().filter { Files.isDirectory(it) }.toList()
    }
    val bundle = dirs.singleOrNull() ?: return
    val current = bundle.fileName.toString()
    if (current.startsWith("$hash-")) return
    Files.move(bundle, bundle.resolveSibling("$hash-$current"))
}

class BackendManager(
    private val homePaths: HomePaths,
    private val downloader: ManagedBackendDownloader = DefaultManagedBackendDownloader(
        archiveDownloadDir = homePaths.downloadsDir,
    ),
    private val launcherResolver: LauncherResolver = LauncherResolver(),
    private val backendLauncherResolver: ManagedBackendLauncherResolver = ManagedBackendLauncherResolver(),
    private val bundledPluginResolver: BundledPluginResolver = ClasspathBundledPluginResolver(),
    private val processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
    /**
     * Build range the bundled plugin supports (from its plugin.xml). Backends outside it cannot load
     * the plugin, so download/start refuse them. Null disables the check; production wiring
     * (DevrigServices) passes [bundledPluginBuildRange].
     */
    private val pluginBuildRange: PluginBuildRange? = null,
    private val ideUserHome: Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
    private val stopGracePeriodMillis: Long = 5_000L,
    private val remoteDevelopmentStartupTimeoutMillis: Long = 180_000L,
) : ManagedBackendService {
    init {
        homePaths.mkdirsAll()
        migrateLegacyArchives(homePaths)
    }

    override suspend fun download(id: BackendId): DownloadResult = withContext(Dispatchers.IO) {
        homePaths.mkdirsAll()
        withGlobalBackendOperationLock {
            downloadLocked(id)
        }
    }

    private suspend fun downloadLocked(id: BackendId): DownloadResult {
        val resolution = downloader.resolve(id)
        requirePluginCompatibleBuild(resolution.product, resolution.version, resolution.build)
        val resolved = ResolvedBackendId(resolution.product, resolution.version)
        val running = scanRunningManagedProcesses().firstOrNull { it.backendId == resolved.id }
        if (running != null) {
            throw ManagedBackendLockException(
                "managed backend ${resolved.id} is running (pid ${running.pid}); stop it before downloading that backend",
            )
        }
        val backendDir = homePaths.backendDir(resolved.id)
        val descriptorPath = descriptorPath(backendDir)
        val existingDescriptor = readDescriptorOrNull(descriptorPath)

        val reusablePrepared = if (isReusableBackendInstall(backendDir, existingDescriptor)) {
            try {
                validateReusableDescriptor(existingDescriptor, resolved, resolution)
                val bundleDir = resolveBundleDir(backendDir)
                val launcher = launcherResolver.resolve(bundleDir)
                val artifact = BackendDownloadArtifact(sourceArchiveSha256 = existingDescriptor?.sourceArchiveSha256)
                validateInstalledProductCode(
                    product = resolution.product,
                    actualProductCode = launcher.productCode,
                    downloadedUrl = resolution.url,
                    archivePath = artifact.archivePath,
                    bundleDir = bundleDir,
                    descriptorPath = descriptorPath,
                )
                validateInstalledBuildNumber(
                    product = resolution.product,
                    expectedBuild = resolution.build,
                    expectedBuildIsBaseline = resolution.buildIsBaseline,
                    actualBuildNumber = launcher.buildNumber,
                    downloadedUrl = resolution.url,
                    archivePath = artifact.archivePath,
                    bundleDir = bundleDir,
                    descriptorPath = descriptorPath,
                )
                backendLauncherResolver.validateRequiredAssets(
                    resolution.product.id,
                    launcher.buildNumber,
                    bundleDir,
                )
                PreparedBackendInstall(
                    bundleDirName = bundleDir.fileName.toString(),
                    launcher = launcher,
                    downloadArtifact = artifact,
                    downloadedAt = existingDescriptor?.downloadedAt,
                )
            } catch (e: Exception) {
                System.err.println(
                    "WARN: managed backend install at $backendDir is not reusable and will be replaced: ${e.message}",
                )
                deleteRecursively(backendDir)
                null
            }
        } else {
            null
        }

        val prepared = reusablePrepared ?: run {
            val partialDir = homePaths.backendsDir.resolve("${resolved.id}.partial")
            deleteRecursively(backendDir)
            deleteRecursively(partialDir)
            Files.createDirectories(partialDir)
            try {
                val artifact = downloader.downloadAndUnpack(resolution, partialDir)
                val partialBundleDir = resolveBundleDir(partialDir)
                val launcher = launcherResolver.resolve(partialBundleDir)
                validateInstalledProductCode(
                    product = resolution.product,
                    actualProductCode = launcher.productCode,
                    downloadedUrl = resolution.url,
                    archivePath = artifact.archivePath,
                    bundleDir = partialBundleDir,
                    descriptorPath = descriptorPath(partialDir),
                )
                validateInstalledBuildNumber(
                    product = resolution.product,
                    expectedBuild = resolution.build,
                    expectedBuildIsBaseline = resolution.buildIsBaseline,
                    actualBuildNumber = launcher.buildNumber,
                    downloadedUrl = resolution.url,
                    archivePath = artifact.archivePath,
                    bundleDir = partialBundleDir,
                    descriptorPath = descriptorPath(partialDir),
                )
                backendLauncherResolver.validateRequiredAssets(
                    resolution.product.id,
                    launcher.buildNumber,
                    partialBundleDir,
                )
                Files.move(partialDir, backendDir, StandardCopyOption.ATOMIC_MOVE)
                PreparedBackendInstall(
                    bundleDirName = partialBundleDir.fileName.toString(),
                    launcher = launcher,
                    downloadArtifact = artifact,
                    downloadedAt = null,
                )
            } catch (e: Exception) {
                deleteRecursively(partialDir)
                throw e
            }
        }

        val effectiveBuildNumber = prepared.launcher.buildNumber ?: resolution.build
        val vmOptionsPath = writeBackendVmOptions(homePaths, resolved.id, prepared.bundleDirName)
        val descriptor = BackendDescriptor(
            id = resolved.id,
            productKey = resolution.product.id,
            productCode = prepared.launcher.productCode ?: resolution.product.code,
            version = resolution.version,
            buildNumber = effectiveBuildNumber,
            bundleDirName = prepared.bundleDirName,
            launcherPath = prepared.launcher.launcherPath,
            downloadedAt = prepared.downloadedAt ?: Instant.now().toString(),
            sourceArchiveSha256 = prepared.downloadArtifact.sourceArchiveSha256,
        )
        writeDescriptor(descriptorPath, descriptor)
        deployMcpSteroidPlugin(resolved.id)
        return DownloadResult(resolved.id, descriptor, backendDir, vmOptionsPath)
    }

    /**
     * Refuses a backend whose build the bundled plugin cannot load. Such a backend would start but
     * never write a marker (the plugin would not load), so it could never become reachable — failing
     * fast with a clear message is far better than a silent never-discovered IDE.
     */
    private fun requirePluginCompatibleBuild(product: IdeProduct, version: String, build: String) {
        val range = pluginBuildRange ?: return
        if (range.accepts(build)) return
        throw ManagedBackendValidationException(
            "${product.id} $version (build $build) is not compatible with the bundled MCP Steroid plugin " +
                "(plugin.xml requires ${range.describe()}). The plugin would not load, so the IDE would never " +
                "become reachable. Pick a product/version that satisfies ${range.describe()} — run " +
                "`devrig backend download` and choose one not marked incompatible.",
        )
    }

    private fun isReusableBackendInstall(backendDir: Path, descriptor: BackendDescriptor?): Boolean {
        if (!Files.isDirectory(backendDir)) return false
        if (descriptor != null) {
            return backendDir.resolve(descriptor.bundleDirName).isDirectory()
        }
        return Files.list(backendDir).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() }
                .any { hasProductInfoCandidate(it) }
        }
    }

    private fun validateReusableDescriptor(
        descriptor: BackendDescriptor?,
        resolved: ResolvedBackendId,
        resolution: BackendDownloadResolution,
    ) {
        descriptor ?: return
        val expectedProductCode = resolution.product.installedProductCode
        val mismatches = buildList {
            if (descriptor.id != resolved.id) add("id=${descriptor.id}")
            if (descriptor.productKey != resolution.product.id) add("productKey=${descriptor.productKey}")
            if (descriptor.productCode != expectedProductCode) add("productCode=${descriptor.productCode}")
            if (descriptor.version != resolution.version) add("version=${descriptor.version}")
            if (descriptor.buildNumber != null && !ideBuildMatches(
                    actual = descriptor.buildNumber,
                    expected = resolution.build,
                    expectedIsBaseline = resolution.buildIsBaseline,
                )
            ) {
                add("buildNumber=${descriptor.buildNumber}")
            }
        }
        if (mismatches.isNotEmpty()) {
            throw ManagedBackendValidationException(
                "backend descriptor does not match resolved ${resolved.id} (${resolution.build}): ${mismatches.joinToString()}",
            )
        }
    }

    fun deployMcpSteroidPlugin(id: String): Path {
        val source = bundledPluginResolver.resolveBundledPluginZip()
        require(Files.isRegularFile(source)) { "Bundled ij-plugin.zip is missing: $source" }
        val target = homePaths.cacheDir(id).resolve("plugins/mcp-steroid")
        val partial = homePaths.cacheDir(id).resolve("plugins/.mcp-steroid-unpack.partial")
        deleteRecursively(target)
        deleteRecursively(partial)
        Files.createDirectories(partial)
        try {
            unpackPluginZip(source, partial)
            val unpackedPluginRoot = partial.resolve(MCP_STEROID_PLUGIN_DIR_NAME)
                .takeIf { Files.isDirectory(it) }
                ?: partial
            copyDirectory(unpackedPluginRoot, target)
        } finally {
            deleteRecursively(partial)
        }
        return target
    }

    override suspend fun start(id: BackendId): StartResult = withContext(Dispatchers.IO) {
        homePaths.mkdirsAll()
        withGlobalBackendOperationLock {
            startLocked(id)
        }
    }

    private suspend fun startLocked(id: BackendId): StartResult {
        val resolved = resolveConcreteId(id)
        val descriptor = loadDescriptor(resolved)
        val pidFile = homePaths.pidFile(resolved.id)
        descriptor.buildNumber?.let { build -> requirePluginCompatibleBuild(resolved.product, descriptor.version, build) }
        val running = scanRunningManagedProcesses()
        val other = running.firstOrNull { it.backendId != resolved.id }
        if (other != null) {
            throw ManagedBackendLockException(lockConflictMessage(other))
        }
        val existing = running.firstOrNull { it.backendId == resolved.id && it.ready }
            ?: running.firstOrNull { it.backendId == resolved.id }
        if (existing != null) {
            if (!existing.ready) {
                throw ManagedBackendLockException(
                    "error: managed backend ${existing.backendId} (pid ${existing.pid}) is not ready; " +
                        "wait for its MCP Steroid Remote Development marker or stop it before starting again",
                )
            }
            if (existing.untracked) {
                val startInstant = existing.startInstant ?: throw ManagedBackendLockException(lockConflictMessage(existing))
                writePidFile(pidFile, existing.pid, startInstant)
            }
            return StartResult(
                id = resolved.id,
                pid = existing.pid,
                ideaLogPath = homePaths.cacheDir(resolved.id).resolve("logs/managed.log"),
                configPath = homePaths.cacheDir(resolved.id).resolve("config"),
                alreadyRunning = true,
            )
        }

        val bundleDir = homePaths.backendDir(resolved.id).resolve(descriptor.bundleDirName)
        val launchSpec = backendLauncherResolver.resolve(descriptor, bundleDir)
        require(Files.isExecutable(launchSpec.executable)) {
            "Launcher is not executable: ${launchSpec.executable}"
        }

        writeBackendVmOptions(homePaths, resolved.id, descriptor.bundleDirName)
        // Re-provision the current bundled plugin before launch so a backend downloaded by an older
        // devrig boots with THIS devrig's plugin (writes ideHome → becomes reachable). Only reached when
        // the backend is not already running (the pid-file checks above early-return otherwise), so the
        // plugin dir is never rewritten under a live IDE.
        deployMcpSteroidPlugin(resolved.id)
        val cacheDir = homePaths.cacheDir(resolved.id)
        val logDir = cacheDir.resolve("logs")
        listOf("config", "system", "logs", "plugins").forEach { Files.createDirectories(cacheDir.resolve(it)) }
        writeIdeStartupConfigFiles(cacheDir.resolve("config"))
        writeIdeUserStartupConfigFiles(ideUserHome)

        val managedLog = logDir.resolve("managed.log")
        val expectedPluginHome = cacheDir.resolve("plugins/mcp-steroid")
        val preLaunchProcesses = processInspector.allProcesses().associateBy { it.pid }
        var launcherPid: Long? = null
        var backendPid: Long? = null
        try {
            val spawnedPid = spawnIdeProcess(
                launcher = launchSpec.executable,
                arguments = launchSpec.arguments,
                workDir = launchSpec.workingDirectory,
                stdoutLog = managedLog.toFile(),
                stderrLog = managedLog.toFile(),
                environment = launchSpec.environment,
            )
            launcherPid = spawnedPid
            val launcherSnapshot = processInspector.snapshot(spawnedPid)
            val readyPid = if (backendLauncherResolver.usesRemoteDevelopment(descriptor)) {
                awaitRemoteDevelopmentBackendMarker(
                    backendId = resolved.id,
                    descriptor = descriptor,
                    expectedIdeHome = launchSpec.workingDirectory,
                    expectedPluginHome = expectedPluginHome,
                    launcherPid = spawnedPid,
                    preLaunchProcesses = preLaunchProcesses,
                ).pid
            } else {
                spawnedPid
            }
            backendPid = readyPid
            val readyStartInstant = processInspector.snapshot(readyPid)?.startInstant
                ?: throw ManagedBackendValidationException(
                    "Managed backend '${resolved.id}' pid $readyPid has no process start instant; " +
                        "refusing to persist PID-only state that could later target a reused PID.",
                )
            retireHandedOffLauncher(
                backendId = resolved.id,
                launcherPid = spawnedPid,
                launcherSnapshot = launcherSnapshot,
                backendPid = readyPid,
            )
            writePidFile(pidFile, readyPid, readyStartInstant)
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    terminateFailedBackendStart(
                        descriptor = descriptor,
                        expectedIdeHome = launchSpec.workingDirectory,
                        expectedPluginHome = expectedPluginHome,
                        launcherPid = launcherPid,
                        knownBackendPid = backendPid,
                        pidFile = pidFile,
                        preLaunchProcesses = preLaunchProcesses,
                    )
                } catch (cleanupError: Exception) {
                    System.err.println(
                        "WARN: failed to clean up unsuccessful managed backend '${resolved.id}' start: ${cleanupError.message}",
                    )
                    e.addSuppressed(cleanupError)
                }
            }
            throw e
        }
        return StartResult(
            id = resolved.id,
            pid = backendPid,
            ideaLogPath = managedLog,
            configPath = cacheDir.resolve("config"),
        )
    }

    private fun retireHandedOffLauncher(
        backendId: String,
        launcherPid: Long,
        launcherSnapshot: ProcessSnapshot?,
        backendPid: Long,
    ) {
        if (launcherPid == backendPid || !processInspector.isAlive(launcherPid)) return
        val expectedLauncher = launcherSnapshot
            ?: throw ManagedBackendValidationException(
                "Managed backend '$backendId' handed off from launcher pid $launcherPid to backend pid $backendPid, " +
                    "but the still-live launcher has no process identity; refusing to leave it untracked.",
            )
        when (processInspector.terminateIfMatches(expectedLauncher)) {
            ManagedProcessTerminationOutcome.TERMINATED,
            ManagedProcessTerminationOutcome.NOT_RUNNING,
            ManagedProcessTerminationOutcome.IDENTITY_CHANGED,
            -> Unit

            ManagedProcessTerminationOutcome.FAILED -> throw ManagedBackendValidationException(
                "Managed backend '$backendId' handed off to pid $backendPid, but launcher pid $launcherPid " +
                    "could not be terminated; refusing to leave it untracked.",
            )
        }
    }

    private suspend fun awaitRemoteDevelopmentBackendMarker(
        backendId: String,
        descriptor: BackendDescriptor,
        expectedIdeHome: Path,
        expectedPluginHome: Path,
        launcherPid: Long,
        preLaunchProcesses: Map<Long, ProcessSnapshot>,
    ): PidMarker {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remoteDevelopmentStartupTimeoutMillis)
        val warnedMarkers = mutableSetOf<Path>()
        while (System.nanoTime() < deadlineNanos) {
            findReadyRemoteDevelopmentMarker(
                descriptor,
                expectedIdeHome,
                expectedPluginHome,
                warnedMarkers,
                preLaunchProcesses,
            )?.let { return it }
            delay(100.milliseconds)
        }

        // Do one final scan at the deadline boundary before tearing down the unready launch.
        findReadyRemoteDevelopmentMarker(
            descriptor,
            expectedIdeHome,
            expectedPluginHome,
            warnedMarkers,
            preLaunchProcesses,
        )?.let { return it }
        val launcherAlive = processInspector.isAlive(launcherPid)
        throw ManagedBackendValidationException(
            "Timed out waiting for the MCP Steroid readiness marker for '$backendId' after " +
                "${remoteDevelopmentStartupTimeoutMillis}ms (launcher pid $launcherPid, alive=$launcherAlive). " +
                "The IDE was not recorded as running; inspect ${homePaths.cacheDir(backendId).resolve("logs/managed.log")} " +
                "and ${homePaths.cacheDir(backendId).resolve("logs")}.",
        )
    }

    private fun findReadyRemoteDevelopmentMarker(
        descriptor: BackendDescriptor,
        expectedIdeHome: Path,
        expectedPluginHome: Path,
        warnedMarkers: MutableSet<Path>,
        preLaunchProcesses: Map<Long, ProcessSnapshot>,
    ): PidMarker? {
        val markerDir = PidMarker.markerDirectory(ideUserHome)
        if (!Files.isDirectory(markerDir)) return null
        return Files.list(markerDir).use { stream ->
            stream.asSequence()
                .filter { Files.isRegularFile(it) }
                .mapNotNull { markerPath ->
                    val markerPid = PidMarker.pidFromFileName(markerPath.fileName.toString()) ?: return@mapNotNull null
                    val marker = try {
                        PidMarkerJson.decode(Files.readString(markerPath))
                    } catch (e: Exception) {
                        if (warnedMarkers.add(markerPath)) {
                            System.err.println(
                                "WARN: failed to decode MCP Steroid readiness marker $markerPath: ${e.javaClass.simpleName}",
                            )
                        }
                        return@mapNotNull null
                    }
                    if (marker.pid != markerPid) return@mapNotNull null
                    if (!processInspector.isAlive(marker.pid)) return@mapNotNull null
                    val processSnapshot = processInspector.snapshot(marker.pid) ?: return@mapNotNull null
                    val processStartInstant = processSnapshot.startInstant ?: return@mapNotNull null
                    if (wasPresentBeforeLaunch(processSnapshot, preLaunchProcesses)) return@mapNotNull null
                    if (!markerWasCreatedForProcess(marker, processStartInstant, markerPath, warnedMarkers)) {
                        return@mapNotNull null
                    }
                    val matchesBackend = markerMatchesManagedBackend(
                        marker,
                        descriptor,
                        expectedIdeHome,
                        expectedPluginHome,
                        markerPath,
                        warnedMarkers,
                    )
                    if (!matchesBackend) return@mapNotNull null
                    marker
                }
                .firstOrNull()
        }
    }

    private fun markerMatchesManagedBackend(
        marker: PidMarker,
        descriptor: BackendDescriptor,
        expectedIdeHome: Path,
        expectedPluginHome: Path,
        markerPath: Path,
        warnedMarkers: MutableSet<Path>,
    ): Boolean {
        if (marker.schema != PidMarker.SCHEMA_VERSION) return false
        if (marker.plugin.id != MCP_STEROID_PLUGIN_ID) return false
        if (marker.devrigEndpoint == null) return false
        val expectedBuild = descriptor.buildNumber ?: return false
        // Marker builds carry the product-code prefix (`IC-262.8665.258`) that product-info.json
        // omits, and a descriptor written from a baseline-only resolution holds just `262`.
        if (!ideBuildMatches(
                actual = marker.ide.build,
                expected = expectedBuild,
                expectedIsBaseline = isPlatformBaselineOnly(expectedBuild),
            )
        ) {
            return false
        }
        val markerIdeHome = marker.ideHome ?: return false
        val parsedMarkerHome = try {
            Path.of(markerIdeHome).toAbsolutePath().normalize()
        } catch (e: Exception) {
            if (warnedMarkers.add(markerPath)) {
                System.err.println(
                    "WARN: invalid ideHome in MCP Steroid readiness marker $markerPath: ${e.javaClass.simpleName}",
                )
            }
            return false
        }
        if (parsedMarkerHome != expectedIdeHome.toAbsolutePath().normalize()) return false
        val markerPluginHome = marker.mcpSteroidServer?.pluginPath ?: return false
        val parsedPluginHome = try {
            Path.of(markerPluginHome).toAbsolutePath().normalize()
        } catch (e: Exception) {
            if (warnedMarkers.add(markerPath)) {
                System.err.println(
                    "WARN: invalid pluginPath in MCP Steroid readiness marker $markerPath: ${e.javaClass.simpleName}",
                )
            }
            return false
        }
        return parsedPluginHome == expectedPluginHome.toAbsolutePath().normalize()
    }

    private suspend fun terminateFailedBackendStart(
        descriptor: BackendDescriptor,
        expectedIdeHome: Path,
        expectedPluginHome: Path,
        launcherPid: Long?,
        knownBackendPid: Long?,
        pidFile: Path,
        preLaunchProcesses: Map<Long, ProcessSnapshot>,
    ) {
        val candidatePids = linkedSetOf<Long>()
        launcherPid?.let(candidatePids::add)
        knownBackendPid?.let(candidatePids::add)
        val warnedMarkers = mutableSetOf<Path>()
        var scanCount = 0
        var quietScanCount = 0
        while (scanCount < FAILED_START_CLEANUP_MAX_SCANS && quietScanCount < FAILED_START_CLEANUP_QUIET_SCANS) {
            scanCount++
            findReadyRemoteDevelopmentMarker(
                descriptor,
                expectedIdeHome,
                expectedPluginHome,
                warnedMarkers,
                preLaunchProcesses,
            )?.pid?.let(candidatePids::add)
            val currentProcesses = processInspector.allProcesses()
            for (process in currentProcesses) {
                if (!wasPresentBeforeLaunch(process, preLaunchProcesses) &&
                    processSnapshotMatchesManagedLauncher(process, descriptor)
                ) {
                    candidatePids += process.pid
                }
            }
            val snapshotsByPid = currentProcesses.associateBy { it.pid }
            val ownedProcesses = candidatePids.mapNotNull { pid ->
                if (!processInspector.isAlive(pid)) return@mapNotNull null
                val snapshot = snapshotsByPid[pid] ?: return@mapNotNull null
                val ownedByCommand = processSnapshotMatchesManagedLauncher(snapshot, descriptor)
                snapshot.takeIf {
                    ownedByCommand || pidMarkerMatchesDescriptor(pid, descriptor, snapshot.startInstant)
                }
            }
            var terminationFailed = false
            for (process in ownedProcesses.sortedByDescending { it.pid }) {
                when (processInspector.terminateIfMatches(process)) {
                    ManagedProcessTerminationOutcome.TERMINATED,
                    ManagedProcessTerminationOutcome.NOT_RUNNING,
                    -> {
                        candidatePids.remove(process.pid)
                        deleteMcpMarker(process.pid)
                    }

                    ManagedProcessTerminationOutcome.IDENTITY_CHANGED -> {
                        candidatePids.remove(process.pid)
                    }

                    ManagedProcessTerminationOutcome.FAILED -> terminationFailed = true
                }
            }

            val liveCandidateRemains = candidatePids.any(processInspector::isAlive)
            quietScanCount = if (!terminationFailed && !liveCandidateRemains) quietScanCount + 1 else 0
            if (scanCount < FAILED_START_CLEANUP_MAX_SCANS && quietScanCount < FAILED_START_CLEANUP_QUIET_SCANS) {
                delay(FAILED_START_CLEANUP_SCAN_DELAY_MILLIS.milliseconds)
            }
        }

        val survivingPids = candidatePids.filter(processInspector::isAlive)
        if (survivingPids.isNotEmpty()) {
            throw ManagedBackendValidationException(
                "Failed to clean up unsuccessful managed backend '${descriptor.id}'; " +
                    "candidate processes still alive: ${survivingPids.sorted().joinToString()}.",
            )
        }
        deleteRecursively(pidFile)
    }

    private fun wasPresentBeforeLaunch(
        current: ProcessSnapshot,
        preLaunchProcesses: Map<Long, ProcessSnapshot>,
    ): Boolean {
        val previousStart = preLaunchProcesses[current.pid]?.startInstant ?: return false
        val currentStart = current.startInstant ?: return false
        return previousStart == currentStart
    }

    private fun writePidFile(pidFile: Path, pid: Long, startInstant: Instant) {
        Files.createDirectories(pidFile.parent)
        val state = ManagedBackendProcessState(pid = pid, startInstant = startInstant.toString())
        Files.writeString(pidFile, backendJson.encodeToString(state) + "\n")
    }

    private suspend fun <T> withGlobalBackendOperationLock(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        Files.createDirectories(homePaths.stateDir)
        val lockPath = homePaths.stateDir.resolve("global.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                throw ManagedBackendLockException("another devrig backend operation is in progress; retry shortly")
            }
            lock.use {
                block()
            }
        }
    }

    override suspend fun stop(id: BackendId): StopResult = withContext(Dispatchers.IO) {
        homePaths.mkdirsAll()
        withGlobalBackendOperationLock {
            stopLocked(resolveConcreteId(id))
        }
    }

    private fun stopLocked(resolved: ResolvedBackendId): StopResult {
        val pidFile = homePaths.pidFile(resolved.id)
        val processState = readManagedBackendProcessState(pidFile)
        if (processState == null) {
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = null, outcome = "not running")
        }
        val pid = processState.pid
        val descriptor = loadDescriptor(resolved)

        val handle = ProcessHandle.of(pid).getOrNull()
        if (handle == null || !handle.isAlive) {
            deleteMcpMarker(pid)
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = pid, outcome = "already stopped")
        }
        if (!isManagedBackendProcess(handle, descriptor, processState)) {
            Files.deleteIfExists(pidFile)
            return StopResult(
                id = resolved.id,
                pid = null,
                outcome = "stale",
                message = "pid $pid is no longer the managed backend",
            )
        }

        handle.destroy()
        val graceful = waitForExit(handle, timeoutMillis = stopGracePeriodMillis)
        val outcome = if (graceful) {
            "stopped"
        } else {
            handle.destroyForcibly()
            if (!waitForExit(handle, timeoutMillis = 5_000L)) {
                throw ManagedBackendValidationException(
                    "Managed backend '${resolved.id}' pid $pid is still alive after forced termination; " +
                        "its pid file and marker were retained for a retry.",
                )
            }
            "killed"
        }
        deleteMcpMarker(pid)
        Files.deleteIfExists(pidFile)
        return StopResult(resolved.id, pid = pid, outcome = outcome)
    }

    private fun isManagedBackendProcess(
        handle: ProcessHandle,
        descriptor: BackendDescriptor,
        processState: ManagedBackendProcessState,
    ): Boolean {
        val pid = processState.pid
        val info = handle.info()
        val snapshot = ProcessSnapshot(
            pid = pid,
            command = info.command().orElse(null),
            arguments = info.arguments().orElse(emptyArray()).toList(),
            startInstant = info.startInstant().orElse(null),
        )
        if (processState.startInstant != null) return processState.matchesProcessSnapshot(snapshot)
        return pidMarkerMatchesDescriptor(pid, descriptor, snapshot.startInstant)
    }

    private fun processSnapshotMatchesManagedLauncher(
        process: ProcessSnapshot,
        descriptor: BackendDescriptor,
    ): Boolean {
        val expectedBundle = homePaths.backendDir(descriptor.id)
            .resolve(descriptor.bundleDirName)
            .toAbsolutePath()
            .normalize()
        val commandPath = process.command?.let(::parseAbsoluteProcessPath)

        val expectedLauncher = try {
            backendLauncherResolver.resolve(descriptor, expectedBundle).executable.toAbsolutePath().normalize()
        } catch (e: Exception) {
            System.err.println(
                "WARN: failed to resolve managed backend '${descriptor.id}' while scanning processes: " +
                    e.javaClass.simpleName,
            )
            return false
        }
        return commandPath == expectedLauncher ||
            (commandPath != null && recognizedInterpreterLaunches(commandPath, process.arguments, expectedLauncher))
    }

    private fun recognizedInterpreterLaunches(commandPath: Path, arguments: List<String>, expectedLauncher: Path): Boolean {
        val executableName = commandPath.fileName.toString().lowercase()
        val unixShells = setOf("sh", "bash", "dash", "zsh", "ksh")
        if (executableName in unixShells) {
            return arguments.firstOrNull()?.let(::parseAbsoluteProcessPath) == expectedLauncher
        }
        val windowsShells = setOf("cmd", "cmd.exe")
        if (executableName in windowsShells && arguments.size >= 2 && arguments.first().equals("/c", ignoreCase = true)) {
            return parseAbsoluteProcessPath(arguments[1]) == expectedLauncher
        }
        return false
    }

    private fun pidMarkerMatchesDescriptor(
        pid: Long,
        descriptor: BackendDescriptor,
        processStartInstant: Instant?,
    ): Boolean {
        processStartInstant ?: return false
        val markerPath = markerPathsForPid(pid).firstOrNull { Files.isRegularFile(it) } ?: return false
        val marker = try {
            PidMarkerJson.decode(Files.readString(markerPath))
        } catch (e: Exception) {
            System.err.println("WARN: failed to decode MCP Steroid marker file $markerPath: ${e.javaClass.simpleName}")
            return false
        }
        if (marker.pid != pid) return false
        if (!markerWasCreatedForProcess(marker, processStartInstant, markerPath, mutableSetOf())) return false
        val bundleDir = homePaths.backendDir(descriptor.id).resolve(descriptor.bundleDirName)
        val expectedIdeHome = try {
            backendLauncherResolver.resolve(descriptor, bundleDir).workingDirectory
        } catch (e: Exception) {
            System.err.println("WARN: failed to resolve managed backend '${descriptor.id}' while validating pid $pid: ${e.message}")
            return false
        }
        return markerMatchesManagedBackend(
            marker = marker,
            descriptor = descriptor,
            expectedIdeHome = expectedIdeHome,
            expectedPluginHome = homePaths.cacheDir(descriptor.id).resolve("plugins/mcp-steroid"),
            markerPath = markerPath,
            warnedMarkers = mutableSetOf(),
        )
    }

    private fun markerWasCreatedForProcess(
        marker: PidMarker,
        processStartInstant: Instant,
        markerPath: Path,
        warnedMarkers: MutableSet<Path>,
    ): Boolean {
        val markerCreatedAt = try {
            Instant.parse(marker.createdAt)
        } catch (e: Exception) {
            if (warnedMarkers.add(markerPath)) {
                System.err.println(
                    "WARN: invalid createdAt in MCP Steroid marker file $markerPath: ${e.javaClass.simpleName}",
                )
            }
            return false
        }
        return !markerCreatedAt.isBefore(processStartInstant)
    }

    private fun deleteMcpMarker(pid: Long) {
        for (marker in markerPathsForPid(pid)) {
            try {
                Files.deleteIfExists(marker)
            } catch (e: Exception) {
                System.err.println("WARN: failed to delete MCP Steroid marker file $marker: ${e.message}")
            }
        }
    }

    private fun markerPathsForPid(pid: Long): List<Path> = listOf(
        PidMarker.markerDirectory(ideUserHome).resolve(PidMarker.markerFileNameFor(pid)),
    )

    fun list(): List<ManagedBackendInfo> {
        if (!Files.isDirectory(homePaths.backendsDir)) return emptyList()
        val snapshotsByPid = processInspector.allProcesses().associateBy { it.pid }
        return Files.list(homePaths.backendsDir).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { dir ->
                    try {
                        val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                        val processState = readManagedBackendProcessState(homePaths.pidFile(descriptor.id))
                        val alivePid = processState
                            ?.takeIf { isOwnedManagedBackendProcess(it, descriptor, snapshotsByPid) }
                            ?.pid
                        val state = when {
                            alivePid != null -> ManagedBackendState.RUNNING
                            processState != null -> ManagedBackendState.UNREACHABLE
                            else -> ManagedBackendState.INSTALLED
                        }
                        ManagedBackendInfo(
                            id = descriptor.id,
                            productKey = descriptor.productKey,
                            productCode = descriptor.productCode,
                            version = descriptor.version,
                            buildNumber = descriptor.buildNumber,
                            installPath = dir,
                            cachePath = homePaths.cacheDir(descriptor.id),
                            runningPid = alivePid,
                            state = state,
                        )
                    } catch (e: Exception) {
                        System.err.println("WARN: failed to read managed backend metadata from $dir: ${e.message}")
                        null
                    }
                }
                .sortedWith(compareBy({ it.productKey }, { it.version }))
                .toList()
        }
    }

    private suspend fun resolveConcreteId(id: BackendId): ResolvedBackendId {
        if (id.version != null) return ResolvedBackendId(id.product, id.version)
        findHighestInstalledBackend(id.product)?.let { return it }
        val resolution = downloader.resolve(id)
        return ResolvedBackendId(resolution.product, resolution.version)
    }

    private fun findHighestInstalledBackend(product: IdeProduct): ResolvedBackendId? {
        if (!Files.isDirectory(homePaths.backendsDir)) return null
        val prefix = "${product.id}-"
        return Files.list(homePaths.backendsDir).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { dir ->
                    val dirName = dir.fileName.toString()
                    if (!dirName.startsWith(prefix)) return@mapNotNull null
                    val version = dirName.removePrefix(prefix)
                    if (!isSupportedBackendVersion(version)) return@mapNotNull null
                    val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                    if (descriptor.id != dirName || descriptor.productKey != product.id || descriptor.version != version) {
                        return@mapNotNull null
                    }
                    ResolvedBackendId(product, version)
                }
                .maxWithOrNull { left, right -> compareBackendVersions(left.version, right.version) }
        }
    }

    private fun loadDescriptor(id: ResolvedBackendId): BackendDescriptor {
        val path = descriptorPath(homePaths.backendDir(id.id))
        return readDescriptorOrNull(path)
            ?: error("Managed backend '${id.id}' is not installed. Run `devrig backend download ${id.product.id}` first.")
    }

    private fun scanRunningManagedProcesses(): List<RunningManagedProcess> {
        val byId = mutableListOf<RunningManagedProcess>()
        val trackedPids = mutableSetOf<Long>()
        val trackedIds = mutableSetOf<String>()
        val processSnapshots = processInspector.allProcesses()
        val snapshotsByPid = processSnapshots.associateBy { it.pid }
        if (Files.isDirectory(homePaths.stateDir)) {
            Files.list(homePaths.stateDir).use { stream ->
                stream.asSequence()
                    .filter { it.fileName.toString().endsWith(".pid") }
                    .forEach { pidFile ->
                        val backendId = pidFile.fileName.toString().removeSuffix(".pid")
                        val processState = readManagedBackendProcessState(pidFile)
                        val descriptor = readDescriptorOrNull(descriptorPath(homePaths.backendDir(backendId)))
                        if (processState != null && descriptor != null &&
                            isOwnedManagedBackendProcess(processState, descriptor, snapshotsByPid)
                        ) {
                            trackedPids += processState.pid
                            trackedIds += backendId
                            byId += RunningManagedProcess(
                                backendId = backendId,
                                pid = processState.pid,
                                startInstant = processState.parsedStartInstant(),
                                untracked = false,
                                ready = true,
                            )
                        } else {
                            Files.deleteIfExists(pidFile)
                        }
                    }
            }
        }

        for (process in processSnapshots) {
            if (process.pid in trackedPids) continue
            for (backendId in backendIdsFromProcess(process)) {
                if (backendId in trackedIds) continue
                val descriptor = readDescriptorOrNull(descriptorPath(homePaths.backendDir(backendId))) ?: continue
                val launcherMatches = processSnapshotMatchesManagedLauncher(process, descriptor)
                val markerMatches = pidMarkerMatchesDescriptor(process.pid, descriptor, process.startInstant)
                if (!launcherMatches && !markerMatches) continue
                val ready = markerMatches || !backendLauncherResolver.usesRemoteDevelopment(descriptor)
                byId += RunningManagedProcess(
                    backendId = backendId,
                    pid = process.pid,
                    startInstant = process.startInstant,
                    untracked = true,
                    ready = ready,
                )
                break
            }
        }

        return byId.sortedWith(compareBy({ it.backendId }, { it.pid }))
    }

    private fun isOwnedManagedBackendProcess(
        processState: ManagedBackendProcessState,
        descriptor: BackendDescriptor,
        snapshotsByPid: Map<Long, ProcessSnapshot>,
    ): Boolean {
        val pid = processState.pid
        if (!processInspector.isAlive(pid)) return false
        val snapshot = snapshotsByPid[pid] ?: processInspector.snapshot(pid) ?: return false
        if (processState.startInstant != null) return processState.matchesProcessSnapshot(snapshot)
        return pidMarkerMatchesDescriptor(pid, descriptor, snapshot.startInstant)
    }

    private fun backendIdsFromProcess(process: ProcessSnapshot): List<String> =
        (listOfNotNull(process.command).asSequence() + process.arguments.asSequence())
            .mapNotNull(::backendIdFromCommand)
            .distinct()
            .toList()

    private fun backendIdFromCommand(command: String?): String? {
        if (command.isNullOrBlank()) return null
        val backendsRoot = homePaths.backendsDir.toAbsolutePath().normalize()
        val commandPath = parseAbsoluteProcessPath(command) ?: return null
        if (!commandPath.startsWith(backendsRoot)) return null
        val relative = backendsRoot.relativize(commandPath)
        if (relative.nameCount < 2) return null
        return relative.getName(0).toString().takeIf { it.isNotBlank() }
    }

    private fun parseAbsoluteProcessPath(rawPath: String): Path? {
        val path = try {
            Path.of(rawPath)
        } catch (e: Exception) {
            System.err.println(
                "WARN: ignored an invalid process path while scanning managed backends: ${e.javaClass.simpleName}",
            )
            return null
        }
        if (!path.isAbsolute) return null
        return path.toAbsolutePath().normalize()
    }

    private fun lockConflictMessage(process: RunningManagedProcess): String = buildString {
        appendLine("error: another managed backend is already running: ${process.backendId} (pid ${process.pid})")
        append("stop it first:  devrig backend stop ${process.backendId}")
        if (process.untracked) {
            appendLine()
            append("cleanup stale state under ${homePaths.stateDir} if this process is no longer managed")
        }
    }
}

private data class RunningManagedProcess(
    val backendId: String,
    val pid: Long,
    val startInstant: Instant?,
    val untracked: Boolean,
    val ready: Boolean,
)

fun writeBackendVmOptions(homePaths: HomePaths, id: String, bundleDirName: String): Path {
    val cacheDir = homePaths.cacheDir(id).toAbsolutePath().normalize()
    listOf("config", "system", "logs", "plugins").forEach { Files.createDirectories(cacheDir.resolve(it)) }
    Files.createDirectories(homePaths.backendDir(id))
    val path = homePaths.backendDir(id).resolve("$bundleDirName.vmoptions")
    val content = buildString {
        appendLine("-Didea.config.path=${cacheDir.resolve("config")}")
        appendLine("-Didea.system.path=${cacheDir.resolve("system")}")
        appendLine("-Didea.log.path=${cacheDir.resolve("logs")}")
        appendLine("-Didea.plugins.path=${cacheDir.resolve("plugins")}")
        appendLine("-Didea.vendor.name=devrig (managed)")
        appendLine("-Xms256m")
        appendLine("-Xmx2048m")
        // Let the managed IDE report analytics and check for updates like a normal install — do not
        // disable mcp.steroid updates/analytics here.
        appendLine("-Dmcp.steroid.idea.description.enabled=false")
        appendLine("-Dmcp.steroid.dialog.killer.enabled=true")
        appendLine("-Djb.consents.confirmation.enabled=false")
        appendLine("-Djb.privacy.policy.text=<!--999.999-->")
        appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
        appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
        appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
        appendLine("-Didea.initially.ask.config=never")
        appendLine("-Dide.newUsersOnboarding=false")
        appendLine("-Dnosplash=true")
    }
    Files.writeString(path, content)
    return path
}

fun descriptorPath(backendDir: Path): Path = backendDir.resolve("backend.json")

fun readDescriptorOrNull(path: Path): BackendDescriptor? {
    if (!path.exists()) return null
    return backendJson.decodeFromString<BackendDescriptor>(Files.readString(path))
}

fun writeDescriptor(path: Path, descriptor: BackendDescriptor) {
    Files.createDirectories(path.parent)
    Files.writeString(path, backendJson.encodeToString(descriptor) + "\n")
}

private val backendJson = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun validateInstalledProductCode(
    product: IdeProduct,
    actualProductCode: String?,
    downloadedUrl: String,
    archivePath: Path?,
    bundleDir: Path,
    descriptorPath: Path,
) {
    val expectedProductCode = product.installedProductCode
    if (actualProductCode == expectedProductCode) return

    deleteRecursively(bundleDir)
    Files.deleteIfExists(descriptorPath)
    throw ManagedBackendValidationException(
        buildString {
            append("Managed backend product validation failed for ${product.id} (${product.code}). ")
            append("Expected product-info.json productCode '$expectedProductCode', ")
            append("actual '${actualProductCode ?: "<missing>"}'. ")
            append("Downloaded URL: $downloadedUrl. ")
            append("Archive path: ${archivePath?.toString() ?: "<not downloaded in this invocation>"}. ")
            append("Unpacked path: $bundleDir. ")
            append("Removed unpacked bundle and descriptor: $descriptorPath")
        }
    )
}

/**
 * Refuses an install whose `product-info.json` build is not the build that was resolved — a wrong or
 * swapped artifact under the requested id.
 *
 * Feeds differ in what they can tell us: `data.services.jetbrains.com` returns the exact build
 * (`262.8665.337`), while GitHub Community releases and the Android Studio canary page only give the
 * platform baseline (`262`). A baseline resolution therefore matches any `262.*` install — comparing
 * it for equality with the real `262.8665.258` would reject every Community download.
 *
 * A missing `buildNumber` is not a failure: there is nothing to compare, and
 * [validateInstalledProductCode] already covers artifact identity.
 */
fun validateInstalledBuildNumber(
    product: IdeProduct,
    expectedBuild: String,
    expectedBuildIsBaseline: Boolean,
    actualBuildNumber: String?,
    downloadedUrl: String,
    archivePath: Path?,
    bundleDir: Path,
    descriptorPath: Path,
) {
    if (actualBuildNumber == null) return
    if (ideBuildMatches(actualBuildNumber, expectedBuild, expectedBuildIsBaseline)) return

    deleteRecursively(bundleDir)
    Files.deleteIfExists(descriptorPath)
    throw ManagedBackendValidationException(
        buildString {
            append("Managed backend build validation failed for ${product.id} (${product.code}). ")
            if (expectedBuildIsBaseline) {
                append("Expected a product-info.json build on platform baseline '$expectedBuild', ")
            } else {
                append("Expected product-info.json build '$expectedBuild', ")
            }
            append("actual '$actualBuildNumber'. ")
            append("Downloaded URL: $downloadedUrl. ")
            append("Archive path: ${archivePath?.toString() ?: "<not downloaded in this invocation>"}. ")
            append("Unpacked path: $bundleDir. ")
            append("Removed unpacked bundle and descriptor: $descriptorPath")
        }
    )
}

private fun resolveBundleDir(backendDir: Path): Path {
    val candidates = Files.list(backendDir).use { stream ->
        stream.asSequence()
            .filter { it.isDirectory() }
            .filter { hasProductInfoCandidate(it) }
            .toList()
    }
    require(candidates.isNotEmpty()) {
        "No IntelliJ bundle with product-info.json found in $backendDir"
    }
    require(candidates.size == 1) {
        "Expected exactly one IntelliJ bundle in $backendDir, found: ${candidates.joinToString { it.fileName.toString() }}"
    }
    return candidates.single()
}

private fun hasProductInfoCandidate(dir: Path): Boolean {
    return listOf(
        dir.resolve("product-info.json"),
        dir.resolve("Contents/Resources/product-info.json"),
    ).any { it.exists() }
}

fun readManagedBackendProcessState(path: Path): ManagedBackendProcessState? {
    if (!path.exists()) return null
    val text = Files.readString(path).trim()
    if (text.isBlank()) return null
    text.toLongOrNull()?.let { legacyPid ->
        return ManagedBackendProcessState(pid = legacyPid, startInstant = null)
    }
    return try {
        backendJson.decodeFromString<ManagedBackendProcessState>(text)
    } catch (e: Exception) {
        System.err.println("WARN: failed to decode managed backend process state $path: ${e.javaClass.simpleName}")
        null
    }
}

private fun ManagedBackendProcessState.parsedStartInstant(): Instant? {
    val raw = startInstant ?: return null
    return try {
        Instant.parse(raw)
    } catch (e: Exception) {
        System.err.println("WARN: invalid managed backend process start instant: ${e.javaClass.simpleName}")
        null
    }
}

fun ManagedBackendProcessState.matchesProcessSnapshot(snapshot: ProcessSnapshot?): Boolean {
    val recordedStartInstant = parsedStartInstant() ?: return false
    return recordedStartInstant == snapshot?.startInstant
}

private fun terminateProcessIfMatches(expected: ProcessSnapshot): ManagedProcessTerminationOutcome {
    val expectedStartInstant = expected.startInstant
        ?: return ManagedProcessTerminationOutcome.IDENTITY_CHANGED
    val handle = ProcessHandle.of(expected.pid).getOrNull()
        ?: return ManagedProcessTerminationOutcome.NOT_RUNNING
    if (!handle.isAlive) return ManagedProcessTerminationOutcome.NOT_RUNNING
    val currentStartInstant = handle.info().startInstant().orElse(null)
    if (currentStartInstant != expectedStartInstant) {
        return ManagedProcessTerminationOutcome.IDENTITY_CHANGED
    }
    handle.destroy()
    if (waitForExit(handle, timeoutMillis = 2_000L)) {
        return ManagedProcessTerminationOutcome.TERMINATED
    }
    handle.destroyForcibly()
    if (waitForExit(handle, timeoutMillis = 5_000L)) {
        return ManagedProcessTerminationOutcome.TERMINATED
    }
    System.err.println("WARN: failed to terminate managed backend process pid ${expected.pid}")
    return ManagedProcessTerminationOutcome.FAILED
}

private fun waitForExit(handle: ProcessHandle, timeoutMillis: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (!handle.isAlive) return true
        Thread.sleep(200L)
    }
    return !handle.isAlive
}

private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
        stream.asSequence().forEach { path ->
            val relative = source.relativize(path)
            val destination = target.resolve(relative)
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

private fun deleteRecursively(path: Path) {
    if (!path.exists()) return
    Files.walk(path).use { stream ->
        stream.asSequence()
            .sortedWith(compareByDescending { it.nameCount })
            .forEach { Files.deleteIfExists(it) }
    }
}

private fun nullDevice(): File {
    return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) File("NUL") else File("/dev/null")
}

/**
 * Spawn the IDE launcher detached from the current process's lifetime, so it
 * survives devrig termination (and, on Windows, the surrounding shell's).
 *
 * Linux uses `setsid`; macOS uses a short job-control helper plus `nohup`. Both
 * detach the launcher from the invoking agent tool's process group before returning.
 *
 * Windows — see [spawnDetachedOnWindows].
 */
private fun spawnIdeProcess(
    launcher: Path,
    arguments: List<String>,
    workDir: Path,
    stdoutLog: File,
    stderrLog: File,
    environment: Map<String, String>,
): Long {
    val hostOs = resolveHostOs()
    if (hostOs == HostOs.WINDOWS) {
        // stdoutLog/stderrLog are not propagated on Windows; the WMI-spawned child
        // is created by the winmgmt service and has no caller-attached stdio.
        // idea64.exe is GUI-subsystem and writes idea.log itself anyway.
        return spawnDetachedOnWindows(launcher, arguments, workDir, environment)
    }
    if (hostOs == HostOs.MAC) {
        return spawnDetachedOnMacOs(
            launcher = launcher,
            arguments = arguments,
            workDir = workDir,
            stdoutLog = stdoutLog,
            stderrLog = stderrLog,
            environment = environment,
        )
    }

    // Agent shell tools terminate their whole process group after a command completes. The native
    // Remote Development launcher stays in the foreground, so it must lead a new session or it is
    // killed immediately after `devrig backend start` returns. `setsid` execs the launcher in that
    // session while preserving its PID for the normal marker handoff.
    val command = listOf(resolveSetsidExecutable().toString(), launcher.toString()) + arguments
    return ProcessBuilder(command)
        .also { builder ->
            if (environment.isNotEmpty()) {
                // Agent CLIs carry API keys in their environment. A Remote Development backend needs
                // normal OS/session variables plus its explicit REMOTE_DEV_* flags, never those secrets.
                val childEnvironment = managedProcessEnvironment(System.getenv(), environment, hostOs)
                builder.environment().clear()
                builder.environment().putAll(childEnvironment)
            }
        }
        .directory(workDir.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
        .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
        .start()
        .pid()
}

private fun spawnDetachedOnMacOs(
    launcher: Path,
    arguments: List<String>,
    workDir: Path,
    stdoutLog: File,
    stderrLog: File,
    environment: Map<String, String>,
): Long {
    val pidFile = Files.createTempFile("devrig-spawn-", ".pid")
    val errFile = Files.createTempFile("devrig-spawn-", ".err")
    try {
        val command = buildMacOsDetachedProcessCommand(
            launcher = launcher,
            arguments = arguments,
            stdoutLog = stdoutLog.toPath(),
            stderrLog = stderrLog.toPath(),
        )
        val helper = ProcessBuilder(command)
            .also { builder ->
                if (environment.isNotEmpty()) {
                    val childEnvironment = managedProcessEnvironment(System.getenv(), environment, HostOs.MAC)
                    builder.environment().clear()
                    builder.environment().putAll(childEnvironment)
                }
            }
            .directory(workDir.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(pidFile.toFile())
            .redirectError(errFile.toFile())
            .start()

        val finished = helper.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            helper.destroyForcibly()
            error("macOS detach helper timed out launching $launcher")
        }
        if (helper.exitValue() != 0) {
            val errOutput = Files.readString(errFile).trim()
            error("macOS detach helper exited ${helper.exitValue()} launching $launcher; stderr: $errOutput")
        }
        val pidText = Files.readString(pidFile).trim()
        return pidText.toLongOrNull()
            ?: error("Could not parse pid from macOS detach helper output: '$pidText'")
    } finally {
        deleteTempQuietly(pidFile)
        deleteTempQuietly(errFile)
    }
}

private const val MAC_OS_DETACH_SCRIPT =
    $$"set -m; out=$1; err=$2; shift 2; /usr/bin/nohup \"$@\" </dev/null >>\"$out\" 2>>\"$err\" & printf '%s\\n' \"$!\""

fun buildMacOsDetachedProcessCommand(
    launcher: Path,
    arguments: List<String>,
    stdoutLog: Path,
    stderrLog: Path,
): List<String> = listOf(
    "/bin/sh",
    "-c",
    MAC_OS_DETACH_SCRIPT,
    "devrig-detach",
    stdoutLog.toString(),
    stderrLog.toString(),
    launcher.toString(),
) + arguments

private fun resolveSetsidExecutable(): Path {
    val candidates = listOf(Path.of("/usr/bin/setsid"), Path.of("/bin/setsid"))
    return candidates.firstOrNull { Files.isExecutable(it) }
        ?: throw ManagedBackendValidationException(
            "Cannot detach the managed IDE: setsid is required on Linux but was not found at " +
                candidates.joinToString(),
        )
}

private fun spawnDetachedOnWindows(
    launcher: Path,
    arguments: List<String>,
    workDir: Path,
    environment: Map<String, String>,
): Long {
    // Spawn via WMI's Win32_Process.Create, executed in winmgmt.exe (the WMI
    // service) so the new IDE process has *no* relationship to our process tree:
    // - not a child of devrig → survives devrig's exit
    // - not in our console group → no CTRL_CLOSE_EVENT propagation
    // - not in our Job Object → SSH session teardown can't kill it
    //
    // Neither Java's ProcessBuilder (no detach flags) nor PowerShell's
    // Start-Process (still inherits the caller's Job Object on Windows) is
    // sufficient — the IDE dies the moment a non-interactive shell session
    // (e.g. SSH-spawned cmd.exe) closes. WMI is the only stdlib-only escape.
    val pidFile = Files.createTempFile("devrig-spawn-", ".pid")
    val errFile = Files.createTempFile("devrig-spawn-", ".err")
    try {
        val processCommand = buildWindowsProcessCommand(launcher, arguments)
        val script = buildString {
            append($$"$cmd = '").append(psQuote(processCommand)).append("'; ")
            if (environment.isEmpty()) {
                append($$"$startup = $null; ")
            } else {
                val mergedEnvironment = windowsProcessEnvironment(System.getenv(), environment)
                append($$"$startup = ([wmiclass]'\\\\.\\root\\cimv2:Win32_ProcessStartup').CreateInstance(); ")
                append($$"$startup.EnvironmentVariables = @(")
                append(mergedEnvironment.entries.joinToString(", ") { "'${psQuote("${it.key}=${it.value}")}'" })
                append("); ")
            }
            append($$"$r = ([wmiclass]'\\\\.\\root\\cimv2:Win32_Process').Create($cmd, '")
            append(psQuote(workDir.toString())).append($$"', $startup); ")
            append($$"if ($r.ReturnValue -ne 0) { Write-Error (\"Win32_Process.Create returned \" + $r.ReturnValue); exit $r.ReturnValue }; ")
            append($$"$r.ProcessId | Out-File -FilePath '").append(psQuote(pidFile.toAbsolutePath().toString())).append("' -Encoding ASCII")
        }

        val helper = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errFile.toFile())
            .start()

        val finished = helper.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            helper.destroyForcibly()
            error("WMI spawn helper timed out launching $launcher")
        }
        if (helper.exitValue() != 0) {
            val errOutput = Files.readString(errFile).trim()
            error("WMI spawn helper exited ${helper.exitValue()} launching $launcher; stderr: $errOutput")
        }
        val pidText = Files.readString(pidFile).trim()
        return pidText.toLongOrNull()
            ?: error("Could not parse pid from WMI spawn helper output: '$pidText'")
    } finally {
        deleteTempQuietly(pidFile)
        deleteTempQuietly(errFile)
    }
}

private fun deleteTempQuietly(path: Path) {
    try {
        Files.deleteIfExists(path)
    } catch (e: Exception) {
        System.err.println("Failed to delete temp file $path: $e")
    }
}

/** Doubles single quotes for embedding inside a PowerShell single-quoted string literal. */
private fun psQuote(s: String): String = s.replace("'", "''")

/** Builds the command line passed to Win32_Process.Create. */
fun buildWindowsProcessCommand(launcher: Path, arguments: List<String>): String {
    val launcherExt = launcher.fileName.toString().substringAfterLast('.', "").lowercase()
    val executable = quoteWindowsProcessArgument(launcher.toString(), alwaysQuote = true)
    val command = (listOf(executable) + arguments.map { quoteWindowsProcessArgument(it) }).joinToString(" ")
    return if (launcherExt == "bat" || launcherExt == "cmd") {
        // Win32_Process.Create cannot execute batch scripts directly. Keep the script path in
        // the command line so processCommandIsUnderBackendsDir() can still recognise the process.
        "cmd.exe /c $command"
    } else {
        command
    }
}

fun windowsProcessEnvironment(
    inherited: Map<String, String>,
    overrides: Map<String, String>,
): Map<String, String> = managedProcessEnvironment(inherited, overrides, HostOs.WINDOWS)

fun managedProcessEnvironment(
    inherited: Map<String, String>,
    overrides: Map<String, String>,
    hostOs: HostOs,
): Map<String, String> {
    val requiredKeys = if (hostOs == HostOs.WINDOWS) WINDOWS_PROCESS_ENVIRONMENT_KEYS else UNIX_PROCESS_ENVIRONMENT_KEYS
    val caseInsensitive = hostOs == HostOs.WINDOWS

    fun allowed(key: String): Boolean = if (caseInsensitive) {
        key.uppercase() in requiredKeys
    } else {
        key in requiredKeys || key.startsWith("LC_")
    }

    fun overridden(key: String): Boolean = if (caseInsensitive) {
        overrides.keys.any { it.equals(key, ignoreCase = true) }
    } else {
        key in overrides
    }

    val result = linkedMapOf<String, String>()
    inherited.entries
        .filter { allowed(it.key) }
        .filterNot { overridden(it.key) }
        .sortedBy { if (caseInsensitive) it.key.uppercase() else it.key }
        .forEach { result[it.key] = it.value }
    overrides.entries.sortedBy { it.key }.forEach { result[it.key] = it.value }
    return result
}

private val WINDOWS_PROCESS_ENVIRONMENT_KEYS = setOf(
    "APPDATA",
    "COMSPEC",
    "HOMEDRIVE",
    "HOMEPATH",
    "JAVA_HOME",
    "LOCALAPPDATA",
    "PATH",
    "PATHEXT",
    "PROGRAMDATA",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "SYSTEMDRIVE",
    "SYSTEMROOT",
    "TEMP",
    "TMP",
    "USERNAME",
    "USERPROFILE",
)

private val UNIX_PROCESS_ENVIRONMENT_KEYS = setOf(
    "DISPLAY",
    "HOME",
    "JAVA_HOME",
    "JDK_HOME",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "LOGNAME",
    "PATH",
    "SHELL",
    "TEMP",
    "TMP",
    "TMPDIR",
    "USER",
    "WAYLAND_DISPLAY",
    "XAUTHORITY",
    "XDG_CACHE_HOME",
    "XDG_CONFIG_HOME",
    "XDG_DATA_HOME",
    "XDG_RUNTIME_DIR",
)

private fun quoteWindowsProcessArgument(value: String, alwaysQuote: Boolean = false): String {
    if (!alwaysQuote && value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' }) return value

    val result = StringBuilder().append('"')
    var backslashes = 0
    for (character in value) {
        when (character) {
            '\\' -> backslashes++
            '"' -> {
                repeat(backslashes * 2 + 1) { result.append('\\') }
                result.append('"')
                backslashes = 0
            }
            else -> {
                repeat(backslashes) { result.append('\\') }
                result.append(character)
                backslashes = 0
            }
        }
    }
    repeat(backslashes * 2) { result.append('\\') }
    return result.append('"').toString()
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
