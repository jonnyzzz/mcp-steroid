/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdePidDiscoveryService
import com.jonnyzzz.mcpSteroid.devrig.monitor.PortDiscovery
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Entry point invoked by [runCli] when the `backend` command is selected.
 *
 * Composes three explicit data sources:
 *  - S1: running IDEs with MCP Steroid plugin (via projectRouting.discoveredBackends())
 *  - S2: running IDEs without MCP Steroid plugin (via port scan)
 *  - S3: installed but not running IDEs (startable)
 */
fun DevrigServices.runBackendCommand(command: DevrigCommand.DevrigCommandBackend): Int {
    val s1 = projectRouting.discoveredBackends()
    val s2 = runBlocking(Dispatchers.IO) {
        withTimeoutOrNull(1.seconds) { collectPortDiscoveredIdes(portDiscovery) } ?: emptySet()
    }
    val s3 = startableBackends(installedBackends(), s1, runningManagedIds())
    if (command.json) {
        renderBackendJson3(s1, s2, s3, mcpStdout)
    } else {
        renderBackendOutput3(s1, s2, s3, mcpStdout)
    }
    return 0
}

fun scanMarkersOnce(
    homePaths: HomePaths = resolveHomePaths(),
): List<DiscoveredIde> {
    //TODO: use services
    return createIdeDiscoveryService(homePaths).stateSnapshot()
}

fun DevrigServices.scanMarkersOnce(): List<DiscoveredIde> {
    return ideDiscovery.stateSnapshot()
}

fun createIdeDiscoveryService(homePaths: HomePaths): IdePidDiscoveryService {
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    return IdePidDiscoveryService(
        markersDir = homePaths.markersDir,
        allowHosts = allowHosts,
    )
}

fun DevrigServices.runBackendDownloadCommand(command: DevrigCommand.DevrigCommandBackendDownload): Int =
    runBackendDownloadCommand(mcpStdout, homePaths, command, backendService = backendManager)

fun runBackendDownloadCommand(
    out: PrintStream,
    homePaths: HomePaths,
    command: DevrigCommand.DevrigCommandBackendDownload,
    backendService: ManagedBackendService = BackendManager(homePaths),
): Int {
    val versionOverride = command.version
    val id = command.id ?: run {
        runBackendDownloadListCommand(out, json = command.json)
        return 0
    }
    if (!isSupportedBackendLifecycleId(id)) {
        return unknownArguments(
            listOf("backend", "download", id),
            "Run `devrig backend download` with no id to list valid backend ids.",
        )
    }
    if (command.json) {
        return runBackendActionJson(out, action = "download", id = id) {
            val backendId = parseBackendId(id).withVersionOverride(versionOverride)
            lateinit var result: DownloadResult
            val durationMs = measureTimeMillis {
                result = runBlocking(Dispatchers.IO) {
                    backendService.download(backendId)
                }
            }
            buildJsonObject {
                putToolJson()
                put("action", "download")
                put("id", result.id)
                put("productKey", result.descriptor.productKey)
                put("version", result.descriptor.version)
                put("installPath", result.backendDir.toString())
                put("cachePath", homePaths.cacheDir(result.id).toString())
                put("vmoptionsPath", result.vmOptionsPath.toString())
                put("downloadDurationMs", durationMs)
            }
        }
    }
    val backendId = parseBackendId(id).withVersionOverride(versionOverride)
    val result = runBlocking(Dispatchers.IO) {
        backendService.download(backendId)
    }
    val bundleDir = result.backendDir.resolve(result.descriptor.bundleDirName)
    val launcher = ManagedBackendLauncherResolver().resolve(result.descriptor, bundleDir).executable
    out.println("id: ${result.id}")
    out.println("install: ${result.backendDir}")
    out.println("launcher: $launcher")
    out.println("vmoptions: ${result.vmOptionsPath}")
    return 0
}

fun DevrigServices.runBackendStartCommand(command: DevrigCommand.DevrigCommandBackendStart): Int =
    runBackendStartCommand(mcpStdout, homePaths, command, backendService = backendManager)

fun runBackendStartCommand(
    out: PrintStream,
    homePaths: HomePaths,
    command: DevrigCommand.DevrigCommandBackendStart,
    backendService: ManagedBackendService = BackendManager(homePaths),
): Int {
    val versionOverride = command.version
    val id = command.id ?: run {
        runBackendStartListCommand(out, homePaths, json = command.json)
        return 0
    }
    if (!isSupportedBackendLifecycleId(id)) {
        return unknownArguments(
            listOf("backend", "start", id),
            "Run `devrig backend start` with no id to list valid backend ids.",
        )
    }
    if (command.json) {
        return runBackendActionJson(out, action = "start", id = id) {
            val backendId = parseBackendId(id).withVersionOverride(versionOverride)
            val result = runBlocking(Dispatchers.IO) {
                backendService.start(backendId)
            }
            buildJsonObject {
                putToolJson()
                put("action", "start")
                put("id", result.id)
                put("pid", result.pid)
                put("logPath", result.ideaLogPath.toString())
                put("configPath", result.configPath.toString())
                put("vmoptionsPath", backendVmOptionsPath(homePaths, result.id).toString())
            }
        }
    }
    val backendId = parseBackendId(id).withVersionOverride(versionOverride)
    val result = runBlocking(Dispatchers.IO) {
        backendService.start(backendId)
    }
    if (result.alreadyRunning) {
        out.println("already running: ${result.id} (pid ${result.pid})")
    }
    out.println("pid: ${result.pid}")
    out.println("log: ${result.ideaLogPath}")
    out.println("config: ${result.configPath}")
    return 0
}

fun DevrigServices.runBackendStopCommand(command: DevrigCommand.DevrigCommandBackendStop): Int =
    runBackendStopCommand(mcpStdout, homePaths, command, backendService = backendManager)

fun runBackendStopCommand(
    out: PrintStream,
    homePaths: HomePaths,
    command: DevrigCommand.DevrigCommandBackendStop,
    backendService: ManagedBackendService = BackendManager(homePaths),
): Int {
    val versionOverride = command.version
    val id = command.id ?: run {
        runBackendStopListCommand(out, homePaths, json = command.json)
        return 0
    }
    if (!isSupportedBackendLifecycleId(id)) {
        return unknownArguments(
            listOf("backend", "stop", id),
            "Run `devrig backend stop` with no id to list valid backend ids.",
        )
    }
    if (command.json) {
        return runBackendActionJson(out, action = "stop", id = id) {
            val backendId = parseBackendId(id).withVersionOverride(versionOverride)
            lateinit var result: StopResult
            val durationMs = measureTimeMillis {
                result = runBlocking(Dispatchers.IO) {
                    backendService.stop(backendId)
                }
            }
            buildJsonObject {
                putToolJson()
                put("action", "stop")
                put("id", result.id)
                if (result.pid == null) {
                    put("stoppedPid", kotlinx.serialization.json.JsonNull)
                } else {
                    put("stoppedPid", result.pid)
                }
                put("outcome", result.outcome)
                put("graceful", result.outcome != "killed")
                result.message?.let { put("message", it) }
                put("durationMs", durationMs)
            }
        }
    }
    val backendId = parseBackendId(id).withVersionOverride(versionOverride)
    val result = runBlocking(Dispatchers.IO) {
        backendService.stop(backendId)
    }
    val pidSuffix = result.pid?.let { " pid $it" }.orEmpty()
    val messageSuffix = result.message?.let { " - $it" }.orEmpty()
    out.println("${result.outcome}: ${result.id}$pidSuffix$messageSuffix")
    return 0
}

fun runBackendActionJson(
    out: PrintStream,
    action: String,
    id: String,
    block: () -> JsonObject,
): Int {
    return try {
        val payload = withSilencedSystemStreams { block() }
        out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
        0
    } catch (e: Exception) {
        val payload = buildJsonObject {
            putToolJson()
            put("action", action)
            put("id", id)
            put("error", e.shortMessage())
            put("exitCode", 64)
        }
        out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
        64
    }
}

/**
 * Silence stray stdout/stderr writes from [block]. The receiver-action
 * lambdas in the `backend --json` path occasionally print progress text
 * through libraries we don't control; redirecting `System.out`/`System.err`
 * keeps the single JSON document on stdout clean.
 *
 * Process-global by design — devrig is a one-shot CLI, so swapping the
 * shared `System.out` is fine. Do NOT use this from concurrent paths
 * (e.g. the MCP server loop) where another coroutine might write to
 * stdout in parallel.
 */
private inline fun <T> withSilencedSystemStreams(block: () -> T): T {
    val originalOut = System.out
    val originalErr = System.err
    val sink = PrintStream(OutputStream.nullOutputStream(), true, Charsets.UTF_8)
    System.setOut(sink)
    System.setErr(sink)
    try {
        return block()
    } finally {
        System.setOut(originalOut)
        System.setErr(originalErr)
        sink.close()
    }
}

private fun backendVmOptionsPath(homePaths: HomePaths, id: String): java.nio.file.Path {
    val descriptor = readDescriptorOrNull(descriptorPath(homePaths.backendDir(id)))
        ?: error("Managed backend '$id' is not installed. Run `devrig backend download ${id.substringBeforeLast('-')}` first.")
    return homePaths.backendDir(id).resolve("${descriptor.bundleDirName}.vmoptions")
}

fun isSupportedBackendLifecycleId(raw: String): Boolean {
    if (raw.isBlank()) return false
    val colonParts = raw.split(':')
    if (colonParts.size > 2) return false
    if (colonParts.size == 2) {
        return isKnownProductKey(colonParts[0]) && isSupportedBackendVersion(colonParts[1])
    }
    if (isKnownProductKey(raw)) return true
    val product = com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct.knownProducts
        .sortedByDescending { it.id.length }
        .firstOrNull { raw.startsWith("${it.id}-") }
        ?: return false
    return isSupportedBackendVersion(raw.removePrefix("${product.id}-"))
}

private fun isKnownProductKey(raw: String): Boolean =
    com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct.knownProducts.any { it.id == raw }

/**
 * One-shot port scan. Wraps [PortDiscovery.stateSnapshot] + reads the
 * resulting set. Separate function so tests can drive it independently.
 */
suspend fun collectPortDiscoveredIdes(
    portDiscovery: PortDiscovery,
): Set<DiscoveredIdeByPort> = portDiscovery.stateSnapshot()

/**
 * Pure renderer for the 3-group backend listing.
 *
 * Per-backend open projects are listed under `devrig project`, not `devrig backend`.
 *
 * Output shape:
 * ```
 * MCP Steroid backends (N):
 *
 *   [1] <IDE name> (build <build>, pid <pid>)
 *         MCP Steroid: <version>
 *
 * Other IDEs (incompatible or no MCP Steroid) (M):
 *
 *   [1] <IDE name> (build <build>, pid <pid>) (incompatible plugin, old version)
 *         MCP Steroid: <version> (incompatible)
 *   [2] <IDE name> (build <build>, port <port>)
 *         MCP Steroid: not installed
 *
 * Installed, not running (startable) (K):
 *
 *   [1] <IDE name> <version> (managed: <id>)
 *         ideHome: <path>
 *
 * To install another IDE: devrig backend download <product>  (downloads + installs → startable, then open_project launches it)
 * ```
 *
 * Group 1 ("MCP Steroid backends") = S1 markers WITH [DiscoveredIde.ideHome] (compatible plugin).
 * Group 2 ("Other IDEs") = S1 markers WITHOUT [DiscoveredIde.ideHome] (incompatible/old plugin) +
 *   port-discovered IDEs, de-duplicated so a port IDE whose build matches any marker does not appear.
 * Group 3 ("Installed, not running") = managed IDEs not yet running.
 */
fun renderBackendOutput3(
    s1: List<DiscoveredIde>,
    s2: Set<DiscoveredIdeByPort>,
    s3: List<InstalledBackend>,
    out: PrintStream,
) {
    // Split S1 by compatibility: ideHome present = compatible (new plugin), absent = incompatible (old plugin).
    // Same ordering as the MCP backends[] lookup (#155): marker-backed rows sort by backend_name.
    val s1Compatible = s1.filter { it.ideHome != null }.sortedBy { it.backendName }
    val s1Incompatible = s1.filter { it.ideHome == null }.sortedBy { it.backendName }

    if (s1Compatible.isEmpty() && s1Incompatible.isEmpty() && s2.isEmpty() && s3.isEmpty()) {
        out.println(NO_BACKENDS_DETECTED_MESSAGE)
        out.println()
        return
    }

    // Dedup port IDEs: drop any whose normalised build matches any marker (compatible or incompatible).
    // A marker already represents this IDE; showing the port entry too would be a duplicate.
    val allMarkerBuilds = s1.mapNotNull { normaliseBuildForDedup(it.ide.build) }.toSet()
    val s2Deduped = s2.filter { portIde ->
        val norm = normaliseBuildForDedup(portIde.buildNumber)
        norm == null || norm !in allMarkerBuilds
    }

    // Group 1: MCP Steroid backends (compatible only)
    val s1Count = s1Compatible.size
    out.println("MCP Steroid backends ($s1Count):")
    out.println()
    if (s1Compatible.isEmpty()) {
        out.println("  (none)")
    } else {
        for ((index, ide) in s1Compatible.withIndex()) {
            out.println("  [${index + 1}] ${markerBackendDisplayName(ide)} (${ide.backendName}; ${markerBackendLocatorLabel(ide)})")
            val plugin = ide.plugin
            out.println("        ${plugin.name.ifBlank { "MCP Steroid" }}: ${plugin.version.ifBlank { "unknown" }}")
            if (index < s1Compatible.lastIndex) out.println()
        }
    }
    out.println()

    // Group 2: incompatible markers + port-discovered IDEs
    val group2Count = s1Incompatible.size + s2Deduped.size
    val s2Sorted = s2Deduped.sortedBy { it.port }
    out.println("Other IDEs (incompatible or no MCP Steroid) ($group2Count):")
    out.println()
    if (s1Incompatible.isEmpty() && s2Sorted.isEmpty()) {
        out.println("  (none)")
    } else {
        for ((index, ide) in s1Incompatible.withIndex()) {
            out.println("  [${index + 1}] ${markerBackendDisplayName(ide)} (${ide.backendName}; ${markerBackendLocatorLabel(ide)}) (incompatible plugin, old version)")
            val plugin = ide.plugin
            out.println("        ${plugin.name.ifBlank { "MCP Steroid" }}: ${plugin.version.ifBlank { "unknown" }} (incompatible)")
            if (index < group2Count - 1) out.println()
        }
        for ((index, ide) in s2Sorted.withIndex()) {
            val entryIndex = s1Incompatible.size + index + 1
            out.println("  [$entryIndex] ${portBackendDisplayName(ide)} (${backendNameForPort(ide.port, ide.buildNumber)}; ${portBackendLocatorLabel(ide)}) (run: ${provisionCommand(provisionTargetId(ide.port))})")
            out.println("        MCP Steroid: not installed")
            if (s1Incompatible.size + index < group2Count - 1) out.println()
        }
        // Promote the one-shot REST installer for every IDE above that lacks a compatible plugin.
        // The IDE shows its own native confirmation dialog, so this is safe to surface unconditionally.
        out.println()
        out.println("  Install / update MCP Steroid in the IDE(s) above:  $INSTALL_PLUGIN_COMMAND")
        out.println("        (each IDE shows a native \"Choose Plugins to Install or Enable\" dialog — nothing installs silently)")
    }
    out.println()

    // S3: Installed, not running
    val s3Count = s3.size
    out.println("Installed, not running (startable) ($s3Count):")
    out.println()
    if (s3.isEmpty()) {
        out.println("  (none)")
    } else {
        for ((index, installed) in s3.withIndex()) {
            out.println("  [${index + 1}] ${installed.ide.name} ${installed.ide.version} (${startableBackendName(installed)}; managed: ${installed.id})")
            out.println("        ideHome: ${installed.ideHome}")
            if (index < s3.lastIndex) out.println()
        }
    }
    out.println()

    out.println("To install another IDE: devrig backend download <product>  (downloads + installs; it then appears above as startable and open_project can launch it)")
    out.println()
}

/**
 * Pretty-printed JSON renderer for the `backend --json` form.
 *
 * Output shape:
 * ```json
 * {
 *   "tool": {"name": "devrig", "version": "..."},
 *   "mcpSteroidBackends": [...],
 *   "otherIdes": [...],
 *   "startableBackends": [...]
 * }
 * ```
 *
 * `mcpSteroidBackends` contains only compatible markers (those with a non-null [DiscoveredIde.ideHome]).
 * Compatible entries carry `"compatible": true`.
 *
 * `otherIdes` contains incompatible markers (`"compatible": false`) followed by port-discovered IDEs
 * (also `"compatible": false` — they are never MCP-capable by definition). Port IDEs whose normalised
 * build matches any marker build are deduplicated out to avoid double-counting.
 */
fun renderBackendJson3(
    s1: List<DiscoveredIde>,
    s2: Set<DiscoveredIdeByPort>,
    s3: List<InstalledBackend>,
    out: PrintStream,
) {
    // Same ordering as the MCP backends[] lookup (#155): marker-backed rows sort by backend_name.
    val s1Compatible = s1.filter { it.ideHome != null }.sortedBy { it.backendName }
    val s1Incompatible = s1.filter { it.ideHome == null }.sortedBy { it.backendName }
    val allMarkerBuilds = s1.mapNotNull { normaliseBuildForDedup(it.ide.build) }.toSet()
    val s2Deduped = s2.filter { portIde ->
        val norm = normaliseBuildForDedup(portIde.buildNumber)
        norm == null || norm !in allMarkerBuilds
    }

    val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", "devrig")
            put("version", DevrigVersionMetadata.getDevrigVersion())
        })
        putJsonArray("mcpSteroidBackends") {
            for (ide in s1Compatible) {
                add(buildJsonObject {
                    put("backend_name", ide.backendName)
                    put("displayName", markerBackendDisplayName(ide))
                    put("build", ide.ide.build)
                    put("pid", ide.pid)
                    put("compatible", true)
                })
            }
        }
        putJsonArray("otherIdes") {
            for (ide in s1Incompatible) {
                add(buildJsonObject {
                    put("backend_name", ide.backendName)
                    put("displayName", markerBackendDisplayName(ide))
                    put("build", ide.ide.build)
                    put("pid", ide.pid)
                    put("compatible", false)
                    put("installCommand", INSTALL_PLUGIN_COMMAND)
                })
            }
            for (ide in s2Deduped.sortedBy { it.port }) {
                add(buildJsonObject {
                    put("backend_name", backendNameForPort(ide.port, ide.buildNumber))
                    put("displayName", portBackendDisplayName(ide))
                    ide.buildNumber?.let { put("build", it) }
                    put("port", ide.port)
                    put("compatible", false)
                    put("installCommand", INSTALL_PLUGIN_COMMAND)
                })
            }
        }
        putJsonArray("startableBackends") {
            for (installed in s3) {
                add(buildJsonObject {
                    put("backend_name", startableBackendName(installed))
                    put("displayName", "${installed.ide.name} ${installed.ide.version}")
                    put("build", installed.ide.build)
                    put("ideHome", installed.ideHome)
                    put("id", installed.id)
                })
            }
        }
    }
    out.println(json.encodeToString(JsonObject.serializer(), payload))
}
