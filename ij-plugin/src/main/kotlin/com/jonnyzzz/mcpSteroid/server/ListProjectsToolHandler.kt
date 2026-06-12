/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo

/**
 * Direct in-IDE `steroid_list_projects`. R3.6 — the surface self-describes with the SAME shape devrig
 * emits: no top-level `ide`/`plugin`/`pid` header (the responding server's identity lives in the MCP
 * server info), exactly one [BackendInfo] for this IDE (its own R3.3 `backend_name` over its pid;
 * `source=marker`, `routable=true`, one `plugins[]` entry of `kind=mcp-steroid`), and one
 * [ListedProject] per open project where `project_name == name` and `backend_name` is this IDE's
 * self-id. Built via the shared [describeSelfBackend] assembler — also used by
 * [ListWindowsToolHandlerIJ] — so the in-IDE handlers never re-implement the self-describe shape.
 */
class ListProjectsToolHandlerIJ : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val self = describeSelfBackend()
        return ListProjectsResponse(
            projects = self.projects,
            backends = listOf(self.backend),
        )
    }
}

/**
 * The in-IDE self-describe: this IDE's R3.3 `backend_name`, its open projects as [ListedProject]s
 * bound to that name, and the single self [BackendInfo] (built via the shared [markerBackendInfo]
 * assembler so the in-IDE and devrig sides never re-implement the marker shape). Shared by
 * [ListProjectsToolHandlerIJ] and [ListWindowsToolHandlerIJ].
 */
class SelfBackendDescription(
    /** This IDE's own `backend_name` ([backendNameForMarker] over its pid + build). */
    val backendName: String,
    /** Open projects, each with `project_name == name` and `backend_name == `[backendName]. */
    val projects: List<ListedProject>,
    /** The single self [BackendInfo] (`source=marker`, `routable=true`, `openProjects=`[projects]). */
    val backend: BackendInfo,
)

suspend fun describeSelfBackend(): SelfBackendDescription {
    val ide = IdeInfo.ofApplication()
    val plugin = PluginInfo.ofCurrentPlugin()
    val pid = ProcessHandle.current().pid()
    val selfBackendName = backendNameForMarker(pid = pid, build = ide.build)

    val openProjects = readAction {
        ProjectManager.getInstance().openProjects.toList()
    }

    val listedProjects = openProjects.map { project ->
        val name = project.name
        ListedProject(
            projectName = name,
            name = name,
            path = project.basePath ?: "",
            backendName = selfBackendName,
        )
    }

    val selfBackend = markerBackendInfo(
        backendName = selfBackendName,
        pid = pid,
        ide = ide,
        plugins = mcpSteroidPlugins(plugin),
        openProjects = listedProjects,
    )

    return SelfBackendDescription(
        backendName = selfBackendName,
        projects = listedProjects,
        backend = selfBackend,
    )
}
