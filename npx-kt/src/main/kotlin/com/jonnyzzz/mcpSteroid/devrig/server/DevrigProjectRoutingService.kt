/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.compareBackendVersions
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.server.ListedBackendInfo
import com.jonnyzzz.mcpSteroid.server.base36FixedWidth
import java.io.IOException
import java.nio.file.Path

data class ProjectRoute(
    val route: DiscoveredIde,
    val projectInfo: IdeProjectState,
    val exposedProjectName: String,
    val projectPath: String,
) {
    val originalProjectName: String get() = projectInfo.ideProjectName
    val exposedBackendName: String get() = route.backendName
}

class DevrigProjectRoutingService(
    private val stateProvider: () -> List<IdeMonitorState>,
) {

    //TODO: it should include inventory#backends
    fun routes(): List<ProjectRoute> {
        return stateProvider().flatMap { ide ->
            ide.projects.map { proj ->
                val realHome = canonicalProjectHome(proj.projectPath)
                val projectHash = base36FixedWidth(realHome, ide.ide.backendName)
                ProjectRoute(
                    route = ide.ide,
                    exposedProjectName = "${proj.name}-$projectHash",
                    projectInfo = proj,
                    projectPath = realHome,
                )
            }
        }
    }

    fun routeProject(exposedProjectName: String): ProjectRoute? =
        routes().singleOrNull { it.exposedProjectName == exposedProjectName }

    fun requireProject(exposedProjectName: String): ProjectRoute =
        routeProject(exposedProjectName) ?: throw ProjectRouteNotFoundException(exposedProjectName)

    /**
     * All discovered backends as (backend_name, ide) pairs, for list_projects summaries and error
     * messages. De-duped by backend_name (keep-first + WARN), mirroring `backendRowsWithStableIds`.
     */
    fun discoveredBackends(): List<DiscoveredIde> = stateProvider().map { it.ide }.distinctBy { it.backendName }

    /**
     * The backends exposed on the MCP `steroid_list_projects` / `steroid_list_windows` `backends[]`:
     * exactly the routing-discovered IDEs (the open_project-routable backends), each as a slim
     * [ListedBackendInfo]. Port-only / managed backends are intentionally NOT here — those are a CLI
     * concern (`devrig backend`), kept out of the agent-facing MCP surface.
     */
    fun listedBackends(): List<ListedBackendInfo> = discoveredBackends().map { ide ->
        ListedBackendInfo(
            backendName = ide.backendName,
            displayName = ide.ide.name,
            version = ide.ide.version,
            build = ide.ide.build,
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
                stripProductCode(left.ide.build),
                stripProductCode(right.ide.build),
            )
            if (byBuild != 0) return@Comparator byBuild
            left.pid.compareTo(right.pid)
        }

        private val PRODUCT_CODE_PREFIX = Regex("^[A-Za-z]+-")

        private fun stripProductCode(build: String): String = build.replaceFirst(PRODUCT_CODE_PREFIX, "")

        /**
         * Canonicalizes a project home for routing/hash purposes. `toRealPath()` resolves symlinks but
         * THROWS when the directory no longer exists — and a single vanished project (e.g. a test
         * project deleted while its IDE snapshot is still cached) must not break routing for every
         * other project. Fall back to the lexically-normalized absolute path in that case.
         */
        fun canonicalProjectHome(projectHome: String): String {
            val path = Path.of(projectHome)
            return try {
                path.toRealPath()
            } catch (_: IOException) {
                path.toAbsolutePath().normalize()
            }.toString()
        }

        fun newestOf(ides: List<DiscoveredIde>): DiscoveredIde? {
            return ides.maxWithOrNull(NEWEST_IDE_FIRST)
        }
    }
}

class ProjectRouteNotFoundException(projectName: String) : IllegalArgumentException(
    "project_name '$projectName' is no longer present; call steroid_list_projects to refresh"
)
