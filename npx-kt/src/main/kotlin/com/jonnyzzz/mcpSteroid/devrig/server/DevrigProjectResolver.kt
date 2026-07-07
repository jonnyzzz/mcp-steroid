/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException

/**
 * Resolves the [ProjectRoute] for a project-scoped devrig tool call. An explicit, non-blank
 * `project_name` always wins (delegates to [DevrigProjectRoutingService.requireProject]); otherwise the
 * project is auto-detected from the agent's working directory. Detection failures surface as
 * [ToolCallErrorException] with an actionable, case-specific message (task #226).
 */
class DevrigProjectResolver(
    private val routing: DevrigProjectRoutingService,
    private val workspaceRoots: DevrigWorkspaceRoots,
) {
    suspend fun resolve(projectName: String?): ProjectRoute {
        if (!projectName.isNullOrBlank()) return routing.requireProject(projectName)

        val dirs = workspaceRoots.candidateDirs()
        val primaryDir = dirs.firstOrNull()?.toString() ?: "(unknown)"

        return when (val detection = routing.detectProject(dirs)) {
            is ProjectDetection.Unique -> detection.route
            is ProjectDetection.Ambiguous -> throw ToolCallErrorException(
                "Multiple open projects match your working directory ($primaryDir): " +
                    detection.matches.joinToString(", ") { it.exposedProjectName } +
                    ". Pass project_name to choose one — see steroid_list_projects."
            )
            ProjectDetection.NoMatch -> throw ToolCallErrorException(
                "No open project matches your working directory ($primaryDir). " +
                    "Open it in IntelliJ, or pass project_name explicitly — see steroid_list_projects."
            )
            ProjectDetection.NoBackends -> throw ToolCallErrorException(
                "No IntelliJ IDE with the MCP Steroid plugin is running. " +
                    "Open your project in IntelliJ, then retry."
            )
        }
    }
}
