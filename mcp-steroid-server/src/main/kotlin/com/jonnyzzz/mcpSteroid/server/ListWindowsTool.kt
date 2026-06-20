package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Handler for the steroid_list_windows MCP tool.
 */
class ListWindowsToolSpec(val handler: () -> ListWindowsToolHandler) : McpToolBase() {
    override val name = "steroid_list_windows"
    override val description = "List open IDE windows and their background tasks, with per-window readiness (modal/indexing/initialized) and a `window_id` for screenshot/input targeting in multi-window setups. Each window and background-task entry references its project by `project_name` — the single routing key for the project-scoped tools; look up that project's human-readable `name` and `path` via steroid_list_projects by the key (they are not duplicated here). `project_name` is null for windows not tied to a project."

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val response = handler().collectListWindowsResponse()
        val json = McpJson.encodeToString(response)
        return ToolCallResult(
            content = listOf(ContentItem.Text(text = json))
        )
    }
}

interface ListWindowsToolHandler {
    suspend fun collectListWindowsResponse(): ListWindowsResponse
}

/**
 * MCP-only output of `steroid_list_windows` — never crosses the devrig<->IDE wire. There is no
 * top-level `ide`/`plugin`/`pid` header: the responding server's identity lives in the MCP server
 * info, and per-entry attribution happens via `backend_name` against [backends].
 */
@Serializable
data class ListWindowsResponse(
    val windows: List<ListedWindow>,
    val backgroundTasks: List<ListedBackgroundTask>,
    /**
     * Backends reachable through this connection. On a direct in-IDE connection exactly one entry (this
     * IDE); on devrig one entry per discovered backend. Each window/background-task entry references its
     * owning backend via `backend_name`.
     */
    val backends: List<BackendInfo> = emptyList(),
)

/**
 * MCP-only window entry: all [WindowInfo] wire fields verbatim plus the owning backend's
 * `backend_name`. Never serialized onto the devrig<->IDE wire — the wire stays [WindowInfo].
 */
@Serializable
data class ListedWindow(
    /**
     * The window's project routing KEY — the opaque, within-IDE-unique id you pass to the project-scoped
     * tools (`steroid_execute_code`, `steroid_take_screenshot`, `steroid_input`, …). The SAME `project_name`
     * `steroid_list_projects` reports; look up the project's `name`/`path` there by this key. Null for
     * windows not tied to a project. Treat it as opaque.
     */
    @SerialName("project_name") val projectName: String?,
    val title: String?,
    val isActive: Boolean,
    val isVisible: Boolean,
    val bounds: WindowBounds?,
    val windowId: String,
    /** True if a modal dialog is currently showing in the IDE */
    val modalDialogShowing: Boolean = false,
    /** True if the project is currently indexing (dumb mode) */
    val indexingInProgress: Boolean? = null,
    /** True if the project has been fully initialized */
    val projectInitialized: Boolean? = null,
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)

/**
 * Maps the wire [WindowInfo] to the MCP-only [ListedWindow], binding it to [backendName] and the
 * resolved [projectKey] (the within-IDE-unique `project_name` for this window's project, or null). The
 * project's raw name/path are intentionally NOT copied — they are looked up via `steroid_list_projects`
 * by [projectKey], keeping `project_name` the single project reference across the MCP surface (#92).
 */
fun WindowInfo.listed(backendName: String?, projectKey: String?): ListedWindow = ListedWindow(
    projectName = projectKey,
    title = title,
    isActive = isActive,
    isVisible = isVisible,
    bounds = bounds,
    windowId = windowId,
    modalDialogShowing = modalDialogShowing,
    indexingInProgress = indexingInProgress,
    projectInitialized = projectInitialized,
    backendName = backendName,
)

/**
 * MCP-only background-task entry: all [ProgressTaskInfo] wire fields verbatim plus the owning
 * backend's `backend_name`. Never serialized onto the devrig<->IDE wire — the wire stays
 * [ProgressTaskInfo].
 */
@Serializable
data class ListedBackgroundTask(
    /** Task title (e.g., "Indexing", "Building") */
    val title: String,
    /** Current status text */
    val text: String,
    /** Secondary status text */
    val text2: String,
    /** Progress fraction (0.0 to 1.0), null if indeterminate */
    val fraction: Double?,
    /** True if progress is indeterminate (no percentage) */
    val isIndeterminate: Boolean,
    /** True if the task can be canceled */
    val isCancellable: Boolean,
    /**
     * The routing KEY of the project this task belongs to — the same opaque id `steroid_list_projects`
     * reports as `project_name` (look up the project's `name`/`path` there). Null if the task isn't tied
     * to a known open project.
     */
    @SerialName("project_name") val projectName: String?,
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)

/**
 * Maps the wire [ProgressTaskInfo] to the MCP-only [ListedBackgroundTask], binding it to [backendName]
 * and the resolved [projectKey] (the within-IDE-unique `project_name`, or null). The project's raw
 * name/path are not copied — look them up via `steroid_list_projects` by [projectKey] (#92).
 */
fun ProgressTaskInfo.listed(backendName: String?, projectKey: String?): ListedBackgroundTask = ListedBackgroundTask(
    title = title,
    text = text,
    text2 = text2,
    fraction = fraction,
    isIndeterminate = isIndeterminate,
    isCancellable = isCancellable,
    projectName = projectKey,
    backendName = backendName,
)

/**
 * The wire DTO carried inside [NpxBridgeWindowsResponse] (devrig<->IDE). The raw `projectName` (folder
 * name) + `projectPath` are the v1 fields. `backend_name` is an ADDITIVE OPTIONAL field (#92): a new IDE
 * populates it, an older one omits it, both decode — INFORMATIONAL/symmetry only. The unique routing key
 * is NOT carried here (no consumer): devrig recomputes it and the IDE-direct handler derives it from the
 * open-project list.
 */
@Serializable
data class WindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val title: String?,
    val isActive: Boolean,
    val isVisible: Boolean,
    val bounds: WindowBounds?,
    val windowId: String,
    /** True if a modal dialog is currently showing in the IDE */
    val modalDialogShowing: Boolean = false,
    /** True if the project is currently indexing (dumb mode) */
    val indexingInProgress: Boolean? = null,
    /** True if the project has been fully initialized */
    val projectInitialized: Boolean? = null,
    /** This IDE's self `backend_name` (#92) — additive, optional, informational (devrig recomputes). */
    @SerialName("backend_name") val backendName: String? = null,
)

@Serializable
data class WindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Information about a background task/progress indicator. Wire DTO carried inside
 * [NpxBridgeWindowsResponse] (devrig<->IDE). The raw `projectName` is the v1 field; `projectPath` and
 * `backend_name` are ADDITIVE OPTIONAL fields (#92, populated by new IDEs, omitted by old ones, both
 * decode) — `projectPath` lets devrig recompute the routing key by path, `backend_name` is informational.
 */
@Serializable
data class ProgressTaskInfo(
    /** Task title (e.g., "Indexing", "Building") */
    val title: String,
    /** Current status text */
    val text: String,
    /** Secondary status text */
    val text2: String,
    /** Progress fraction (0.0 to 1.0), null if indeterminate */
    val fraction: Double?,
    /** True if progress is indeterminate (no percentage) */
    val isIndeterminate: Boolean,
    /** True if the task can be canceled */
    val isCancellable: Boolean,
    /** Raw `Project.name` this task belongs to (if known). */
    val projectName: String?,
    /**
     * Base path of the project this task belongs to (if known). Additive optional field (#92): lets devrig
     * recompute the within-IDE-unique routing key by path, the same way windows do. Older peers that omit
     * it still decode.
     */
    val projectPath: String? = null,
    /** This IDE's self `backend_name` (#92) — additive, optional, informational (devrig recomputes). */
    @SerialName("backend_name") val backendName: String? = null,
)
