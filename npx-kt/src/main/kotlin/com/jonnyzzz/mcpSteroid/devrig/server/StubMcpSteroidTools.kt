/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
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
    private val bridge by lazy {
        DevrigToolBridgeClient(httpClient = services.mcpHttpClient)
    }

    private val listProjects by lazy {
        DevrigListProjectsToolHandler(
            routing = services.projectRouting,
        )
    }

    private val listWindows by lazy {
        DevrigListWindowsToolHandler(
            bridge = bridge,
            routing = services.projectRouting,
        )
    }

    private val promptsContext by lazy {
        DevrigPromptsContextHandler(
            routing = services.projectRouting
        )
    }

    private val executeCode by lazy {
        DevrigExecuteCodeToolHandler(
            bridge = bridge,
            routing = services.projectRouting,
            beacon = services.beacon
        )
    }

    private val executeFeedback by lazy {
        DevrigExecuteFeedbackToolHandler(
            bridge = bridge,
            routing = services.projectRouting,
            beacon = services.beacon
        )
    }

    private val visionScreenshot by lazy {
        DevrigVisionScreenshotToolHandler(
            bridge = bridge,
            routing = services.projectRouting
        )
    }

    private val visionInput by lazy {
        DevrigVisionInputToolHandler(
            bridge = bridge,
            routing = services.projectRouting,
        )
    }

    private val openProject by lazy {
        DevrigOpenProjectToolHandler(
            bridge = bridge,
            routing = services.projectRouting,
        )
    }

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

            else -> throw UnsupportedOperationException(
                "not yet ready: handler<${type.name}>() is not wired in devrig yet"
            )
        }
        return type.cast(handler)
    }
}
