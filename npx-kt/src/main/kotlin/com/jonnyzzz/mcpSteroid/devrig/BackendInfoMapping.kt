/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.BackendInfo
import com.jonnyzzz.mcpSteroid.server.ManagedBackendDetail
import com.jonnyzzz.mcpSteroid.server.PortBackendDetail
import com.jonnyzzz.mcpSteroid.server.backendNameFor
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.markerBackendInfo
import com.jonnyzzz.mcpSteroid.server.mcpSteroidPlugins
import com.jonnyzzz.mcpSteroid.server.productCodeFromBuild

// R3.3 — the shared backend_name formula (backendNameFor + backendNameForMarker) lives in
// mcp-steroid-server (com.jonnyzzz.mcpSteroid.server.BackendName) so the in-IDE plugin and devrig
// recompute the same id for the same input. The port/managed variants below are devrig-only sources.

/** Port-discovered backend_name: keyed by the scanned port. */
fun backendNameForPort(port: Int, build: String?): String =
    backendNameFor(sourceKey = "port:$port", build = build)

/** Managed-backend backend_name: keyed by the managed id (works before the backend is running). */
fun backendNameForManaged(managedId: String, build: String?): String =
    backendNameFor(sourceKey = "managed:$managedId", build = build)

/** The R3.3 backend_name for any discovery row. */
fun backendNameForRow(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> backendNameForMarker(row.ide.pid, row.ide.marker.ide.build)
    is BackendRow.FromPort -> backendNameForPort(row.ide.port, row.ide.buildNumber)
    // Managed buildNumbers come without the product prefix ("261.x"); re-attach the known productCode
    // so the backend_name carries the product hint ("pc-...") instead of the "ide-" fallback.
    is BackendRow.FromManaged -> backendNameForManaged(row.info.id, "${row.info.productCode}-${row.info.buildNumber ?: ""}")
}

/**
 * R3.4 — maps a discovery [BackendRow] to the single shared [BackendInfo] schema. The ONE representation
 * backing both the MCP `steroid_list_projects` `backends[]` and the devrig CLI `backend/project --json`
 * `backends[]`. De-duplicated per #90: each fact serializes exactly once (no `openProjects` — join the
 * flat `projects[]` on `backend_name`; no `locator` — `build`/`pid`/`port` are the canonical fields).
 *
 * @param backendName precomputed (and de-duped) id for this row — passed in so the caller controls
 *   keep-first de-duplication; defaults to [backendNameForRow].
 * @param managed whether this row is a devrig-managed backend (prefer it).
 */
fun backendInfoForRow(
    row: BackendRow,
    backendName: String = backendNameForRow(row),
    managed: Boolean = row.managed,
): BackendInfo = when (row) {
    is BackendRow.FromMarker -> {
        val ide = row.ide
        val reachable = row.projects != null
        markerBackendInfo(
            backendName = backendName,
            pid = ide.pid,
            ide = ide.marker.ide,
            plugins = mcpSteroidPlugins(ide.marker.plugin),
            managed = managed,
            routable = reachable,
            reachable = reachable,
            error = if (!reachable) (row.errorMessage ?: "unreachable") else null,
        )
    }
    is BackendRow.FromPort -> {
        val ide = row.ide
        BackendInfo(
            backendName = backendName,
            source = "port",
            displayName = portBackendDisplayName(ide),
            routable = false,
            reachable = true,
            managed = managed,
            port = ide.port,
            ideProductCode = productCodeFromBuild(ide.buildNumber),
            build = ide.buildNumber,
            portDetail = PortBackendDetail(
                baseUrl = ide.baseUrl,
                edition = ide.edition,
                baselineVersion = ide.baselineVersion,
            ),
        )
    }
    is BackendRow.FromManaged -> {
        val info = row.info
        BackendInfo(
            backendName = backendName,
            source = "managed",
            displayName = backendDisplayName(row),
            routable = false,
            reachable = info.state == ManagedBackendState.RUNNING,
            managed = true,
            pid = info.runningPid,
            ideProductCode = productCodeFromBuild(info.buildNumber) ?: info.productCode,
            build = info.buildNumber,
            managedDetail = ManagedBackendDetail(
                managedId = info.id,
                state = info.state.name.lowercase(),
                installPath = info.installPath.toString(),
                cachePath = info.cachePath.toString(),
            ),
        )
    }
}

