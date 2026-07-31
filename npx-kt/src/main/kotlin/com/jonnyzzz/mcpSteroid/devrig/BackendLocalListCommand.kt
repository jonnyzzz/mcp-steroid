/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class LocalBackendState(val cliValue: String) {
    INSTALLED("installed"),
    RUNNING("running"),
}

data class InstalledBackendListRow(
    val id: String,
    val productKey: String,
    val version: String,
    val displayName: String,
    val state: LocalBackendState,
    val pid: Long?,
    val installPath: Path,
    val cachePath: Path,
)

data class RunningBackendListRow(
    val id: String,
    val pid: Long,
    val displayName: String,
    val logPath: Path,
)

private data class ParsedConcreteBackendId(
    val product: IdeProduct,
    val version: String,
) {
    val id: String get() = "${product.id}-$version"
    val displayName: String get() = "${product.displayName} $version"
}

fun runBackendStartListCommand(
    out: PrintStream,
    homePaths: HomePaths,
    json: Boolean,
    processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
    availableDownloads: suspend () -> List<AvailableBackendDownload> = {
        collectAvailableBackendDownloads(versionResolver = ReleaseServiceAvailableBackendVersionResolver())
    },
) {
    val rows = collectInstalledBackendListRows(homePaths, processInspector)
    val availableRows = if (rows.isEmpty()) {
        runBlocking(Dispatchers.IO) { availableDownloads() }
    } else {
        null
    }
    if (json) {
        renderBackendStartListJson(rows, out, availableRows)
    } else {
        renderBackendStartListText(rows, out, availableRows)
    }
}

fun runBackendStopListCommand(
    out: PrintStream,
    homePaths: HomePaths,
    json: Boolean,
    processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
) {
    val rows = collectRunningBackendListRows(homePaths, processInspector)
    if (json) renderBackendStopListJson(rows, out) else renderBackendStopListText(rows, out)
}

fun collectInstalledBackendListRows(
    homePaths: HomePaths,
    processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
): List<InstalledBackendListRow> {
    if (!Files.isDirectory(homePaths.backendsDir)) return emptyList()
    return Files.list(homePaths.backendsDir).use { stream ->
        stream.asSequence()
            .filter { Files.isDirectory(it) }
            .mapNotNull { dir -> installedBackendRowOrNull(homePaths, dir, processInspector) }
            .sortedWith(
                compareBy<InstalledBackendListRow> { backendProductSortIndex(it.productKey) }
                    .thenComparator { left, right -> compareBackendVersions(left.version, right.version) }
                    .thenBy { it.id },
            )
            .toList()
    }
}

fun collectRunningBackendListRows(
    homePaths: HomePaths,
    processInspector: ManagedProcessInspector = DefaultManagedProcessInspector,
): List<RunningBackendListRow> {
    if (!Files.isDirectory(homePaths.stateDir)) return emptyList()
    return Files.list(homePaths.stateDir).use { stream ->
        stream.asSequence()
            .filter { Files.isRegularFile(it) && it.name.endsWith(".pid") }
            .mapNotNull { pidFile -> runningBackendRowOrNull(homePaths, pidFile, processInspector) }
            .sortedWith(compareBy({ backendProductSortIndex(it.id.substringBeforeLast('-')) }, { it.id }))
            .toList()
    }
}

private fun installedBackendRowOrNull(
    homePaths: HomePaths,
    dir: Path,
    processInspector: ManagedProcessInspector,
): InstalledBackendListRow? {
    val parsed = parseConcreteBackendDirectoryNameOrNull(dir.name) ?: return null
    val descriptorFile = descriptorPath(dir)
    if (!Files.isRegularFile(descriptorFile) || !Files.isReadable(descriptorFile)) return null
    val descriptor = readDescriptorOrNull(descriptorFile) ?: return null
    if (descriptor.id != parsed.id || descriptor.productKey != parsed.product.id || descriptor.version != parsed.version) {
        return null
    }
    val alivePid = liveManagedBackendPidOrNull(
        pidFile = homePaths.pidFile(parsed.id),
        backendDir = dir,
        descriptor = descriptor,
        processInspector = processInspector,
    )
    return InstalledBackendListRow(
        id = parsed.id,
        productKey = parsed.product.id,
        version = parsed.version,
        displayName = parsed.displayName,
        state = if (alivePid == null) LocalBackendState.INSTALLED else LocalBackendState.RUNNING,
        pid = alivePid,
        installPath = dir,
        cachePath = homePaths.cacheDir(parsed.id),
    )
}

private fun runningBackendRowOrNull(
    homePaths: HomePaths,
    pidFile: Path,
    processInspector: ManagedProcessInspector,
): RunningBackendListRow? {
    val id = pidFile.name.removeSuffix(".pid")
    val parsed = parseConcreteBackendDirectoryNameOrNull(id) ?: return null
    val backendDir = homePaths.backendDir(parsed.id)
    val descriptor = readDescriptorOrNull(descriptorPath(backendDir)) ?: return null
    if (descriptor.id != parsed.id || descriptor.productKey != parsed.product.id || descriptor.version != parsed.version) {
        return null
    }
    val pid = liveManagedBackendPidOrNull(pidFile, backendDir, descriptor, processInspector) ?: return null
    return RunningBackendListRow(
        id = parsed.id,
        pid = pid,
        displayName = parsed.displayName,
        logPath = homePaths.cacheDir(parsed.id).resolve("logs/managed.log"),
    )
}

private fun liveManagedBackendPidOrNull(
    pidFile: Path,
    backendDir: Path,
    descriptor: BackendDescriptor,
    processInspector: ManagedProcessInspector,
): Long? {
    val processState = readManagedBackendProcessState(pidFile) ?: return null
    if (!processInspector.isAlive(processState.pid)) return null
    val snapshot = processInspector.snapshot(processState.pid) ?: return null
    if (processState.startInstant != null) {
        return processState.pid.takeIf { processState.matchesProcessSnapshot(snapshot) }
    }
    return processState.pid.takeIf { legacyProcessSnapshotMatchesBackend(snapshot, backendDir, descriptor) }
}

private fun legacyProcessSnapshotMatchesBackend(
    snapshot: ProcessSnapshot,
    backendDir: Path,
    descriptor: BackendDescriptor,
): Boolean {
    val bundleDir = backendDir.resolve(descriptor.bundleDirName).toAbsolutePath().normalize()
    val expectedLaunchers = setOf(
        bundleDir.resolve(descriptor.launcherPath).normalize(),
        bundleDir.resolve("bin/remote-dev-server").normalize(),
        bundleDir.resolve("Contents/bin/remote-dev-server").normalize(),
    )
    val command = parseAbsoluteLegacyProcessPath(snapshot.command)
    if (command in expectedLaunchers) return true
    val managedRuntimeRoots = listOf(
        bundleDir.resolve("jbr").normalize(),
        bundleDir.resolve("Contents/jbr").normalize(),
    )
    if (command != null && managedRuntimeRoots.any(command::startsWith)) return true

    val interpreter = command?.fileName?.toString()?.lowercase()
    if (interpreter !in setOf("sh", "bash", "dash", "zsh", "ksh", "cmd", "cmd.exe")) return false
    val launcherArgument = when (interpreter) {
        "cmd", "cmd.exe" -> {
            if (!snapshot.arguments.getOrNull(0).equals("/c", ignoreCase = true)) return false
            snapshot.arguments.getOrNull(1)
        }

        else -> snapshot.arguments.firstOrNull()
    }
    return parseAbsoluteLegacyProcessPath(launcherArgument) in expectedLaunchers
}

private fun parseAbsoluteLegacyProcessPath(raw: String?): Path? {
    if (raw.isNullOrBlank()) return null
    return try {
        Path.of(raw).takeIf { it.isAbsolute }?.toAbsolutePath()?.normalize()
    } catch (e: Exception) {
        System.err.println("WARN: ignored an invalid legacy managed-backend process path: ${e.javaClass.simpleName}")
        null
    }
}

private fun parseConcreteBackendDirectoryNameOrNull(raw: String): ParsedConcreteBackendId? {
    val product = IdeProduct.knownProducts
        .sortedByDescending { it.id.length }
        .firstOrNull { raw.startsWith("${it.id}-") }
        ?: return null
    val version = raw.removePrefix("${product.id}-")
    if (!isSupportedBackendVersion(version)) return null
    return ParsedConcreteBackendId(product, version)
}

private fun backendProductSortIndex(productKey: String): Int =
    IdeProduct.knownProducts.indexOfFirst { it.id == productKey }.takeIf { it >= 0 } ?: Int.MAX_VALUE

fun renderBackendStartListText(
    rows: List<InstalledBackendListRow>,
    out: PrintStream,
    availableDownloads: List<AvailableBackendDownload>? = null,
) {
    if (rows.isEmpty()) {
        if (availableDownloads == null) {
            out.println("No backends installed. Use 'devrig backend download <id>' first.")
        } else {
            out.println("No managed backends are installed yet.")
            out.println()
            renderBackendDownloadListRowsText(
                rows = availableDownloads,
                out = out,
                afterRunLine = "Then: devrig backend start <id>",
            )
        }
        return
    }
    out.println("Installed backends:")
    out.println()
    val indexWidth = rows.size.toString().length + 2
    val idWidth = rows.maxOf { it.id.length }
    val displayWidth = rows.maxOf { it.displayName.codePointWidth() }
    for ((index, row) in rows.withIndex()) {
        val state = when (row.state) {
            LocalBackendState.RUNNING -> "running (pid ${row.pid})"
            LocalBackendState.INSTALLED -> "installed"
        }
        out.println("  ${"[${index + 1}]".padEnd(indexWidth)} ${row.id.padEnd(idWidth)}  ${row.displayName.padEndCodePoints(displayWidth)}  $state")
    }
    out.println()
    out.println("Run:  devrig backend start <id>")
    out.println()
}

fun renderBackendStopListText(rows: List<RunningBackendListRow>, out: PrintStream) {
    if (rows.isEmpty()) {
        out.println("No managed backends are currently running.")
        return
    }
    out.println("Running backends:")
    out.println()
    val indexWidth = rows.size.toString().length + 2
    val idWidth = rows.maxOf { it.id.length }
    val displayWidth = rows.maxOf { it.displayName.codePointWidth() }
    for ((index, row) in rows.withIndex()) {
        out.println("  ${"[${index + 1}]".padEnd(indexWidth)} ${row.id.padEnd(idWidth)}  ${row.displayName.padEndCodePoints(displayWidth)}  pid ${row.pid}")
    }
    out.println()
    out.println("Run:  devrig backend stop <id>")
    out.println()
}

fun renderBackendStartListJson(
    rows: List<InstalledBackendListRow>,
    out: PrintStream,
    availableDownloads: List<AvailableBackendDownload>? = null,
) {
    val payload = buildJsonObject {
        putToolJson()
        put("installed", buildJsonArray {
            for (row in rows) {
                add(buildJsonObject {
                    put("id", row.id)
                    put("productKey", row.productKey)
                    put("version", row.version)
                    put("displayName", row.displayName)
                    put("state", row.state.cliValue)
                    if (row.pid == null) put("pid", JsonNull) else put("pid", row.pid)
                    put("installPath", row.installPath.toString())
                    put("cachePath", row.cachePath.toString())
                })
            }
        })
        if (rows.isEmpty() && availableDownloads != null) {
            put("available", availableBackendDownloadsJson(availableDownloads))
            put("hint", "no managed backends installed; run 'devrig backend download <id>' first")
        }
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}

fun renderBackendStopListJson(rows: List<RunningBackendListRow>, out: PrintStream) {
    val payload = buildJsonObject {
        putToolJson()
        put("running", buildJsonArray {
            for (row in rows) {
                add(buildJsonObject {
                    put("id", row.id)
                    put("pid", row.pid)
                    put("displayName", row.displayName)
                    put("logPath", row.logPath.toString())
                })
            }
        })
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}
