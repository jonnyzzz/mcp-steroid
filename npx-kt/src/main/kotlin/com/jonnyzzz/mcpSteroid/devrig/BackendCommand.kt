/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeDiscoveryService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.server.BackendInfo
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.NpxStreamClientInfo
import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import java.io.OutputStream
import java.io.PrintStream
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val backendCommandLog = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.BackendCommand")

/**
 * One row in the `backend` subcommand's output. Two sources:
 *  - [FromMarker]: discovered via
 *    `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON marker. Projects are
 *    queryable through the IDE's `/npx/v1/projects/stream`.
 *  - [FromPort]: discovered via port scan of the IntelliJ built-in HTTP
 *    server (`/api/about`). No project list is available — older IDEs and
 *    IDEs without the mcp-steroid plugin still surface here so the operator
 *    sees the full picture.
 */
sealed interface BackendRow {
    /**
     * Full IDE identifier for the text header — includes the marketing version
     * already. Examples:
     *  - Marker: `"IntelliJ IDEA 2025.3.3"` (name + version concatenated)
     *  - Port:   `"IntelliJ IDEA 2026.1.1"` (productFullName already has the
     *            version baked in by `/api/about`)
     */
    val displayName: String

    /**
     * Short locator shown after [displayName] in parens. Carries the most
     * useful "how do I reach this IDE" hint per source — `pid` for markers,
     * `build` + `port` for port-discovered.
     */
    val locatorLabel: String
    val managed: Boolean

    data class FromMarker(
        val ide: DiscoveredIde,
        val projects: List<ProjectInfo>?,
        val errorMessage: String? = null,
        override val managed: Boolean = false,
    ) : BackendRow {
        override val displayName: String
            get() = markerBackendDisplayName(ide)
        override val locatorLabel: String get() = markerBackendLocatorLabel(ide) + if (managed) ", managed" else ""
    }

    data class FromPort(
        val ide: DiscoveredIdeByPort,
        override val managed: Boolean = false,
    ) : BackendRow {
        override val displayName: String
            get() = portBackendDisplayName(ide)
        override val locatorLabel: String get() = portBackendLocatorLabel(ide) + if (managed) ", managed" else ""
    }

    data class FromManaged(
        val info: ManagedBackendInfo,
    ) : BackendRow {
        override val displayName: String get() = "${info.productKey} ${info.version}"
        override val locatorLabel: String get() = when (info.state) {
            ManagedBackendState.INSTALLED -> "managed, installed"
            ManagedBackendState.RUNNING -> "managed, pid ${info.runningPid}"
            ManagedBackendState.UNREACHABLE -> "managed, unreachable"
        }
        override val managed: Boolean get() = true
    }
}

/**
 * Entry point invoked by [runCli] when the `backend` command is selected. Walks
 * both discovery paths in parallel:
 *  1. `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON markers → IDEs with project lists.
 *  2. Port scan of `127.0.0.1:63342..63361` and `:64342..64361` → IDEs
 *     reachable through their built-in HTTP server, including older ones
 *     that never wrote a marker.
 *
 * Renders one block per IDE on [out]. Exit status is always 0: "no IDE was
 * running" is a steady state on most machines, not a CLI error.
 *
 * @param json `true` ⇒ emit a single machine-readable JSON object instead of
 *  the human-readable list. The JSON is pretty-printed so a human can
 *  still read it without `jq`; `jq` accepts both forms equally.
 */
fun DevrigServices.runBackendCommand(command: DevrigCommand.DevrigCommandBackend): Int {
    val rows = collectBackendRows()
    if (command.json) {
        renderBackendJson(rows, mcpStdout)
    } else {
        renderBackendOutput(rows, mcpStdout)
    }
    return 0
}

/**
 * CLI entry point for the whole-model backend listing. Delegates to the shared [BackendInventory]
 * in CLI mode (one-shot marker scan + snapshot fetch) so `devrig backend` / `devrig project` and the
 * MCP `steroid_list_projects` / `steroid_list_windows` handlers can never diverge on discovery.
 */
fun DevrigServices.collectBackendRows(): List<BackendRow> =
    runBlocking(Dispatchers.IO) {
        cliBackendInventory(this@collectBackendRows).collectRows()
    }

fun scanMarkersOnce(
    homePaths: HomePaths = resolveHomePaths(),
): Set<DiscoveredIde> {
    val discovery = createIdeDiscoveryService(homePaths)
    discovery.scanOnce()
    return discovery.ides.value
}

fun DevrigServices.scanMarkersOnce(): Set<DiscoveredIde> {
    ideDiscovery.scanOnce()
    return ideDiscovery.ides.value
}

fun createIdeDiscoveryService(homePaths: HomePaths): IdeDiscoveryService {
    val allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal")
    return IdeDiscoveryService(
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
    out.println("id: ${result.id}")
    out.println("install: ${result.backendDir}")
    out.println("launcher: ${result.backendDir.resolve(result.descriptor.bundleDirName).resolve(result.descriptor.launcherPath)}")
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
 * One-shot port scan. Wraps [IntelliJPortDiscovery.scanOnce] + reads the
 * resulting set. Separate function so tests can drive it independently.
 */
suspend fun collectPortDiscoveredIdes(
    portDiscovery: IntelliJPortDiscovery,
): Set<DiscoveredIdeByPort> {
    portDiscovery.scanOnce()
    return portDiscovery.detected.value
}

/**
 * Merge marker rows (rich, with projects) and port-discovered IDEs (basic,
 * no projects). A port IDE is dropped when a marker row already represents
 * the same IDE — heuristic: same build number. This catches the common case
 * where one running IDE shows up in BOTH discoveries (it has mcp-steroid
 * plugin AND its built-in HTTP server is on a scanned port). When the IDE
 * has no mcp-steroid plugin, marker is absent and the port row survives.
 *
 * Exposed so a dedupe test can drive it without HTTP.
 */
fun mergeRows(
    markerRows: List<BackendRow.FromMarker>,
    portIdes: Set<DiscoveredIdeByPort>,
    managedBackends: List<ManagedBackendInfo> = emptyList(),
): List<BackendRow> {
    // PidMarker writes `IdeInfo.build = "IU-261.23567.138"` (with the product
    // code prefix), while `/api/about` returns `"261.23567.138"` (without).
    // Normalise both ends to the same shape before comparing — otherwise the
    // same running IDE is never deduplicated.
    val markerBuilds: Set<String> = markerRows
        .mapNotNull { normaliseBuildForDedup(it.ide.marker.ide.build) }
        .toSet()
    val markerManagedIds = markerRows.associateWith { row -> matchingManagedIds(row, managedBackends) }
    val annotatedMarkerRows = markerRows.map { row ->
        row.copy(managed = markerManagedIds.getValue(row).isNotEmpty())
    }
    val portManagedIds = mutableMapOf<DiscoveredIdeByPort, Set<String>>()
    val deduplicatedPortRows = portIdes
        .asSequence()
        .filter { ide ->
            val key = normaliseBuildForDedup(ide.buildNumber)
            key == null || key !in markerBuilds
        }
        .sortedBy { it.port }
        .map { ide ->
            val ids = matchingManagedIds(ide, managedBackends)
            portManagedIds[ide] = ids
            BackendRow.FromPort(ide, managed = ids.isNotEmpty())
        }
        .toList()
    val surfacedManagedIds = markerManagedIds.values.flatten().toSet() + portManagedIds.values.flatten().toSet()
    val managedRows = managedBackends
        .filter { it.id !in surfacedManagedIds }
        .map { BackendRow.FromManaged(it) }
    return annotatedMarkerRows + deduplicatedPortRows + managedRows
}

/**
 * Strip a leading product-code prefix (letters + hyphen, e.g. `IU-`, `PC-`,
 * `GO-`) so marker builds (`IU-261.23567.138`) compare equal to `/api/about`
 * builds (`261.23567.138`). Returns `null` for null/blank input so callers
 * can use it as a Map key without further filtering.
 */
fun normaliseBuildForDedup(build: String?): String? {
    if (build.isNullOrBlank()) return null
    return build.replaceFirst(Regex("^[A-Z]+-"), "")
}

private fun matchingManagedIds(
    row: BackendRow.FromMarker,
    managedBackends: List<ManagedBackendInfo>,
): Set<String> {
    // A marker row carries the real IDE pid, so correlate by pid only. Matching on the (normalised)
    // build would mis-flag any unrelated IDE that merely shares the build baseline — e.g. a user's
    // IDEA Ultimate (IU-261.x) getting tagged "managed" because a managed IDEA Community (IC-261.x)
    // is running. The managed process always writes its own marker, so pid == runningPid is exact.
    return managedBackends
        .filter { it.state == ManagedBackendState.RUNNING && it.runningPid == row.ide.pid }
        .map { it.id }
        .toSet()
}

private fun matchingManagedIds(
    ide: DiscoveredIdeByPort,
    managedBackends: List<ManagedBackendInfo>,
): Set<String> {
    val portBuild = normaliseBuildForDedup(ide.buildNumber)
    return managedBackends
        .filter { it.state == ManagedBackendState.RUNNING }
        .filter { managed ->
            portBuild != null && portBuild == normaliseBuildForDedup(managed.buildNumber)
        }
        .map { it.id }
        .toSet()
}

/**
 * Connect to each marker-discovered IDE in parallel, await the first
 * `type=snapshot` envelope, then close. Returns one row per input IDE in
 * the same order.
 *
 * Per-IDE timeout is enforced inside the helper so one slow IDE doesn't
 * block the others. A timeout / error is recorded on the row's
 * [BackendRow.FromMarker.projects] = `null` so the renderer can flag the
 * IDE without taking down the whole list.
 */
suspend fun collectMarkerSnapshots(
    httpClient: HttpClient,
    ides: List<DiscoveredIde>,
    perIdeTimeout: Duration,
    clientInfo: NpxStreamClientInfo = backendCommandClientInfo(),
): List<BackendRow.FromMarker> = coroutineScope {
    ides.map { ide ->
        async { fetchSnapshotForIde(httpClient, ide, perIdeTimeout, clientInfo) }
    }.awaitAll()
}

private suspend fun fetchSnapshotForIde(
    httpClient: HttpClient,
    ide: DiscoveredIde,
    timeout: Duration,
    clientInfo: NpxStreamClientInfo,
): BackendRow.FromMarker {
    val snapshot = try {
        withTimeoutOrNull(timeout) { fetchFirstSnapshot(httpClient, ide, clientInfo) }
    } catch (e: Exception) {
        return BackendRow.FromMarker(ide, projects = null, errorMessage = e.message ?: e::class.simpleName)
    }
    return when (snapshot) {
        null -> BackendRow.FromMarker(ide, projects = null, errorMessage = "timed out after $timeout")
        else -> BackendRow.FromMarker(ide, projects = snapshot)
    }
}

/**
 * Open the bridge's `…/projects/stream`, drain envelopes until the first `snapshot`,
 * return its `projects` list (empty list if the IDE has nothing open). The
 * caller's `withTimeoutOrNull` bounds total time including the connect.
 */
private suspend fun fetchFirstSnapshot(
    httpClient: HttpClient,
    ide: DiscoveredIde,
    clientInfo: NpxStreamClientInfo,
): List<ProjectInfo> {
    val url = ide.rpcBaseUrl + "/projects/stream"
    val body = NpxStreamJson.encodeClientInfo(clientInfo)

    return httpClient.preparePost(url) {
        headers {
            append(HttpHeaders.ContentType, "application/json")
            for ((name, value) in ide.bridgeHeaders) {
                append(name, value)
            }
        }
        setBody(body)
    }.execute { response ->
        if (response.status.value !in 200..299) {
            error("HTTP ${response.status.value}")
        }
        val channel = response.bodyAsChannel()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            val env = try {
                NpxStreamJson.decodeEnvelope(line)
            } catch (e: Exception) {
                // Skip malformed envelopes; the snapshot is on a later line.
                continue
            }
            if (env.type == "snapshot") {
                return@execute env.projects ?: emptyList()
            }
        }
        error("stream closed before a snapshot envelope arrived")
    }
}

private fun backendCommandClientInfo(): NpxStreamClientInfo =
    NpxStreamClientInfo(
        client = "devrig (backend)",
        clientPid = ProcessHandle.current().pid(),
        clientVersion = DevrigVersionMetadata.getDevrigVersion(),
        clientInstanceId = "backend-${UUID.randomUUID()}",
        platform = System.getProperty("os.name"),
        arch = System.getProperty("os.arch"),
    )

/**
 * Pure renderer — separated from [runBackendCommand] so a unit test can
 * exercise every formatting branch without touching the network.
 *
 * Output shape:
 * ```
 * Discovered N backend(s):
 *
 *   [1] <IDE name> <version> (<locator>)
 *         MCP Steroid: <version>
 *         <project-name>  →  <project-path>
 *         …
 *
 *   [2] <IDE name> <version> (<locator>)
 *         MCP Steroid: not installed
 *         (project list unavailable)
 *
 * ```
 * The list uses `[N]` index markers so it visibly is a list, and the trailing
 * blank line gives shells a clean separator.
 */
fun renderBackendOutput(rows: List<BackendRow>, out: PrintStream) {
    if (rows.isEmpty()) {
        out.println(NO_BACKENDS_DETECTED_MESSAGE)
        out.println()
        return
    }
    val noun = if (rows.size == 1) "backend" else "backends"
    out.println("Discovered ${rows.size} $noun:")
    out.println()
    for ((index, row) in rows.withIndex()) {
        out.println("  [${index + 1}] ${backendDisplayName(row)} (${backendLocatorLabel(row)})${backendActionSuffix(row)}")
        out.println("        ${backendPluginStatusText(row)}")
        when (row) {
            is BackendRow.FromMarker -> renderMarkerProjects(row, out)
            is BackendRow.FromPort -> out.println(
                "        (project list unavailable)"
            )
            is BackendRow.FromManaged -> renderManagedBackend(row.info, out)
        }
        if (index < rows.lastIndex) out.println()
    }
    // Trailing blank line so piped consumers / terminals don't glue the
    // next prompt to the last project line.
    out.println()
}

private fun backendActionSuffix(row: BackendRow): String = when (row) {
    is BackendRow.FromPort -> " (run: ${provisionCommand(provisionTargetId(row.ide.port))})"
    is BackendRow.FromMarker,
    is BackendRow.FromManaged -> ""
}

/**
 * Pretty-printed JSON renderer for the `backend --json` form. Designed for
 * scripted consumption (`devrig backend --json | jq …`):
 *  - **No banner** — stdout is one JSON document, nothing else.
 *  - **Top-level object** with `tool`, `backends`, and flat `projects`.
 *  - **`backend_name`** is the R3.3 uniform id (`<productCodeLower>-<hash8>`),
 *    computed the same way for every source so devrig can round-trip it.
 *  - **Pretty-printed**: humans can read without `jq -P`, scripts don't care.
 *
 * Example query:
 *   devrig backend --json | jq '.backends[] | select(.source=="marker")'
 */
fun renderBackendJson(rows: List<BackendRow>, out: PrintStream) {
    val rowsWithIds = backendRowsWithStableIds(rows)
    // encodeDefaults so meaningful defaults (type/routable/managed/...) are emitted; explicitNulls=false
    // so absent optional fields (error/port/portDetail/...) are omitted rather than serialised as null —
    // matching the old hand-built backendEntryJson and keeping `jq` consumers simple.
    val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    // Build each backend's owned projects first so they can be embedded AND flattened identically.
    val projectsByRow: Map<BackendRow, List<ListedProject>> = rowsWithIds.associate { (backendName, row) ->
        row to listedProjectsForRow(backendName, row)
    }
    val backends = rowsWithIds.map { (backendName, row) ->
        backendInfoForRow(row, backendName = backendName, openProjects = projectsByRow.getValue(row))
    }
    val projects = rowsWithIds.flatMap { (_, row) -> projectsByRow.getValue(row) }
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", "devrig")
            put("version", DevrigVersionMetadata.getDevrigVersion())
        })
        put("backends", json.encodeToJsonElement(ListSerializer(BackendInfo.serializer()), backends))
        put("projects", json.encodeToJsonElement(ListSerializer(ListedProject.serializer()), projects))
    }
    out.println(json.encodeToString(JsonObject.serializer(), payload))
}

/**
 * Pairs every row with its R3.3 `backend_name`, de-duplicating by keep-first + WARN. Shared by
 * `backend/project --json` and the MCP handlers' `backends[]` (via `collectBackendInfos`).
 */
fun backendRowsWithStableIds(rows: List<BackendRow>): List<Pair<String, BackendRow>> {
    val rowsWithIds = rows.map { row -> backendNameForRow(row) to row }
    val ids = rowsWithIds.map { it.first }
    if (ids.toSet().size != rows.size) {
        val duplicateIds = ids.groupingBy { it }.eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
        backendCommandLog.warn(
            "Duplicate backend_name in backend --json output: {}. Keeping the first row for each duplicate id.",
            duplicateIds.joinToString(", "),
        )
    }

    val seen = LinkedHashSet<String>()
    return rowsWithIds.filter { (id, _) -> seen.add(id) }
}

/**
 * Maps a row's reachable projects to [ListedProject], computing the SAME devrig-exposed `project_name`
 * (`<name>-<projectHash>`) that the MCP `steroid_list_projects` / routing layer surfaces, so CLI and MCP
 * agree (R3.7). Only marker rows carry projects. The raw folder [ListedProject.name] is preserved so
 * existing `jq '.projects[].name'` consumers keep working.
 */
private fun listedProjectsForRow(backendName: String, row: BackendRow): List<ListedProject> {
    if (row !is BackendRow.FromMarker) return emptyList()
    val projects = row.projects ?: return emptyList()
    return projects.map { project ->
        ListedProject(
            projectName = exposedProjectName(project, row.ide.pid),
            name = project.name,
            path = project.path,
            backendName = backendName,
        )
    }
}

/**
 * Devrig-exposed disambiguated project name = `<name>-<projectHash>`, where `projectHash` salts on the
 * canonical project home + the owning IDE pid (same scheme as [DevrigProjectRoutingService]). Falls back
 * to the raw folder name when the path cannot be canonicalized (e.g. an already-closed project), so the
 * CLI never crashes mid-render.
 */
private fun exposedProjectName(project: ProjectInfo, idePid: Long): String {
    val hash = try {
        val realHome = com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService.canonicalProjectHome(project.path)
        com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService.projectHash(realHome, idePid)
    } catch (e: Exception) {
        backendCommandLog.debug("Cannot compute project hash for {} (pid {}): {}", project.path, idePid, e.message)
        return project.name
    }
    return "${project.name}-$hash"
}

private fun renderMarkerProjects(row: BackendRow.FromMarker, out: PrintStream) {
    when {
        row.projects == null -> {
            val reason = row.errorMessage ?: "unreachable"
            out.println("        (unreachable: $reason)")
        }
        row.projects.isEmpty() -> {
            out.println("        (no open projects)")
        }
        else -> {
            // Right-pad project names so `→` arrows line up — turns the inner
            // list into a small two-column table when more than one project
            // is open. Cap the pad so a single unusually long name doesn't
            // push every other row's path off the screen.
            val padWidth = row.projects.maxOf { it.name.codePointWidth() }.coerceAtMost(40)
            for (p in row.projects) {
                val paddedName = p.name.padEndCodePoints(padWidth)
                out.println("        $paddedName  →  ${p.path}")
            }
        }
    }
}

private fun renderManagedBackend(info: ManagedBackendInfo, out: PrintStream) {
    out.println("        state: ${info.state.name.lowercase()}${info.runningPid?.let { " (pid $it)" }.orEmpty()}")
    out.println("        install: ${info.installPath}")
    out.println("        cache: ${info.cachePath}")
}
