/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import java.nio.file.Path

/** Outcome of matching the agent's working directory against the open projects. */
sealed interface ProjectDetection {
    data class Unique(val route: ProjectRoute) : ProjectDetection
    data class Ambiguous(val matches: List<ProjectRoute>) : ProjectDetection
    data object NoMatch : ProjectDetection
    data object NoBackends : ProjectDetection
}

/**
 * Selects the project a working directory belongs to. A route matches when its (canonical) project path
 * equals or is an ancestor of a candidate dir (the agent may run from a subdirectory). Among matches,
 * the deepest project in a single ancestor chain wins; two matches that are not in an ancestor relation
 * are [ProjectDetection.Ambiguous].
 */
fun selectProjectByCwd(routes: List<ProjectRoute>, dirs: List<Path>): ProjectDetection {
    if (routes.isEmpty()) return ProjectDetection.NoBackends

    val canonicalDirs = dirs.map { Path.of(DevrigProjectRoutingService.canonicalProjectHome(it.toString())) }

    val matched = routes
        .filter { route ->
            val projectPath = Path.of(route.projectPath)
            canonicalDirs.any { dir -> dir == projectPath || dir.startsWith(projectPath) }
        }
        .distinctBy { it.projectPath }

    if (matched.isEmpty()) return ProjectDetection.NoMatch

    // A match is a "leaf" when no other match sits strictly beneath it. A nested chain (/repo, /repo/sub)
    // has exactly one leaf (/repo/sub → deepest wins); unrelated matches (/a, /b) have several → ambiguous.
    val leaves = matched.filter { candidate ->
        val candidatePath = Path.of(candidate.projectPath)
        matched.none { other ->
            other !== candidate &&
                Path.of(other.projectPath).let { it != candidatePath && it.startsWith(candidatePath) }
        }
    }

    return if (leaves.size == 1) ProjectDetection.Unique(leaves.single())
    else ProjectDetection.Ambiguous(leaves)
}
