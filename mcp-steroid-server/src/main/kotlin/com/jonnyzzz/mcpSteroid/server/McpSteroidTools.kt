package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore

abstract class McpSteroidTools {
    /**
     * Registers the tools common to every backend surface.
     *
     * `steroid_open_project` is intentionally NOT registered here: its spec differs per surface
     * (the in-IDE plugin advertises no `backend_name`, devrig advertises a required `backend_name`
     * routing param). Each caller registers its own `OpenProjectToolSpec(...)` after this call,
     * using the public [handler] accessor to resolve the [OpenProjectToolHandler].
     */
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry
        commonToolSpecs().forEach { tools.registerTool(it) }
    }

    /**
     * Tool specs common to the in-IDE and devrig surfaces, in registration order. `steroid_open_project`
     * is excluded because its routing parameters differ between the two surfaces.
     */
    fun commonToolSpecs(): List<CliToolSpec> = listOf(
        ListProjectsToolSpec { handler<ListProjectsToolHandler>() },
        ListWindowsToolSpec { handler<ListWindowsToolHandler>() },
        ExecuteCodeToolSpec { handler<ExecuteCodeToolHandler>() },
        ExecuteFeedbackToolSpec { handler<ExecuteFeedbackToolHandler>() },
        VisionScreenshotToolSpec { handler<VisionScreenshotToolHandler>() },
        VisionInputToolSpec { handler<VisionInputToolHandler>() },
        FetchResourceToolHandler { handler<PromptsContextHandler>() },
    )

    /**
     * Tool specs exposed by devrig. Its `steroid_open_project` includes `backend_name` so calls can be
     * routed to one of the discovered IDE backends.
     */
    fun devrigToolSpecs(): List<CliToolSpec> =
        commonToolSpecs() + OpenProjectToolSpec(includeBackendName = true) { handler<OpenProjectToolHandler>() }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}
