package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_list_projects MCP tool.
 */
class ListProjectsToolSpec(val handler: () -> ListProjectsToolHandler) : McpToolBase() {
    override val name = "steroid_list_projects"
    override val description = "List all open projects in the IDE. Each entry has `project_name` (a unique routing key — pass it to steroid_execute_code and the other project-scoped tools) and `name` (the raw folder name, informational only); they are not equal. Resolve an entry's `backend_name` to the owning IDE's identity (`intellij` = `{name, version, build}`) via the `backends` lookup in the same response."

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = handler().collectListProjectsResponse()
        val json = McpJson.encodeToString(response)

        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

interface ListProjectsToolHandler {
    suspend fun collectListProjectsResponse(): ListProjectsResponse
}

/**
 * MCP-only output of `steroid_list_projects` — never crosses the devrig<->IDE wire. There is no
 * top-level `ide`/`plugin`/`pid` header (#89): the responding server's identity lives in the MCP
 * server info, and per-entry attribution happens via `backend_name` on each project entry,
 * resolvable to the owning IDE's identity through the `backends[]` table of the same response (#155).
 */
@Serializable
data class ListProjectsResponse(
    /**
     * Projects reachable through this connection, sorted by `project_name`. Each entry's `project_name`
     * is the within-IDE-unique routing KEY an agent passes back to the project-scoped tools — opaque
     * (do not parse or rely on its format); never equal to the raw `name`; `name` is the raw folder name
     * and is informational only. On a direct in-IDE connection `backend_name` is this IDE's self-id; on
     * devrig the owning discovered IDE.
     */
    val projects: List<ListedProject>,
    /**
     * Resolution table for this response (#155): exactly the distinct `backend_name` values referenced
     * by `projects[]` — no more, no less — sorted by `backend_name`, with one deliberate exception: on
     * a direct in-IDE connection the single self entry is ALWAYS present, even with zero open projects
     * (a server always describes itself — the identity probe). Identity-only by design: growth belongs
     * to `devrig backend --json` (#151), never here.
     */
    val backends: List<BackendRef> = emptyList(),
)

/**
 * The wire DTO carried over `/projects/stream` (devrig<->IDE). Pristine `{name, path}` — the per-project
 * backend reference lives on the MCP-only [ListedProject], never here.
 */
@Serializable
data class ProjectInfo(
    val name: String,
    val path: String,
)

/**
 * Shared marker locator — `"build <build>, pid <pid>"`, with the `build ` segment omitted when [build] is
 * null or blank. devrig appends its own `", managed"` suffix where applicable.
 */
fun markerLocator(build: String?, pid: Long): String = buildString {
    build?.trim()?.takeIf { it.isNotEmpty() }?.let {
        append("build ").append(it).append(", ")
    }
    append("pid ").append(pid)
}

@Serializable
data class ListedProject(
    /**
     * The within-IDE-unique routing KEY an agent passes back to every project-scoped tool
     * (`steroid_execute_code`, `steroid_input`, `steroid_take_screenshot`, …). Opaque — do not parse or
     * construct it; never equal to [name]. devrig and IDE-direct compute the same key for the same project.
     */
    @SerialName("project_name") val projectName: String,
    /**
     * Raw IntelliJ `Project.name` (the folder name) — INFORMATIONAL ONLY (display / `jq`), NOT a routing
     * key. Kept so existing `jq '.projects[].name'` consumers do not break. Use [projectName] to address a project.
     */
    val name: String,
    val path: String,
    /** Owning backend's backend_name; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)
