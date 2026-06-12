/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.server.BackendInfo
import com.jonnyzzz.mcpSteroid.server.ListedProject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The ONE component producing the whole-model backend listing ([BackendRow]s: markers + port-discovered
 * + managed), shared by the CLI (`devrig backend` / `devrig project` via [collectBackendRows]) and the
 * MCP `steroid_list_projects` / `steroid_list_windows` handlers.
 *
 * The marker-row source is a strategy so the two modes differ ONLY in where marker rows come from:
 *  - CLI mode ([cliBackendInventory]): one-shot marker scan + per-IDE snapshot fetch over HTTP
 *    (today's `backend` command behavior).
 *  - MCP mode ([monitorBackendInventory]): the monitor's cached state — NO network re-fetch on a tool
 *    call; the monitor's streaming connection keeps snapshots fresh.
 *
 * Shared across both modes:
 *  - managed rows come from `backendManager.list()`; a managed row claiming a `runningPid` is verified
 *    against process liveness ([isProcessAlive]) BEFORE any HTTP work, so a dead pid degrades to
 *    not-running instead of triggering futile probes;
 *  - the port scan is bounded to ~1 second total ([boundedPortScan]);
 *  - [mergeRows] de-duplicates (a managed-running IDE surfaces as its marker row, never twice).
 */
class BackendInventory(
    private val markerRows: suspend () -> List<BackendRow.FromMarker>,
    private val portIdes: suspend () -> Set<DiscoveredIdeByPort>,
    private val managedBackends: () -> List<ManagedBackendInfo>,
    private val isProcessAlive: (Long) -> Boolean = { pid -> DefaultManagedProcessInspector.isAlive(pid) },
) {
    suspend fun collectRows(): List<BackendRow> {
        // Managed rows first — liveness of every claimed runningPid is settled BEFORE any HTTP below,
        // so a dead managed pid can never be mistaken for a running backend by the merge.
        val managed = managedBackends().map(::verifiedLiveness)
        return coroutineScope {
            // Marker fetch and port scan are independent localhost operations — run them concurrently.
            val markerRowsAsync = async { markerRows() }
            val portIdesAsync = async { portIdes() }
            mergeRows(markerRowsAsync.await(), portIdesAsync.await(), managed)
        }
    }

    /**
     * Downgrades a managed row whose claimed `runningPid` is dead: the pid file is stale, the backend is
     * not running. State becomes [ManagedBackendState.UNREACHABLE] (same verdict `BackendManager.list()`
     * reaches for a stale pid file) and the pid is dropped so [mergeRows] never correlates it with an
     * unrelated marker row.
     */
    private fun verifiedLiveness(info: ManagedBackendInfo): ManagedBackendInfo {
        val pid = info.runningPid ?: return info
        if (isProcessAlive(pid)) return info
        return info.copy(runningPid = null, state = ManagedBackendState.UNREACHABLE)
    }
}

/**
 * One bounded port-scan pass: [collectPortDiscoveredIdes] cut off at [budget] total. On timeout the
 * last completed scan's result is returned (empty on a first-ever scan) — the listing degrades to
 * "no port-only rows" instead of stalling the whole response behind a slow probe.
 */
suspend fun boundedPortScan(
    portDiscovery: IntelliJPortDiscovery,
    budget: Duration = 1.seconds,
): Set<DiscoveredIdeByPort> =
    withTimeoutOrNull(budget) { collectPortDiscoveredIdes(portDiscovery) }
        ?: portDiscovery.detected.value

/**
 * CLI-mode inventory: today's `devrig backend` discovery — one-shot marker scan, then a per-IDE
 * `/projects/stream` snapshot fetch (8 s per IDE, parallel).
 */
fun cliBackendInventory(services: DevrigServices): BackendInventory = BackendInventory(
    markerRows = {
        services.ideDiscovery.scanOnce()
        val ides = services.ideDiscovery.ides.value
            .sortedWith(compareBy({ it.marker.ide.name }, { it.pid }))
        collectMarkerSnapshots(
            httpClient = services.commandHttpClient,
            ides = ides,
            perIdeTimeout = 8.seconds,
            clientInfo = services.clientInfo,
        )
    },
    portIdes = { boundedPortScan(services.portDiscovery) },
    managedBackends = { services.backendManager.list() },
)

/**
 * MCP-mode inventory: marker rows come from the IDE monitor's cached state (the streaming connection
 * keeps `lastSnapshot` fresh) — a tool call never re-fetches project snapshots over HTTP.
 */
fun monitorBackendInventory(services: DevrigServices): BackendInventory = BackendInventory(
    markerRows = {
        services.ideMonitor.states.value.values
            .sortedWith(compareBy({ it.ide.marker.ide.name }, { it.ide.pid }))
            .map { state -> BackendRow.FromMarker(ide = state.ide, projects = state.lastSnapshot) }
    },
    portIdes = { boundedPortScan(services.portDiscovery) },
    managedBackends = { services.backendManager.list() },
)

/**
 * Maps the whole inventory to the shared MCP/CLI `backends[]` schema: stable de-duplicated
 * `backend_name`s (keep-first + WARN, same as `backend --json`) and one [BackendInfo] per row via
 * [backendInfoForRow]. [openProjectsFor] lets `steroid_list_projects` attach each marker backend's
 * owned projects; the default leaves `openProjects` empty (port/managed rows never own projects).
 */
suspend fun BackendInventory.collectBackendInfos(
    openProjectsFor: (backendName: String, row: BackendRow) -> List<ListedProject> = { _, _ -> emptyList() },
): List<BackendInfo> =
    backendRowsWithStableIds(collectRows()).map { (backendName, row) ->
        backendInfoForRow(
            row = row,
            backendName = backendName,
            openProjects = openProjectsFor(backendName, row),
        )
    }
