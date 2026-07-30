package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.backendsTable
import com.jonnyzzz.mcpSteroid.server.toIntelliJInfo

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes()
        val listedProjects = routes.map { route ->
            ListedProject(
                // Opaque routing key (the disambiguated <name>-<hash>). `name` is the human-readable
                // folder name — NOT originalProjectName, which is the IDE's project_name hash and would
                // make `name` unreadable and break consumers that filter by the real folder name.
                projectName = route.exposedProjectName,
                name = route.projectInfo.name,
                path = route.projectPath,
                backendName = route.exposedBackendName,
            )
        }

        return ListProjectsResponse(
            projects = listedProjects.sortedBy { it.projectName },
            // Referenced-only membership (#155): derived from the same routing snapshot the entries
            // come from, so every entry's backend_name resolves and no unreferenced backend appears.
            // Zero-project running, startable, and port-only backends own no route — their inventory
            // lives in `devrig backend --json` (#151) and the open_project candidate listing.
            backends = backendsTable(routes.map { BackendRef(it.route.backendName, it.route.ide.toIntelliJInfo()) }),
        )
    }
}
