/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.IdeInfo

/**
 * Stable id for an open [project] — the value returned as `project_name` by list_projects/list_windows.
 * Shape: `<readable project name>-<hash8>`, mirroring devrig's `exposedProjectName`. The hash (via the
 * shared [base36FixedWidth] util, same family as `backend_name`) covers the project's **base directory**
 * and display name — the base dir is essential so two same-named projects in different folders do not
 * collide. Computed at the call sites that hold the [Project] (the producers and
 * [ProjectScopedToolHandler.resolveProject]) so `/projects` and `/windows` always emit the same id
 * for the same project.
 */
fun projectNameFor(project: Project): String =
    "${project.name}-${base36FixedWidth("project", project.basePath, project.name)}"

/**
 * Direct in-IDE `steroid_list_projects`. No top-level `ide`/`plugin`/`pid` header (the responding
 * server's identity lives in the MCP server info). Each [ListedProject] carries a stable base36
 * hash as `project_name` (derived from the real name) and `backend_name` pointing at this IDE's self-id.
 */
class ListProjectsToolHandlerIJ : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val self = describeSelfBackend()
        return ListProjectsResponse(
            projects = self.projects,
        )
    }
}

class SelfBackendDescription(
    /** This IDE's own `backend_name` ([backendNameForMarker] over its pid + build). */
    val backendName: String,
    /** Open projects, each with a hashed `project_name` ([projectNameFor]) and `backend_name == `[backendName]. */
    val projects: List<ListedProject>,
)

suspend fun describeSelfBackend(): SelfBackendDescription {
    val ide = IdeInfo.ofApplication()
    val pid = ProcessHandle.current().pid()
    val selfBackendName = backendNameForMarker(pid = pid, build = ide.build)

    val openProjects = run { // #214: no read action — must not park behind a pending write (wedges every tool)
        ProjectManager.getInstance().openProjects.toList()
    }

    val listedProjects = openProjects.map { project ->
        ListedProject(
            projectName = projectNameFor(project),
            name = project.name,
            path = project.basePath ?: "",
            backendName = selfBackendName,
        )
    }

    return SelfBackendDescription(
        backendName = selfBackendName,
        projects = listedProjects,
    )
}
