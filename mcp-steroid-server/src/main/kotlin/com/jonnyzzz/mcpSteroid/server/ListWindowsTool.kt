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
    override val description = "List open IDE windows and their background tasks, with per-window readiness (modal/indexing/initialized) and a `window_id` for screenshot/input targeting in multi-window setups. Each window and background-task entry references its project by `project_name` — the single routing key for the project-scoped tools; look up that project's human-readable `name` and `path` via steroid_list_projects by the key (they are not duplicated here). `project_name` is null for windows not tied to a project. Resolve an entry's `backend_name` to the owning IDE's identity (`intellij` = `{name, version, build}`) via the `backends` lookup in the same response."

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
 * top-level `ide`/`plugin`/`pid` header (#89): the responding server's identity lives in the MCP
 * server info, and per-entry attribution happens via `backend_name` on each window/task entry,
 * resolvable to the owning IDE's identity through the `backends[]` table of the same response (#155).
 */
@Serializable
data class ListWindowsResponse(
    val windows: List<ListedWindow>,
    val backgroundTasks: List<ListedBackgroundTask>,
    /**
     * Resolution table for this response (#155), sorted by `backend_name`: every `backend_name`
     * referenced by `windows[]` / `backgroundTasks[]` resolves to exactly one element. On a direct
     * in-IDE connection it is the single self entry, ALWAYS present even with zero open windows (a
     * server always describes itself — the identity probe). Via devrig it is derived from the same
     * routing snapshot the entries come from — a route-owning backend can appear with zero entries in
     * transient states (e.g. a project registered while its frame is still opening). Identity-only by
     * design: growth belongs to `devrig backend --json` (#151), never here.
     */
    val backends: List<BackendRef> = emptyList(),
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
     *
     * Serialized as snake_case `project_name`/`project_path` (#381) — one spelling of the routing key
     * across `steroid_list_projects` and `steroid_list_windows`, matching the sibling `backend_name`.
     */
    @SerialName("project_name") val projectName: String?,
    @SerialName("project_path") val projectPath: String?,
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
    /** Owning backend's backend_name; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)

/** Maps the wire [WindowInfo] to the MCP-only [ListedWindow], binding it to [backendName]. */
fun WindowInfo.listed(projectName: String?, backendName: String?): ListedWindow = ListedWindow(
    projectName = projectName,
    projectPath = projectPath,
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
     * to a known open project. Serialized as snake_case `project_name` (#381).
     */
    @SerialName("project_name") val projectName: String?,
    /** Owning backend's backend_name; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)

/** Maps the wire [ProgressTaskInfo] to the MCP-only [ListedBackgroundTask], binding it to [backendName]. */
fun ProgressTaskInfo.listed(projectName: String?, backendName: String?): ListedBackgroundTask = ListedBackgroundTask(
    title = title,
    text = text,
    text2 = text2,
    fraction = fraction,
    isIndeterminate = isIndeterminate,
    isCancellable = isCancellable,
    projectName = projectName,
    backendName = backendName,
)

/**
 * The wire DTO carried inside [NpxBridgeWindowsResponse] (devrig<->IDE). Pristine — the per-window
 * backend reference lives on the MCP-only [ListedWindow], never here.
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
 * [NpxBridgeWindowsResponse] (devrig<->IDE) — pristine; the per-task backend reference lives on the
 * MCP-only [ListedBackgroundTask], never here.
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
    /**
     * Within-IDE-unique project routing key this task belongs to — the same `project_name`
     * `steroid_list_projects` reports; null if the task isn't tied to a known open project.
     */
    val projectName: String?
)
