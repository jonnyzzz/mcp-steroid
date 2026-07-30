package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.server.BackendRef
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.backendsTable
import com.jonnyzzz.mcpSteroid.server.listed
import com.jonnyzzz.mcpSteroid.server.toIntelliJInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class DevrigListWindowsToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = coroutineScope {
        val routes = routing.routes()

        val routedBackends = routes
            .map { it.route }
            .distinctBy { it.backendName }

        val responses = routedBackends
            .map { state ->
                async { state to bridge.fetchWindows(state) }
            }.awaitAll()

        fun exposedProjectName(ide: DiscoveredIde, rawProjectName: String?): String? = routes.find {
            it.route.backendName == ide.backendName && it.originalProjectName == rawProjectName
        }?.exposedProjectName

        ListWindowsResponse(
            // windows[]/backgroundTasks[] keep their produced order (#155 sorts list_projects only).
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
            // Referenced-only membership (#155): the same routed-backend set the entries above come
            // from, so every entry's backend_name resolves. Zero-project running, startable, and
            // port-only backends own no route — their inventory is `devrig backend --json` (#151).
            backends = backendsTable(routedBackends.map { BackendRef(it.backendName, it.ide.toIntelliJInfo()) }),
        )
    }
}
