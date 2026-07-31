/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.server.backendNameFor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.InstalledBackends")

/**
 * A devrig-managed (installed under `~/.mcp-steroid/backends/`) IDE that can be started on demand.
 */
data class InstalledBackend(
    val id: String,
    val ide: IdeInfo,
    /** Absolute path to the IDE's install home — the same path `PathManager.getHomePath()` reports when running. */
    val ideHome: String,
    val launcher: Path,
)

/**
 * Normalizes an IDE home path for dedup/comparison purposes.
 *
 * Mirrors [com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService.canonicalProjectHome]:
 * `toRealPath()` resolves symlinks so a managed IDE's [InstalledBackend.ideHome] matches the value
 * reported by the running IDE's `PathManager.getHomePath()`. Falls back to `toAbsolutePath().normalize()`
 * when the path no longer exists (e.g. during tests with a cleaned install directory).
 */
fun normalizeHome(p: String): String {
    val path = Path.of(p)
    return try {
        path.toRealPath()
    } catch (_: IOException) {
        path.toAbsolutePath().normalize()
    }.toString()
}

/**
 * The single source of truth for the `backend_name` of a startable (installed but not yet running) IDE.
 *
 * Keyed by `"home:" + normalizeHome(installed.ideHome)` so the id stays stable for the same install
 * directory regardless of how the path was formed, and matches the marker-side `ideHome` once the IDE is
 * running (after symlink resolution on both sides).
 *
 * Use this in BOTH [com.jonnyzzz.mcpSteroid.devrig.server.OpenProjectCandidate.Startable.backendName] and
 * every CLI render that emits a `backend_name` for startable rows — never inline the formula separately.
 */
fun startableBackendName(installed: InstalledBackend): String =
    backendNameFor(sourceKey = "home:" + normalizeHome(installed.ideHome), build = installed.ide.build)

/**
 * Returns the IDE home directory for a bundle — the directory that contains `bin/` and
 * `product-info.json`, matching what `PathManager.getHomePath()` returns in the running IDE.
 *
 * - macOS app bundle: `bundleDir/Contents` contains `bin/` → return `bundleDir/Contents`
 * - Linux / Windows: `bundleDir` itself contains `bin/` → return `bundleDir`
 */
fun resolveIdeHome(bundleDir: Path): Path {
    val contentsBin = bundleDir.resolve("Contents/bin")
    return if (Files.isDirectory(contentsBin)) bundleDir.resolve("Contents") else bundleDir
}

/**
 * Returns the subset of [installed] backends that are not already running.
 *
 * An installed backend is excluded when either:
 * - a [DiscoveredIde] in [running] has an `ideHome` that matches the backend's `ideHome`
 *   (path-normalized), indicating the same IDE is running with a compatible plugin, OR
 * - the backend's [InstalledBackend.id] is in [runningManagedIds], indicating a live managed
 *   pid file exists for it (the backend was started by devrig and is currently alive).
 *
 * [runningManagedIds] is populated from [BackendManager.list] (the pid-file scan) by callers
 * in [DevrigServices]; pass [emptySet] in unit tests that only test the ideHome path.
 */
fun startableBackends(
    installed: List<InstalledBackend>,
    running: List<DiscoveredIde>,
    runningManagedIds: Set<String> = emptySet(),
): List<InstalledBackend> {
    val runningHomes = running.mapNotNull { it.ideHome }.map(::normalizeHome).toSet()
    return installed.filter { it.id !in runningManagedIds && normalizeHome(it.ideHome) !in runningHomes }
}

/**
 * Enumerates all devrig-managed IDEs installed under `~/.mcp-steroid/backends/`.
 *
 * Reuses [readDescriptorOrNull] and [descriptorPath] from [ManagedBackend.kt] — does not
 * duplicate JSON parsing. Backends with a missing or malformed descriptor are silently skipped
 * (logged to stderr).
 */
fun DevrigServices.installedBackends(): List<InstalledBackend> {
    if (!Files.isDirectory(homePaths.backendsDir)) return emptyList()
    return Files.list(homePaths.backendsDir).use { stream ->
        stream.asSequence()
            .filter { Files.isDirectory(it) }
            .mapNotNull { dir ->
                try {
                    val descriptor = readDescriptorOrNull(descriptorPath(dir)) ?: return@mapNotNull null
                    val bundleDir = homePaths.backendDir(descriptor.id).resolve(descriptor.bundleDirName)
                    if (!Files.isDirectory(bundleDir)) return@mapNotNull null
                    val launchSpec = ManagedBackendLauncherResolver().resolve(descriptor, bundleDir)
                    val ide = IdeInfo(
                        name = descriptor.productKey,
                        version = descriptor.version,
                        build = descriptor.buildNumber ?: "",
                    )
                    InstalledBackend(
                        id = descriptor.id,
                        ide = ide,
                        ideHome = normalizeHome(resolveIdeHome(bundleDir).toString()),
                        launcher = launchSpec.executable,
                    )
                } catch (e: Exception) {
                    log.warn("failed to read installed backend from $dir", e)
                    null
                }
            }
            .sortedWith(compareBy({ it.ide.name }, { it.ide.version }))
            .toList()
    }
}
