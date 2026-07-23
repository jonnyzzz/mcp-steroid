package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
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
    fun registerAll(server: McpServerCore) {
        val tools = server.toolRegistry
        commonToolSpecs(this).forEach { tools.registerTool(it) }
    }

    inline fun <reified T : Any> handler(): T = handler(T::class.java)
    abstract fun <T> handler(type: Class<T>): T
}

/**
 * The ONE canonical list of devrig-surface tool specs. Every devrig surface — the
 * `devrig mcp` stdio server and the generated `devrig --help` "MCP tools as CLI" block — builds from
 * this single list, so they can never advertise a different set of tools. Returns [CliToolSpec] (not
 * `McpTool`) directly, so callers read `.cli`/`.schema` without a cast and a newly added tool can never
 * be silently dropped by a `filterIsInstance` narrowing.
 *
 * The devrig `steroid_open_project` advertises the required `backend_name` routing param
 * (`includeBackendName = true`) because devrig routes to one of several discovered IDEs — the single
 * intentional divergence from the in-IDE surface (see `DevrigDescriptorParityTest`). Registration order
 * is preserved by callers that register each spec as an [com.jonnyzzz.mcpSteroid.mcp.McpTool].
 */
/**
 * The tool specs common to every backend surface (in-IDE plugin and devrig CLI), in registration order.
 * The single hand-written list — both [McpSteroidTools.registerAll] (in-IDE) and [devrigToolSpecs]
 * (devrig) build from it, so adding a tool here surfaces on every surface and cannot be forgotten on one.
 * `steroid_open_project` is excluded: its spec differs per surface (see the two callers).
 */
fun commonToolSpecs(tools: McpSteroidTools): List<CliToolSpec> = with(tools) {
    listOf(
        ListProjectsToolSpec { handler<ListProjectsToolHandler>() },
        ListWindowsToolSpec { handler<ListWindowsToolHandler>() },
        ExecuteCodeToolSpec { handler<ExecuteCodeToolHandler>() },
        ExecuteFeedbackToolSpec { handler<ExecuteFeedbackToolHandler>() },
        VisionScreenshotToolSpec { handler<VisionScreenshotToolHandler>() },
        VisionInputToolSpec { handler<VisionInputToolHandler>() },
        FetchResourceToolHandler { handler<PromptsContextHandler>() },
    )
}

fun devrigToolSpecs(tools: McpSteroidTools): List<CliToolSpec> =
    commonToolSpecs(tools) + OpenProjectToolSpec(includeBackendName = true) { tools.handler<OpenProjectToolHandler>() }
