/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException

/** One open project plus its within-IDE-unique [projectName] (see [ProjectNameService]). */
data class OpenProjectEntry(
    val project: Project,
    /** Within-IDE-unique name an agent passes back as `project_name`. */
    val projectName: String,
    /** Raw IntelliJ `Project.name`. */
    val rawName: String,
    val path: String,
)

/**
 * Application-level owner of the two project-addressing operations (#92): **list** the open projects and
 * **find** the one a `project_name` refers to. Both go through the per-project [ProjectNameService], so
 * every name is the within-IDE-unique `<name>-<hash>` and is **recomputed each call — never cached**.
 * This is the single place that maps `project_name` ⇄ [Project]; tool handlers must use it rather than
 * matching `Project.name` directly (which is ambiguous when two projects share a name).
 */
@Service(Service.Level.APP)
class OpenProjectsService {
    /** All open projects with their unique `project_name`, raw name, and base path. */
    suspend fun listOpenProjects(): List<OpenProjectEntry> {
        val projectManager = ProjectManager.getInstance()
        return readAction { projectManager.openProjects.map { it.toEntry() } }
    }

    /**
     * Resolve [projectName] to its open [Project]. Throws [ToolCallErrorException] — `McpToolRegistry.callTool`
     * turns it into an MCP error naming the missing project and the currently-open unique names.
     *
     * Resolution order:
     *  1. The within-IDE-unique `project_name` (`<name>-<hash>`) — the primary route, the only key that
     *     disambiguates two same-named projects (#92).
     *  2. Backward/forward-compat fallback: the raw IntelliJ `Project.name`, accepted ONLY when it
     *     identifies exactly one open project ([singleOrNull], never first-match). This lets an older devrig
     *     (or any client) that forwards the raw name keep working for unique project names, WITHOUT
     *     reopening the #92 same-name ambiguity — two projects sharing a raw name still require the unique key.
     */
    @Throws(ToolCallErrorException::class)
    suspend fun resolveProject(projectName: String): Project {
        val projectManager = ProjectManager.getInstance()
        val (project, availableNames) = readAction {
            val entries = projectManager.openProjects.map { it.toEntry() }
            val resolved = entries.firstOrNull { it.projectName == projectName }
                ?: entries.singleOrNull { it.rawName == projectName }
            resolved?.project to entries.map { it.projectName }
        }
        return project ?: throw ToolCallErrorException(
            "Project not found: \"$projectName\". Available projects: $availableNames"
        )
    }

    private fun Project.toEntry(): OpenProjectEntry =
        OpenProjectEntry(this, service<ProjectNameService>().projectName, name, basePath ?: "")
}
