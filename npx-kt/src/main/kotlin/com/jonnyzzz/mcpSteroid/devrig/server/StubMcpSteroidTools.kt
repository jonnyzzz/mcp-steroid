/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendRow
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler

class StubMcpSteroidTools(
    val services: DevrigServices,
) : McpSteroidTools() {
    private val bridge = DevrigToolBridgeClient(services.projectRouting, services.mcpHttpClient)
    private val listProjects = DevrigListProjectsToolHandler(services.projectRouting, services.backendInventory)
    private val listWindows = DevrigListWindowsToolHandler(
        states = { services.ideMonitor.states.value.values },
        httpClient = services.commandHttpClient,
        routing = services.projectRouting,
        inventory = services.backendInventory,
    )
    val promptsContext = DevrigPromptsContextHandler(services.projectRouting)
    private val executeCode = DevrigExecuteCodeToolHandler(bridge, services.beacon)
    private val executeFeedback = DevrigExecuteFeedbackToolHandler(bridge, services.beacon)
    private val visionScreenshot = DevrigVisionScreenshotToolHandler(bridge)
    private val visionInput = DevrigVisionInputToolHandler(bridge)
    private val openProject = DevrigOpenProjectToolHandler(bridge)

    override fun <T> handler(type: Class<T>): T {
        val handler = when (type) {
            ListProjectsToolHandler::class.java -> listProjects
            ListWindowsToolHandler::class.java -> listWindows
            PromptsContextHandler::class.java -> promptsContext
            ExecuteCodeToolHandler::class.java -> executeCode
            ExecuteFeedbackToolHandler::class.java -> executeFeedback
            VisionScreenshotToolHandler::class.java -> visionScreenshot
            VisionInputToolHandler::class.java -> visionInput
            OpenProjectToolHandler::class.java -> openProject
            else -> unsupportedHandler(type)
        }
        return type.cast(handler)
    }

    private fun unsupportedHandler(type: Class<*>): Nothing =
        throw UnsupportedOperationException(
            "not yet ready: handler<${type.name}>() is not wired in devrig yet for ${services.clientInfo.client}"
        )
}

class DevrigListProjectsToolHandler(
    private val routing: DevrigProjectRoutingService,
    private val inventory: BackendInventory,
) : ListProjectsToolHandler {
    override suspend fun collectListProjectsResponse(): ListProjectsResponse {
        val routes = routing.routes().values.toList()
        // Each route maps to its owning backend's backend_name so projects[] and backends[] agree.
        // discoveredBackends() de-dupes by backend_name (keep-first + WARN) — the same names the
        // inventory computes for its marker rows (both use backendNameForMarker(pid, build)).
        val backendNameByPid = routing.discoveredBackends().associate { (name, ide) -> ide.pid to name }
        val listedProjects = routes.mapNotNull { route ->
            val backendName = backendNameByPid[route.idePid] ?: return@mapNotNull null
            ListedProject(
                projectName = route.exposedProjectName,
                name = route.originalProjectName,
                path = route.projectPath,
                backendName = backendName,
            )
        }
        // backends[] = ALL inventory rows (markers + port-discovered + managed) through the ONE
        // BackendRow -> BackendInfo mapping shared with the CLI, so the MCP `backends[]` and
        // `devrig backend --json` never diverge. Marker rows own their routes' projects; port/managed
        // rows surface with routable=false and no openProjects so the agent sees the full picture.
        val backends = inventory.collectBackendInfos { backendName, row ->
            when (row) {
                is BackendRow.FromMarker -> listedProjects.filter { it.backendName == backendName }
                is BackendRow.FromPort, is BackendRow.FromManaged -> emptyList()
            }
        }
        return ListProjectsResponse(
            projects = listedProjects,
            backends = backends,
        )
    }
}

class DevrigPromptsContextHandler(
    private val routing: DevrigProjectRoutingService,
) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext {
        val route = routing.requireProject(projectName)
        return promptsContextFromBuild(route.ide.build)
    }

    companion object {
        fun promptsContextFromBuild(build: String): PromptsContext {
            val dash = build.indexOf('-')
            if (dash <= 0 || dash == build.lastIndex) return PromptsContext.Generic
            val productCode = build.substring(0, dash)
            val baseline = build.substring(dash + 1).substringBefore('.').toIntOrNull()
                ?: return PromptsContext.Generic
            return PromptsContext(productCode = productCode, baselineVersion = baseline)
        }
    }
}
