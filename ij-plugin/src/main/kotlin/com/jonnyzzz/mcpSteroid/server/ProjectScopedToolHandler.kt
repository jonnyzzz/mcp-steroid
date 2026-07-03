/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException

/**
 * Shared base for `*ToolHandlerIJ` implementations that resolve a
 * `projectName: String` argument to an open IDE [Project].
 *
 * Centralizes the lock-free lookup and the not-found error message
 * (which lists the currently-open `project_name` routing keys to help the
 * caller correct a typo) so each handler only writes the project-scoped body.
 */
@Service(Service.Level.APP)
class ProjectScopedToolHandler {
    /**
     * Resolve [projectName] against the currently open IDE projects. Throws
     * [ToolCallErrorException] if no project matches — `McpToolRegistry.callTool`
     * catches it and turns it into an MCP error result naming the missing project
     * plus the list of currently-open `project_name` values.
     *
     * Deliberately lock-free (#214): a suspend `readAction {}` here parks behind any
     * pending write action, so one hung read action (e.g. a stuck inspection script)
     * would wedge EVERY steroid tool for the duration. [ProjectManager.getOpenProjects]
     * is documented as safe outside a read action provided each project is checked for
     * [Project.isDisposed] before use; [projectNameFor] only reads plain getters
     * (`name`, `basePath`). The returned project can still be disposed concurrently —
     * project-scoped handlers re-check [Project.isDisposed] at use.
     */
    @Throws(ToolCallErrorException::class)
    fun resolveProject(projectName: String): Project {
        // (project_name, name, project) per open project — match by the OPAQUE project_name first,
        // then by the raw folder name ONLY when unambiguous (exactly one match), never first-match,
        // so two same-named projects (e.g. a checkout and its git worktree) can't silently collapse
        // to the wrong one (#92).
        val triples = ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .map { Triple(projectNameFor(it), it.name, it) }
        val resolved = triples.firstOrNull { it.first == projectName }?.third
            ?: triples.singleOrNull { it.second == projectName }?.third
        // Surface the sorted project_name routing keys (not raw names) — that is the key callers must pass.
        val availableProjectNames = triples.map { it.first }.sorted()
        return resolved ?: throw ToolCallErrorException(
            "Project not found: \"$projectName\". Available project_name values: ${availableProjectNames.joinToString()}"
        )
    }
}
