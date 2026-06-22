package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler

class DevrigPromptsContextHandler(
    private val routing: DevrigProjectRoutingService,
) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext {
        val route = routing.requireProject(projectName)
        return promptsContextFromBuild(route.route.ide.build)
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
