/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

/**
 * A [DevrigProjectResolver] over [routing] whose workspace-roots seam is inert (no roots, cwd `/`).
 * For tests that always resolve an explicit `project_name`, so cwd auto-detection is never consulted.
 */
fun testProjectResolver(routing: DevrigProjectRoutingService): DevrigProjectResolver =
    DevrigProjectResolver(
        routing = routing,
        workspaceRoots = DevrigWorkspaceRoots(rootsProvider = { null }, processCwd = { "/" }),
    )
