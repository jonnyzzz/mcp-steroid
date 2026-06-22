/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.PortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
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
 * Consumes its discovery services directly — marker rows are derived from [routing] (the same
 * [DevrigProjectRoutingService] the MCP handlers use, so discovery can never diverge), port-only rows
 * from a bounded scan of [portDiscovery], and managed rows from [managedBackends].
 *
 * Shared across both modes:
 *  - managed rows come from `backendManager.list()`; a managed row claiming a `runningPid` is verified
 *    against process liveness ([isProcessAlive]) BEFORE any HTTP work, so a dead pid degrades to
 *    not-running instead of triggering futile probes;
 *  - the port scan is bounded to ~1 second total ([boundedPortScan]);
 *  - [mergeRows] de-duplicates (a managed-running IDE surfaces as its marker row, never twice).
 */
class BackendInventory(
    private val routing: DevrigProjectRoutingService,
    private val portDiscovery: PortDiscovery,
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
            val portIdesAsync = async { boundedPortScan(portDiscovery) }
            mergeRows(markerRowsAsync.await(), portIdesAsync.await(), managed)
        }
    }

    /**
     * Marker-discovered IDEs as rich [BackendRow.FromMarker]s, each carrying its owned project routes.
     * Sorted by IDE name then backend_name for a stable listing. Derived from [routing] so the rows
     * match exactly what `steroid_list_projects` routes against.
     */
    private fun markerRows(): List<BackendRow.FromMarker> {
        val routesByBackend = routing.routes().groupBy { it.route.backendName }
        return routing.discoveredBackends()
            .sortedWith(compareBy({ it.ide.name }, { it.backendName }))
            .map { ide -> BackendRow.FromMarker(ide = ide, projects = routesByBackend[ide.backendName].orEmpty()) }
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
private suspend fun boundedPortScan(
    portDiscovery: PortDiscovery,
    budget: Duration = 1.seconds,
): Set<DiscoveredIdeByPort> =
    withTimeoutOrNull(budget) { collectPortDiscoveredIdes(portDiscovery) } ?: emptySet()

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
