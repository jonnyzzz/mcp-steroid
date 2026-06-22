/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.BackendInfo
import com.jonnyzzz.mcpSteroid.server.ListedProject
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
    is BackendRow.FromMarker -> backendNameForMarker(row.ide.pid, row.ide.ide.build)
    is BackendRow.FromPort -> backendNameForPort(row.ide.port, row.ide.buildNumber)
    // Managed buildNumbers come without the product prefix ("261.x"); re-attach the known productCode
    // so the backend_name carries the product hint ("pc-...") instead of the "ide-" fallback.
    is BackendRow.FromManaged -> backendNameForManaged(row.info.id, "${row.info.productCode}-${row.info.buildNumber ?: ""}")
}

/**
 * R3.4 — maps a discovery [BackendRow] to the single shared [BackendInfo] schema. The ONE representation
 * backing both the MCP `steroid_list_projects` `backends[]` and the devrig CLI `backend/project --json`
 * `backends[]`. No field of the historical hand-built `backendEntryJson` is dropped (see R3.4 inventory).
 *
 * @param backendName precomputed (and de-duped) id for this row — passed in so the caller controls
 *   keep-first de-duplication; defaults to [backendNameForRow].
 * @param openProjects the projects owned by this backend, already mapped to [ListedProject].
 * @param managed whether this row is a devrig-managed backend (prefer it).
 */
fun backendInfoForRow(
    row: BackendRow,
    backendName: String = backendNameForRow(row),
    openProjects: List<ListedProject> = emptyList(),
    managed: Boolean = row.managed,
): BackendInfo = when (row) {
    is BackendRow.FromMarker -> {
        val ide = row.ide
        val reachable = row.projects != null
        markerBackendInfo(
            backendName = backendName,
            pid = ide.pid,
            ide = ide.ide,
            plugins = mcpSteroidPlugins(ide.plugin),
            openProjects = openProjects,
            managed = managed,
            routable = reachable,
            reachable = reachable,
            // An unreachable marker (projects == null) must carry a non-null reason; fall back when the
            // fetch failure left no message. A reachable marker has no error.
            error = if (reachable) null else (row.errorMessage ?: "unreachable"),
            locator = backendLocatorLabel(row),
        )
    }
    is BackendRow.FromPort -> {
        val ide = row.ide
        BackendInfo(
            backendName = backendName,
            source = "port",
            displayName = portBackendDisplayName(ide),
            locator = portBackendLocatorLabel(ide),
            routable = false,
            reachable = true,
            managed = managed,
            port = ide.port,
            ideProductCode = productCodeFromBuild(ide.buildNumber),
            build = ide.buildNumber,
            portDetail = PortBackendDetail(
                baseUrl = ide.baseUrl,
                productName = ide.productName,
                productFullName = ide.productFullName,
                edition = ide.edition,
                baselineVersion = ide.baselineVersion,
                buildNumber = ide.buildNumber,
            ),
            openProjects = openProjects,
        )
    }
    is BackendRow.FromManaged -> {
        val info = row.info
        BackendInfo(
            backendName = backendName,
            source = "managed",
            displayName = backendDisplayName(row),
            locator = backendLocatorLabel(row),
            routable = false,
            reachable = info.state == ManagedBackendState.RUNNING,
            managed = true,
            pid = info.runningPid,
            ideProductCode = productCodeFromBuild(info.buildNumber) ?: info.productCode,
            build = info.buildNumber,
            managedDetail = ManagedBackendDetail(
                managedId = info.id,
                productKey = info.productKey,
                productCode = info.productCode,
                version = info.version,
                buildNumber = info.buildNumber,
                state = info.state.name.lowercase(),
                installPath = info.installPath.toString(),
                cachePath = info.cachePath.toString(),
                runningPid = info.runningPid,
            ),
            openProjects = openProjects,
        )
    }
}

