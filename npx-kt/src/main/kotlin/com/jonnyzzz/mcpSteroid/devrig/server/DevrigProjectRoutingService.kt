/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.devrig.compareBackendVersions
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.server.canonicalProjectHome
import com.jonnyzzz.mcpSteroid.server.projectHash
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant

class DevrigProjectRoutingService(
    private val stateProvider: () -> Map<Long, IdeMonitorState>,
    /**
     * Pids of IDEs started and owned by devrig as managed backends (`devrig backend start`).
     * Used by [openProjectTargetIde] to land open_project in the agent's own backend rather than
     * an unrelated user-launched IDE. Defaults to none so plain discovery keeps its old behavior.
     */
    private val managedRunningPids: () -> Set<Long>,
) {
    /** No managed-backend awareness — open_project falls back to the newest discovered IDE. */
    constructor(stateProvider: () -> Map<Long, IdeMonitorState>) : this(stateProvider, { emptySet() })

    private val log = LoggerFactory.getLogger(DevrigProjectRoutingService::class.java)

    fun routes(): Map<String, ProjectRoute> {
        val routes = linkedMapOf<String, ProjectRoute>()
        for ((pid, state) in stateProvider()) {
            for (project in state.lastSnapshot) {
                val route = projectRoute(pid, state.ide, project)
                routes[route.exposedProjectName] = route
            }
        }
        return routes
    }

    fun routeProject(exposedProjectName: String): ProjectRoute? =
        routes()[exposedProjectName]

    fun requireProject(exposedProjectName: String): ProjectRoute =
        routeProject(exposedProjectName)
            ?: throw ProjectRouteNotFoundException(exposedProjectName)

    fun singleRouteOrNull(): ProjectRoute? {
        val routes = routes().values.toList()
        return if (routes.size == 1) routes.single() else null
    }

    /**
     * Picks the IDE that should receive `steroid_open_project`.
     *
     * Selection is two-tier:
     *  1. If any devrig-managed backend (`devrig backend start`) is currently running and discovered,
     *     prefer it — that is the agent's own sandbox, and open_project must land there even when the
     *     user has a newer IDE open. This is what aligns open_project with `devrig backend` selection:
     *     "download/start the IDE for this project, then open the project in it" works deterministically.
     *  2. Otherwise fall back to the newest discovered IDE.
     *
     * Within each tier the newest IDE wins (see [newestIdeOrNull]). Returns null only when no IDE is
     * discovered at all.
     */
    fun openProjectTargetIde(): DiscoveredIde? {
        val ides = discoveredIdes()
        if (ides.isEmpty()) return null
        val managedPids = managedRunningPids()
        val managed = ides.filter { it.pid in managedPids }
        return newestOf(managed.ifEmpty { ides })
    }

    /**
     * Picks the newest discovered IDE: highest IDE build, ties broken by the most recently started IDE
     * (marker `createdAt`), then by pid for full determinism. Every discovered IDE already runs the MCP
     * Steroid plugin (found via the plugin's pid markers). Returns null when no IDE is discovered.
     */
    fun newestIdeOrNull(): DiscoveredIde? = newestOf(discoveredIdes())

    /**
     * The agent-facing backend id for a discovered IDE — the value an agent passes as `backend_name`
     * to steroid_open_project. Computed by the R3.3 uniform hash scheme (`<productCodeLower>-<hash8>`,
     * hash over `"pid:<pid>"`), the same value surfaced by `steroid_list_projects` / `devrig backend
     * --json`, so the id surfaced there is the one accepted here.
     */
    fun backendNameForIde(ide: DiscoveredIde): String =
        backendNameForMarker(pid = ide.pid, build = ide.marker.ide.build)

    /**
     * Resolves a `backend_name` (as listed by steroid_list_projects / `devrig backend --json`) to its
     * discovered IDE, or null when no currently-discovered routable backend matches. Only marker IDEs
     * are routable; `port:`/`managed:` ids never match here because their names are computed from a
     * different source key and no marker recomputes to them.
     */
    fun resolveBackend(backendName: String): DiscoveredIde? {
        val wanted = backendName.trim()
        if (wanted.isEmpty()) return null
        return discoveredBackends().firstOrNull { it.first == wanted }?.second
    }

    /**
     * All discovered backends as (backend_name, ide) pairs, for list_projects summaries and error
     * messages. De-duped by backend_name (keep-first + WARN), mirroring `backendRowsWithStableIds`.
     */
    fun discoveredBackends(): List<Pair<String, DiscoveredIde>> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Pair<String, DiscoveredIde>>()
        val duplicates = LinkedHashSet<String>()
        for (ide in discoveredIdes()) {
            val name = backendNameForIde(ide)
            if (seen.add(name)) {
                out += name to ide
            } else {
                duplicates += name
            }
        }
        if (duplicates.isNotEmpty()) {
            log.warn(
                "Duplicate backend_name in discovered backends: {}. Keeping the first IDE for each.",
                duplicates.joinToString(", "),
            )
        }
        return out
    }

    private fun discoveredIdes(): List<DiscoveredIde> =
        stateProvider().values.map { it.ide }.distinctBy { it.pid }

    private fun newestOf(ides: List<DiscoveredIde>): DiscoveredIde? = ides.maxWithOrNull(NEWEST_IDE_FIRST)

    private fun projectRoute(idePid: Long, ide: DiscoveredIde, project: ProjectInfo): ProjectRoute {
        val realHome = canonicalProjectHome(project.path)
        val projectHash = projectHash(realHome, idePid)
        return ProjectRoute(
            idePid = idePid,
            bridgeBaseUrl = ide.rpcBaseUrl,
            headers = ide.bridgeHeaders,
            originalProjectName = project.name,
            // devrig computes its own within-IDE-unique name directly (== uniqueProjectName(name, path, pid));
            // the IDE re-derives the same value by the shared scheme, so devrig forwards exactly this (#92).
            exposedProjectName = "${project.name}-$projectHash",
            projectPath = project.path,
            realProjectHome = realHome,
            projectHash = projectHash,
            ide = ide.marker.ide,
            plugin = ide.marker.plugin,
        )
    }

    companion object {
        /**
         * Orders discovered IDEs so the "newest" sorts last (greatest): highest IDE build first,
         * ties broken by the most recently started IDE, then by pid. Use with [maxWithOrNull].
         * IDE builds carry a product-code prefix (`IU-261.…`); it is stripped so the numeric build
         * components drive the comparison rather than the product letters.
         */
        private val NEWEST_IDE_FIRST: Comparator<DiscoveredIde> = Comparator { left, right ->
            val byBuild = compareBackendVersions(
                stripProductCode(left.marker.ide.build),
                stripProductCode(right.marker.ide.build),
            )
            if (byBuild != 0) return@Comparator byBuild
            val byCreatedAt = compareValuesBy(left, right) { parseCreatedAtOrMin(it.marker.createdAt) }
            if (byCreatedAt != 0) return@Comparator byCreatedAt
            left.pid.compareTo(right.pid)
        }

        private val PRODUCT_CODE_PREFIX = Regex("^[A-Za-z]+-")

        private fun stripProductCode(build: String): String = build.replaceFirst(PRODUCT_CODE_PREFIX, "")

        private fun parseCreatedAtOrMin(value: String): Instant =
            try {
                Instant.parse(value)
            } catch (e: Exception) {
                Instant.MIN
            }

        // canonicalProjectHome + projectHash moved to mcp-steroid-server (shared with the IDE plugin so
        // both compute the same `<name>-<hash>` — see com.jonnyzzz.mcpSteroid.server.ProjectNaming).
    }
}

data class ProjectRoute(
    val idePid: Long,
    val bridgeBaseUrl: String,
    val headers: Map<String, String>,
    /** Raw IntelliJ `Project.name` (the folder name). */
    val originalProjectName: String,
    /** devrig's globally-unique agent-facing `project_name` (cross-IDE-unique; may be disambiguated). */
    val exposedProjectName: String,
    val projectPath: String,
    val realProjectHome: Path,
    val projectHash: String,
    val ide: IdeInfo,
    val plugin: PluginInfo,
)

class ProjectRouteNotFoundException(projectName: String) : IllegalArgumentException(
    "project_name '$projectName' is no longer present; call steroid_list_projects to refresh"
)
