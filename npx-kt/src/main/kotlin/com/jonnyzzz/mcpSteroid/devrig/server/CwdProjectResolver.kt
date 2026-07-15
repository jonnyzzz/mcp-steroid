/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import java.nio.file.Path

/**
 * Result of resolving the current working directory against the known [ProjectRoute]s.
 */
sealed interface CwdProjectMatch {
    /** Exactly one route contains the cwd, and it is the most specific (deepest) such route. */
    data class One(val route: ProjectRoute) : CwdProjectMatch

    /** No known route's [ProjectRoute.projectPath] contains the cwd. */
    data object None : CwdProjectMatch

    /** Two or more routes contain the cwd and tie at the same (deepest) path depth. */
    data class Ambiguous(val candidates: List<ProjectRoute>) : CwdProjectMatch
}

/**
 * Picks the [ProjectRoute] whose [ProjectRoute.projectPath] is the longest path-segment-boundary
 * prefix of [cwd]. Matching is on whole name components (via [Path.startsWith]), not raw string
 * prefixes, so a sibling directory like `/home/u/projbeta` never matches a route at `/home/u/proj`.
 *
 * Pure function: [cwd] is a parameter, never read from the environment here.
 */
fun resolveProjectFromCwd(cwd: Path, routes: List<ProjectRoute>): CwdProjectMatch {
    val absoluteCwd = cwd.toAbsolutePath().normalize()
    val containing = routes.filter { route ->
        absoluteCwd.startsWith(Path.of(route.projectPath).toAbsolutePath().normalize())
    }
    if (containing.isEmpty()) return CwdProjectMatch.None

    val maxDepth = containing.maxOf { Path.of(it.projectPath).toAbsolutePath().normalize().nameCount }
    val deepest = containing.filter { Path.of(it.projectPath).toAbsolutePath().normalize().nameCount == maxDepth }

    return if (deepest.size == 1) CwdProjectMatch.One(deepest.single()) else CwdProjectMatch.Ambiguous(deepest)
}
