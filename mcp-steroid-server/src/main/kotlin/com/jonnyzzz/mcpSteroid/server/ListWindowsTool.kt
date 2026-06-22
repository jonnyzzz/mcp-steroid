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
    override val description = "List open IDE windows and their associated projects. Use this to choose project_name for screenshot/input tools in multi-window setups."

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
    val backends: List<ListedBackendInfo> = emptyList(),
)

/**
 * MCP-only window entry: all [WindowInfo] wire fields verbatim plus the owning backend's
 * `backend_name`. Never serialized onto the devrig<->IDE wire — the wire stays [WindowInfo].
 */
@Serializable
data class ListedWindow(
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
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
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
    /** Project name this task belongs to (if known) */
    val projectName: String?,
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
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
    /** Project name this task belongs to (if known) */
    val projectName: String?
)
