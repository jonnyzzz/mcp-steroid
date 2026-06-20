/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo

/**
 * Direct in-IDE `steroid_list_projects`. R3.6 — the surface self-describes with the SAME shape devrig
 * emits: no top-level `ide`/`plugin`/`pid` header (the responding server's identity lives in the MCP
 * server info), exactly one [BackendInfo] for this IDE (its own R3.3 `backend_name` over its pid;
 * `source=marker`, `routable=true`, one `plugins[]` entry of `kind=mcp-steroid`), and one
 * [ListedProject] per open project whose `project_name` is the within-IDE-unique routing key
 * `<name>-<hash>` (from [OpenProjectsService]/[ProjectNameService]; never equal to `name`, which stays the
 * raw folder name and is informational only) and `backend_name` is this IDE's self-id. Built via the shared [describeSelfBackend] assembler — also used by
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
    /**
     * Open projects, each with the within-IDE-unique routing key `project_name` (`<name>-<hash>`, never
     * equal to the raw `name`) and `backend_name == `[backendName].
     */
    val projects: List<ListedProject>,
    /** The single self [BackendInfo] (`source=marker`, `routable=true`, `openProjects=`[projects]). */
    val backend: BackendInfo,
)

suspend fun describeSelfBackend(): SelfBackendDescription {
    val ide = IdeInfo.ofApplication()
    val plugin = PluginInfo.ofCurrentPlugin()
    val pid = ProcessHandle.current().pid()
    val selfBackendName = backendNameForMarker(pid = pid, build = ide.build)

    val listedProjects = service<OpenProjectsService>().listOpenProjects().map { e ->
        ListedProject(
            // project_name = the within-IDE-unique name (#92), so two same-named projects (e.g. a
            // checkout + its worktree) are individually addressable; `name` stays the raw folder name.
            projectName = e.projectName,
            name = e.rawName,
            path = e.path,
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
