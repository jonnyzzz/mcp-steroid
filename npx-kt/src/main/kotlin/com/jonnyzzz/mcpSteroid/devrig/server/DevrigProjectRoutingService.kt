/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
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

    fun routes(): List<ProjectRoute> {
        return stateProvider().flatMap { ide ->
            ide.projects.map { proj ->
                val realHome = canonicalProjectHome(proj.projectPath)
                val projectHash = base36FixedWidth(realHome, ide.ide.backendName, proj.ideProjectName)
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
     * Detects which open project the agent's working directory belongs to. See [selectProjectByCwd].
     * Used by devrig to resolve a tool call that omits `project_name` (task #226).
     */
    fun detectProject(dirs: List<Path>): ProjectDetection = selectProjectByCwd(routes(), dirs)

    /**
     * All discovered backends as (backend_name, ide) pairs, for list_projects summaries and error
     * messages. De-duped by backend_name (keep-first + WARN), mirroring `backendRowsWithStableIds`.
     */
    fun discoveredBackends(): List<DiscoveredIde> = stateProvider().map { it.ide }.distinctBy { it.backendName }

    companion object {
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
    }
}

class ProjectRouteNotFoundException(projectName: String) : IllegalArgumentException(
    "project_name '$projectName' is no longer present; call steroid_list_projects to refresh"
)
