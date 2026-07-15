package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler

class DevrigPromptsContextHandler(
    private val routing: DevrigProjectRoutingService,
) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext {
        return promptsContextFromRoute(routing.requireProject(projectName))
    }

    companion object {
        /** The only place [ProjectRoute.route]`.ide.build` is navigated to derive a [PromptsContext]. */
        fun promptsContextFromRoute(route: ProjectRoute): PromptsContext =
            promptsContextFromBuild(route.route.ide.build)

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
