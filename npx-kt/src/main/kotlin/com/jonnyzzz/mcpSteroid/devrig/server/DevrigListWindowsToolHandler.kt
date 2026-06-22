package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.listed
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DevrigListWindowsToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = coroutineScope {
        val routes = routing.routes()

        val responses = routes
            .map { it.route }
            .distinctBy { it.backendName }
            .map { state ->
                async { state to bridge.fetchWindows(state) }
            }.awaitAll()

        fun exposedProjectName(ide: DiscoveredIde, rawProjectName: String?): String? = routes.find {
            it.route.backendName == ide.backendName && it.originalProjectName == rawProjectName
        }?.exposedProjectName

        ListWindowsResponse(
            windows = responses.flatMap { (state, response) ->
                response.windows.map { window ->
                    window.listed(
                        exposedProjectName(state, window.projectName),
                        state.backendName
                    )
                }
            },
            backgroundTasks = responses.flatMap { (state, response) ->
                response.backgroundTasks.map { task ->
                    task.listed(
                        exposedProjectName(state, task.projectName),
                        state.backendName
                    )
                }
            },
            // backends[] = exactly the routing-discovered backends, same source as list_projects. Port-only
            // and managed backends are a CLI concern (`devrig backend`), kept off this MCP surface.
            backends = routing.listedBackends(),
        )
    }
}
