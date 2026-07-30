/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeChannel
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeDistribution
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveAndDownload
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveArchive
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.zip.ZipFile

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
)

interface ManagedProcessInspector {
    fun isAlive(pid: Long): Boolean
    fun allProcesses(): List<ProcessSnapshot>
}

object DefaultManagedProcessInspector : ManagedProcessInspector {
    override fun isAlive(pid: Long): Boolean =
        ProcessHandle.of(pid).getOrNull()?.isAlive == true

    override fun allProcesses(): List<ProcessSnapshot> =
        ProcessHandle.allProcesses().use { stream ->
            stream.asSequence()
                .map { handle -> ProcessSnapshot(handle.pid(), handle.info().command().orElse(null)) }
                .toList()
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
        // Android Studio's compatible (261) build is on the canary channel; true Community editions
        // live on GitHub (the products API stops at 253); everything else from data.services.jetbrains.com.
        val archive = when {
            id.product === IdeProduct.AndroidStudio -> resolveAndroidStudioCanaryArchive(os = os, version = id.version)
            isGithubCommunityProduct(id.product) -> resolveGithubCommunityArchive(product = id.product, os = os, version = id.version)
            else -> resolveArchive(product = id.product, channel = IdeChannel.STABLE, os = os, version = id.version)
        }
        BackendDownloadResolution(
            product = archive.product,
            version = archive.version,
            build = archive.build,
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
) : ManagedBackendService {
    init {
        homePaths.mkdirsAll()
        migrateLegacyArchives(homePaths)
    }

    override suspend fun download(id: BackendId): DownloadResult {
        homePaths.mkdirsAll()
        val resolution = downloader.resolve(id)
        requirePluginCompatibleBuild(resolution.product, resolution.version, resolution.build)
        val resolved = ResolvedBackendId(resolution.product, resolution.version)
        val backendDir = homePaths.backendDir(resolved.id)
        val descriptorPath = descriptorPath(backendDir)
        val existingDescriptor = readDescriptorOrNull(descriptorPath)

        val prepared = if (isReusableBackendInstall(backendDir, existingDescriptor)) {
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
            PreparedBackendInstall(
                bundleDirName = bundleDir.fileName.toString(),
                launcher = launcher,
                downloadArtifact = artifact,
                downloadedAt = existingDescriptor?.downloadedAt,
            )
        } else {
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
                Files.move(partialDir, backendDir, StandardCopyOption.ATOMIC_MOVE)
                PreparedBackendInstall(
                    bundleDirName = partialBundleDir.fileName.toString(),
                    launcher = launcher,
                    downloadArtifact = artifact,
                    downloadedAt = existingDescriptor?.downloadedAt,
                )
            } catch (e: Exception) {
                deleteRecursively(partialDir)
                throw e
            }
        }

        val vmOptionsPath = writeBackendVmOptions(homePaths, resolved.id, prepared.bundleDirName)
        val descriptor = BackendDescriptor(
            id = resolved.id,
            productKey = resolution.product.id,
            productCode = prepared.launcher.productCode ?: resolution.product.code,
            version = resolution.version,
            buildNumber = prepared.launcher.buildNumber ?: resolution.build,
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

    override suspend fun start(id: BackendId): StartResult {
        homePaths.mkdirsAll()
        return withGlobalBackendOperationLock {
            startLocked(id)
        }
    }

    private suspend fun startLocked(id: BackendId): StartResult {
        val resolved = resolveConcreteId(id)
        val descriptor = loadDescriptor(resolved)
        descriptor.buildNumber?.let { build -> requirePluginCompatibleBuild(resolved.product, descriptor.version, build) }
        val running = scanRunningManagedProcesses()
        val other = running.firstOrNull { it.backendId != resolved.id }
        if (other != null) {
            throw ManagedBackendLockException(lockConflictMessage(other))
        }
        val existing = running.firstOrNull { it.backendId == resolved.id }
        if (existing != null) {
            return StartResult(
                id = resolved.id,
                pid = existing.pid,
                ideaLogPath = homePaths.cacheDir(resolved.id).resolve("logs/managed.log"),
                configPath = homePaths.cacheDir(resolved.id).resolve("config"),
                alreadyRunning = true,
            )
        }

        val pidFile = homePaths.pidFile(resolved.id)
        val bundleDir = homePaths.backendDir(resolved.id).resolve(descriptor.bundleDirName)
        val launcher = bundleDir.resolve(descriptor.launcherPath)
        require(Files.isExecutable(launcher)) { "Launcher is not executable: $launcher" }

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
        val pid = spawnIdeProcess(
            launcher = launcher,
            workDir = bundleDir,
            stdoutLog = managedLog.toFile(),
            stderrLog = managedLog.toFile(),
            environment = emptyMap(),
        )

        Files.createDirectories(pidFile.parent)
        Files.writeString(pidFile, "$pid\n")
        return StartResult(
            id = resolved.id,
            pid = pid,
            ideaLogPath = managedLog,
            configPath = cacheDir.resolve("config"),
        )
    }

    private suspend fun <T> withGlobalBackendOperationLock(block: suspend () -> T): T {
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
                return block()
            }
        }
    }

    override suspend fun stop(id: BackendId): StopResult {
        val resolved = resolveConcreteId(id)
        val pidFile = homePaths.pidFile(resolved.id)
        val pid = readPid(pidFile)
        if (pid == null) {
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = null, outcome = "not running")
        }
        val descriptor = loadDescriptor(resolved)

        val handle = ProcessHandle.of(pid).getOrNull()
        if (handle == null || !handle.isAlive) {
            deleteMcpMarker(pid)
            Files.deleteIfExists(pidFile)
            return StopResult(resolved.id, pid = pid, outcome = "already stopped")
        }
        if (!isManagedBackendProcess(handle, descriptor, pid)) {
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
            waitForExit(handle, timeoutMillis = 5_000L)
            "killed"
        }
        deleteMcpMarker(pid)
        Files.deleteIfExists(pidFile)
        return StopResult(resolved.id, pid = pid, outcome = outcome)
    }

    private fun isManagedBackendProcess(
        handle: ProcessHandle,
        descriptor: BackendDescriptor,
        pid: Long,
    ): Boolean {
        return processCommandIsUnderBackendsDir(handle) || pidMarkerMatchesDescriptor(pid, descriptor)
    }

    private fun processCommandIsUnderBackendsDir(handle: ProcessHandle): Boolean {
        val info = handle.info()
        val command = info.command().orElse(null)
        if (command != null && processPathIsUnderBackendsDir(command)) return true
        return info.arguments().orElse(emptyArray()).any { processPathIsUnderBackendsDir(it) }
    }

    private fun processPathIsUnderBackendsDir(rawPath: String): Boolean {
        val commandPath = try {
            Path.of(rawPath)
        } catch (e: Exception) {
            System.err.println("WARN: failed to parse process path '$rawPath': ${e.message}")
            return false
        }
        if (!commandPath.isAbsolute) return false
        val backendsDir = homePaths.backendsDir.toAbsolutePath().normalize()
        return commandPath.toAbsolutePath().normalize().startsWith(backendsDir)
    }

    private fun pidMarkerMatchesDescriptor(pid: Long, descriptor: BackendDescriptor): Boolean {
        val markerPath = markerPathsForPid(pid).firstOrNull { Files.isRegularFile(it) } ?: return false
        val marker = try {
            PidMarkerJson.decode(Files.readString(markerPath))
        } catch (e: Exception) {
            System.err.println("WARN: failed to decode MCP Steroid marker file $markerPath: ${e.message}")
            return false
        }
        val expectedBuild = descriptor.buildNumber ?: return false
        return marker.pid == pid && marker.ide.build == expectedBuild
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
        return Files.list(homePaths.backendsDir).use { stream ->
            stream.asSequence()
                .filter { it.isDirectory() }
                .mapNotNull { dir ->
                    try {
                        val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                        val pid = readPid(homePaths.pidFile(descriptor.id))
                        val alivePid = pid?.takeIf { processInspector.isAlive(it) }
                        val state = when {
                            alivePid != null -> ManagedBackendState.RUNNING
                            pid != null -> ManagedBackendState.UNREACHABLE
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
        if (Files.isDirectory(homePaths.stateDir)) {
            Files.list(homePaths.stateDir).use { stream ->
                stream.asSequence()
                    .filter { it.fileName.toString().endsWith(".pid") }
                    .forEach { pidFile ->
                        val backendId = pidFile.fileName.toString().removeSuffix(".pid")
                        val pid = readPid(pidFile)
                        if (pid != null && processInspector.isAlive(pid)) {
                            trackedPids += pid
                            trackedIds += backendId
                            byId += RunningManagedProcess(backendId, pid, untracked = false)
                        } else {
                            Files.deleteIfExists(pidFile)
                        }
                    }
            }
        }

        for (process in processInspector.allProcesses()) {
            if (process.pid in trackedPids) continue
            val backendId = backendIdFromCommand(process.command) ?: continue
            if (backendId in trackedIds) continue
            byId += RunningManagedProcess(backendId, process.pid, untracked = true)
        }

        return byId.sortedWith(compareBy({ it.backendId }, { it.pid }))
    }

    private fun backendIdFromCommand(command: String?): String? {
        if (command.isNullOrBlank()) return null
        val backendsRoot = homePaths.backendsDir.toAbsolutePath().normalize().toString().pathKey() + "/"
        val commandPath = Path.of(command).toAbsolutePath().normalize().toString().pathKey()
        val index = commandPath.indexOf(backendsRoot)
        if (index < 0) return null
        val rest = commandPath.substring(index + backendsRoot.length)
        return rest.substringBefore('/').takeIf { it.isNotBlank() }
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
    val untracked: Boolean,
)

private fun String.pathKey(): String =
    replace('\\', '/').trimEnd('/').lowercase()

fun writeBackendVmOptions(homePaths: HomePaths, id: String, bundleDirName: String): Path {
    val cacheDir = homePaths.cacheDir(id).toAbsolutePath().normalize()
    listOf("config", "system", "logs", "plugins", "execution-storage").forEach { Files.createDirectories(cacheDir.resolve(it)) }
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
        appendLine("-Dmcp.steroid.storage.path=${cacheDir.resolve("execution-storage")}")
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

private fun readPid(path: Path): Long? {
    if (!path.exists()) return null
    val text = Files.readString(path).trim()
    if (text.isBlank()) return null
    return text.toLongOrNull()
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
 * Linux / macOS — [ProcessBuilder] is sufficient: the child gets its own session
 * and outlives the parent without special flags.
 *
 * Windows — see [spawnDetachedOnWindows].
 */
private fun spawnIdeProcess(
    launcher: Path,
    workDir: Path,
    stdoutLog: File,
    stderrLog: File,
    environment: Map<String, String>,
): Long = if (resolveHostOs() == HostOs.WINDOWS) {
    // stdoutLog/stderrLog are not propagated on Windows; the WMI-spawned child
    // is created by the winmgmt service and has no caller-attached stdio.
    // idea64.exe is GUI-subsystem and writes idea.log itself anyway.
    spawnDetachedOnWindows(launcher, workDir, environment)
} else {
    ProcessBuilder(launcher.toString())
        .also { builder -> builder.environment().putAll(environment) }
        .directory(workDir.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(stdoutLog))
        .redirectError(ProcessBuilder.Redirect.appendTo(stderrLog))
        .start()
        .pid()
}

private fun spawnDetachedOnWindows(
    launcher: Path,
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
        val launcherExt = launcher.fileName.toString().substringAfterLast('.', "").lowercase()
        val isBatchScript = launcherExt == "bat" || launcherExt == "cmd"
        val script = buildString {
            // Win32_Process.Create (which wraps CreateProcess) cannot execute .bat/.cmd scripts
            // directly — they require the cmd.exe interpreter. Wrap with cmd.exe /c so the batch
            // file path is still visible in the process's command-line arguments, which lets
            // processCommandIsUnderBackendsDir() recognise the process for stop/list tracking.
            if (isBatchScript) {
                append("\$cmd = 'cmd.exe /c \"' + '").append(psQuote(launcher.toString())).append("' + '\"'; ")
            } else {
                append("\$cmd = '\"' + '").append(psQuote(launcher.toString())).append("' + '\"'; ")
            }
            if (environment.isEmpty()) {
                append("\$startup = \$null; ")
            } else {
                append("\$startup = ([wmiclass]'\\\\.\\root\\cimv2:Win32_ProcessStartup').CreateInstance(); ")
                append("\$startup.EnvironmentVariables = @(")
                append(environment.entries.joinToString(", ") { "'${psQuote("${it.key}=${it.value}")}'" })
                append("); ")
            }
            append("\$r = ([wmiclass]'\\\\.\\root\\cimv2:Win32_Process').Create(\$cmd, '")
            append(psQuote(workDir.toString())).append("', \$startup); ")
            append("if (\$r.ReturnValue -ne 0) { Write-Error (\"Win32_Process.Create returned \" + \$r.ReturnValue); exit \$r.ReturnValue }; ")
            append("\$r.ProcessId | Out-File -FilePath '").append(psQuote(pidFile.toAbsolutePath().toString())).append("' -Encoding ASCII")
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
