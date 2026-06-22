/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import java.io.PrintStream

data class ProjectListing(
    val markerRows: List<BackendRow.FromMarker>,
    val portRows: List<BackendRow.FromPort>,
    val managedRows: List<BackendRow.FromManaged> = emptyList(),
)

fun DevrigServices.runProjectCommand(command: DevrigCommand.DevrigCommandProject): Int {
    val listing = projectListingFromRows(collectBackendRows())
    if (command.json) {
        renderProjectJson(listing, mcpStdout)
    } else {
        renderProjectOutput(listing, mcpStdout)
    }
    return 0
}

fun projectListingFromRows(rows: List<BackendRow>): ProjectListing = ProjectListing(
    markerRows = rows.filterIsInstance<BackendRow.FromMarker>(),
    portRows = rows.filterIsInstance<BackendRow.FromPort>(),
    managedRows = rows.filterIsInstance<BackendRow.FromManaged>(),
)

/**
 * Pure renderer for `devrig project`.
 *
 * Output shape:
 * ```
 * Listing N open project(s) across M backend(s):
 *
 *   [1] <project-name>  →  <project-path>
 *         <IDE name> <version> (pid <pid>)
 *         MCP Steroid: <version>
 *
 * Skipped K backend(s) with MCP Steroid not installed:
 *   - <IDE display> (build <build>, port <port>)
 *
 * ```
 *
 * The project rows are a flattened view of the same marker snapshots the
 * `backend` command fetches. Port-only IDEs and marker IDEs whose snapshot
 * fetch failed are excluded from the project list and reported in a footer.
 */
fun renderProjectOutput(listing: ProjectListing, out: PrintStream) {
    if (listing.markerRows.isEmpty() && listing.portRows.isEmpty() && listing.managedRows.isEmpty()) {
        out.println(NO_BACKENDS_DETECTED_MESSAGE)
        out.println()
        return
    }

    val reachableRows = listing.markerRows.filter { it.projects != null }
    val projectEntries = reachableRows.flatMap { row ->
        // Unreachable markers (projects == null) are excluded above; orEmpty guards the reachable-but-idle case.
        row.projects.orEmpty().map { project -> ProjectEntry(row, project) }
    }

    if (projectEntries.isEmpty()) {
        out.println("No open projects across ${reachableRows.size} backend(s).")
        renderSkippedProjectFooter(listing, out)
        out.println()
        return
    }

    out.println("Listing ${projectEntries.size} open project(s) across ${reachableRows.size} backend(s):")
    out.println()
    val padWidth = projectEntries.maxOf { it.project.exposedProjectName.codePointWidth() }.coerceAtMost(40)
    for ((index, entry) in projectEntries.withIndex()) {
        val paddedName = entry.project.exposedProjectName.padEndCodePoints(padWidth)
        out.println("  [${index + 1}] $paddedName  →  ${entry.project.projectPath}")
        out.println("        ${backendDisplayName(entry.row)} (${backendLocatorLabel(entry.row)})")
        out.println("        ${backendPluginStatusText(entry.row)}")
        if (index < projectEntries.lastIndex) out.println()
    }

    renderSkippedProjectFooter(listing, out)
    out.println()
}

/**
 * Pretty-printed JSON renderer for `devrig project --json`.
 *
 * Output shape (shared R3.4 BackendInfo / ListedProject schema):
 * ```
 * {
 *   "tool": { "name": "devrig", "version": "..." },
 *   "backends": [
 *     { "backend_name": "iu-9fk2a0xQ", "type": "intellij", "source": "marker", "routable": true, ... }
 *   ],
 *   "projects": [
 *     { "project_name": "myproject-1z8KqM03", "name": "myproject",
 *       "path": "/Users/me/myproject", "backend_name": "iu-9fk2a0xQ" }
 *   ]
 * }
 * ```
 *
 * Delegates to [renderBackendJson] so `project --json` is byte-for-byte
 * identical to `backend --json` for the same discovery rows.
 */
fun renderProjectJson(listing: ProjectListing, out: PrintStream) {
    val rows: List<BackendRow> = listing.markerRows + listing.portRows + listing.managedRows
    renderBackendJson(rows, out)
}

private data class ProjectEntry(
    val row: BackendRow.FromMarker,
    val project: ProjectRoute,
)

private fun renderSkippedProjectFooter(
    listing: ProjectListing,
    out: PrintStream,
) {
    val unreachableRows = listing.markerRows.filter { it.projects == null }
    val portRows = listing.portRows
    if (unreachableRows.isEmpty() && portRows.isEmpty()) return

    out.println()

    if (unreachableRows.isNotEmpty()) {
        out.println("Skipped ${unreachableRows.size} ${backendNoun(unreachableRows.size)} that did not return a project snapshot:")
        for (row in unreachableRows) {
            out.println("  - ${backendDisplayName(row)} (${backendLocatorLabel(row)}): unreachable: ${row.errorMessage ?: "unreachable"}")
            out.println("    ${backendPluginStatusText(row)}")
        }
        if (portRows.isNotEmpty()) out.println()
    }

    if (portRows.isNotEmpty()) {
        out.println("Skipped ${portRows.size} ${backendNoun(portRows.size)} with MCP Steroid not installed:")
        for (row in portRows) {
            out.println("  - ${backendDisplayName(row)} (${backendLocatorLabel(row)}): ${backendPluginStatusText(row)}")
        }
    }
}

private fun backendNoun(count: Int): String = if (count == 1) "backend" else "backends"
