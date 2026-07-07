package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

abstract class McpSteroidTools {
    /**
     * Registers the tools common to every backend surface (in-IDE plugin and devrig CLI).
     *
     * `steroid_open_project` is intentionally NOT registered here: its spec differs per surface
     * (the in-IDE plugin advertises no `backend_name`, devrig advertises a required `backend_name`
     * routing param). Each caller registers its own `OpenProjectToolSpec(...)` after this call,
     * using the public [handler] accessor to resolve the [OpenProjectToolHandler].
     */
    fun registerAll(server: McpServerCore, projectNameRequired: Boolean = true) {
        val tools = server.toolRegistry

        tools.registerTool(ListProjectsToolSpec { handler<ListProjectsToolHandler>() })
        tools.registerTool(ListWindowsToolSpec { handler<ListWindowsToolHandler>() })
        tools.registerTool(ExecuteCodeToolSpec(projectNameRequired) { handler<ExecuteCodeToolHandler>() })
        tools.registerTool(ExecuteFeedbackToolSpec(projectNameRequired) { handler<ExecuteFeedbackToolHandler>() })
        tools.registerTool(VisionScreenshotToolSpec(projectNameRequired) { handler<VisionScreenshotToolHandler>() })
        tools.registerTool(VisionInputToolSpec(projectNameRequired) { handler<VisionInputToolHandler>() })
        tools.registerTool(FetchResourceToolHandler(projectNameRequired) { handler<PromptsContextHandler>() })
    }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}
