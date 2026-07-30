/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import java.io.PrintStream
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

fun DevrigServices.runProjectCommand(command: DevrigCommand.DevrigCommandProject): Int {
    val routes = projectRouting.routes()
    if (command.json) {
        renderProjectJson3(routes, mcpStdout)
    } else {
        // Port scan only feeds the text "skipped backends" footer; --json ignores it, so skip the 1s cost there.
        val s2 = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(1.seconds) { collectPortDiscoveredIdes(portDiscovery) } ?: emptySet()
        }
        renderProjectOutput3(routes, s2, mcpStdout)
    }
    return 0
}

/**
 * Pure renderer for `devrig project`.
 *
 * Output shape:
 * ```
 * Listing N open project(s) across M backend(s):
 *
 *   [1] <project-name>  →  <project-path>
 *         <IDE name> <version> (<backend_name>; build <build>, pid <pid>)
 *         MCP Steroid: <version>
 *
 * Skipped K backend(s) with MCP Steroid not installed:
 *   - <IDE display> (build <build>, port <port>)
 *
 * ```
 */
fun renderProjectOutput3(
    routes: List<ProjectRoute>,
    portIdes: Set<com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort>,
    out: PrintStream,
) {
    // Group routes by their owning IDE (DiscoveredIde)
    val routesByIde: Map<DiscoveredIde, List<ProjectRoute>> = routes.groupBy { it.route }
    val reachableIdeCount = routesByIde.keys.size

    if (routes.isEmpty() && portIdes.isEmpty()) {
        out.println(NO_BACKENDS_DETECTED_MESSAGE)
        out.println()
        return
    }

    if (routes.isEmpty()) {
        out.println("No open projects across $reachableIdeCount backend(s).")
        renderPortSkippedFooter(portIdes, out)
        out.println()
        return
    }

    val backendCount = routesByIde.keys.size
    out.println("Listing ${routes.size} open project(s) across $backendCount backend(s):")
    out.println()

    // Same ordering and identity key as the MCP steroid_list_projects response (#155):
    // projects sort by project_name, and every entry names its backend_name.
    val sortedRoutes = routes.sortedBy { it.exposedProjectName }
    val padWidth = sortedRoutes.maxOf { it.exposedProjectName.codePointWidth() }.coerceAtMost(40)
    for ((index, route) in sortedRoutes.withIndex()) {
        val paddedName = route.exposedProjectName.padEndCodePoints(padWidth)
        out.println("  [${index + 1}] $paddedName  →  ${route.projectPath}")
        out.println("        ${markerBackendDisplayName(route.route)} (${route.exposedBackendName}; ${markerBackendLocatorLabel(route.route)})")
        val plugin = route.route.plugin
        out.println("        ${plugin.name.ifBlank { "MCP Steroid" }}: ${plugin.version.ifBlank { "unknown" }}")
        if (index < sortedRoutes.lastIndex) out.println()
    }

    renderPortSkippedFooter(portIdes, out)
    out.println()
}

private fun renderPortSkippedFooter(
    portIdes: Set<com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort>,
    out: PrintStream,
) {
    if (portIdes.isEmpty()) return
    out.println()
    out.println("Skipped ${portIdes.size} ${backendNoun(portIdes.size)} with MCP Steroid not installed:")
    for (ide in portIdes.sortedBy { it.port }) {
        out.println("  - ${portBackendDisplayName(ide)} (${portBackendLocatorLabel(ide)}): MCP Steroid: not installed")
    }
    out.println()
    out.println("Install MCP Steroid into the IDE(s) above (each shows a confirmation dialog): $INSTALL_PLUGIN_COMMAND")
}

/**
 * Pretty-printed JSON renderer for `devrig project --json`.
 *
 * Output shape:
 * ```json
 * {
 *   "tool": { "name": "devrig", "version": "..." },
 *   "projects": [
 *     { "project_name": "...", "name": "...", "path": "...", "backend_name": "..." }
 *   ]
 * }
 * ```
 */
fun renderProjectJson3(routes: List<ProjectRoute>, out: PrintStream) {
    val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", "devrig")
            put("version", DevrigVersionMetadata.getDevrigVersion())
        })
        putJsonArray("projects") {
            // Same ordering as the MCP steroid_list_projects response (#155): sort by project_name.
            for (route in routes.sortedBy { it.exposedProjectName }) {
                add(buildJsonObject {
                    put("project_name", route.exposedProjectName)
                    put("name", route.originalProjectName)
                    put("path", route.projectPath)
                    put("backend_name", route.exposedBackendName)
                })
            }
        }
    }
    out.println(json.encodeToString(JsonObject.serializer(), payload))
}

private fun backendNoun(count: Int): String = if (count == 1) "backend" else "backends"
