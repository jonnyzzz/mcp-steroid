package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
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
    override val description = "List all open projects in the IDE. Returns project names that can be used with steroid_execute_code and steroid_open_project."

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
 * top-level `ide`/`plugin`/`pid` header: the responding server's identity lives in the MCP server
 * info, and per-entry attribution happens via `backend_name` against [backends].
 */
@Serializable
data class ListProjectsResponse(
    /**
     * Projects reachable through this connection. On a direct in-IDE connection `project_name == name`
     * and `backend_name` is this IDE's self-id; on devrig `project_name` is the disambiguated exposed
     * name and `backend_name` is the owning discovered IDE.
     */
    val projects: List<ListedProject>,
    /**
     * Backends reachable through this connection. On a direct in-IDE connection exactly one entry (this
     * IDE); on devrig one entry per discovered IDE. The `backend_name` of any routable entry is a valid
     * argument for steroid_open_project.
     */
    val backends: List<BackendInfo> = emptyList(),
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
 * One shared backend-info schema (R3.4) backing both the MCP `steroid_list_projects` `backends[]` and the
 * devrig CLI `backend/project --json` `backends[]`. MCP/CLI-surface only — never crosses the
 * devrig<->IDE wire.
 */
@Serializable
data class BackendInfo(
    /** R3.3 uniform opaque id, e.g. "iu-9fk2a0xQ". == backend_name passed to steroid_open_project. */
    @SerialName("backend_name") val backendName: String,
    /**
     * Backend family. Today only `"intellij"`; documented as open so more backend types (other editors,
     * remote/dev backends) can appear later without a schema break.
     */
    val type: String = "intellij",
    /** "marker" | "port" | "managed". */
    val source: String,
    val displayName: String,
    val locator: String,
    /** True when open_project-routable (a marker IDE with a live bridge). */
    val routable: Boolean,
    /** True when discovery-reachable. */
    val reachable: Boolean,
    /**
     * True when this devrig instance owns the backend's lifecycle (`devrig backend start`) — the
     * documented tier-2 pick for open_project when no worktree match exists. Orthogonal to [source]:
     * a RUNNING managed backend appears as `source="marker"` with `managed=true`; `source="managed"`
     * covers installed-but-not-running rows only.
     */
    val managed: Boolean = false,
    /** Listed for humans/disambiguation; NOT encoded into [backendName]. */
    val pid: Long? = null,
    val port: Int? = null,
    val ideProductCode: String? = null,
    val build: String? = null,
    /**
     * Plugins observed on this backend (marker rows only today). An MCP Steroid marker contributes one
     * [BackendPlugin] with `kind == MCP_STEROID_PLUGIN_KIND`; port/managed rows carry an empty list. Use
     * [mcpSteroidPlugin] / [hasMcpSteroid] instead of scanning the list by hand.
     */
    val plugins: List<BackendPlugin> = emptyList(),
    /** Marker-unreachable message (was backendEntryJson "error"). */
    val error: String? = null,
    /** Port-only identity extras (renamed from the colliding scalar `port`). */
    val portDetail: PortBackendDetail? = null,
    /** Managed-only extras. */
    val managedDetail: ManagedBackendDetail? = null,
    val openProjects: List<ListedProject> = emptyList(),
)

/**
 * One plugin observed on a [BackendInfo]. MCP/CLI-surface only. [kind] classifies the plugin so consumers
 * can find the MCP Steroid one without matching on id strings: [MCP_STEROID_PLUGIN_KIND] for our plugin,
 * `"other"` for everything else (room for e.g. `"intellij-native-mcp"` later).
 *
 * Today only the MCP Steroid plugin can appear: the marker carries exactly one [PluginInfo]. Enumerating
 * further plugins (e.g. the IDE's built-in MCP server as `"intellij-native-mcp"`) needs an additive
 * `PidMarker.plugins` wire extension — tracked on GH issue #88.
 */
@Serializable
data class BackendPlugin(
    val id: String,
    val name: String,
    val version: String,
    val kind: String = "other",
)

/** [BackendPlugin.kind] for the MCP Steroid plugin itself. */
const val MCP_STEROID_PLUGIN_KIND = "mcp-steroid"

/** The MCP Steroid plugin id, used to classify a [PluginInfo] as [MCP_STEROID_PLUGIN_KIND]. */
const val MCP_STEROID_PLUGIN_ID = "com.jonnyzzz.mcp-steroid"

/** The MCP Steroid plugin on this backend, if present (the first `kind == mcp-steroid` entry). */
fun BackendInfo.mcpSteroidPlugin(): BackendPlugin? =
    plugins.firstOrNull { it.kind == MCP_STEROID_PLUGIN_KIND }

/** True when this backend reports the MCP Steroid plugin installed (replaces `mcpSteroidPluginInstalled`). */
fun BackendInfo.hasMcpSteroid(): Boolean = mcpSteroidPlugin() != null

/**
 * Builds the marker [BackendInfo.plugins] list from a discovered [PluginInfo]: a single [BackendPlugin],
 * tagged [MCP_STEROID_PLUGIN_KIND] iff its id is [MCP_STEROID_PLUGIN_ID], else `"other"`.
 */
fun mcpSteroidPlugins(plugin: PluginInfo): List<BackendPlugin> = listOf(
    BackendPlugin(
        id = plugin.id,
        name = plugin.name,
        version = plugin.version,
        kind = if (plugin.id == MCP_STEROID_PLUGIN_ID) MCP_STEROID_PLUGIN_KIND else "other",
    ),
)

/**
 * Shared marker display name — the `"<name> <version>"` rule used by both the in-IDE self-describe and
 * devrig's discovery. Trims; drops the version when blank or already a suffix of the name.
 */
fun markerDisplayName(ide: IdeInfo): String {
    val trimmedName = ide.name.trim()
    val trimmedVersion = ide.version.trim()
    if (trimmedVersion.isEmpty()) return trimmedName
    if (trimmedName == trimmedVersion || trimmedName.endsWith(" $trimmedVersion")) return trimmedName
    return "$trimmedName $trimmedVersion".trim()
}

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

/**
 * The ONE marker-row -> [BackendInfo] assembler, shared by the in-IDE `steroid_list_projects`
 * self-describe and devrig's `backendInfoForRow(FromMarker)`. Produces `source="marker"`,
 * `type="intellij"`, with the build-derived product code and the shared display/locator formatters.
 *
 * [locator] is overridable so devrig can pass its `backendLocatorLabel(row)` (which appends a
 * `", managed"` suffix for managed marker rows); the default is the plain [markerLocator].
 */
fun markerBackendInfo(
    backendName: String,
    pid: Long,
    ide: IdeInfo,
    plugins: List<BackendPlugin>,
    openProjects: List<ListedProject>,
    managed: Boolean = false,
    routable: Boolean = true,
    reachable: Boolean = true,
    error: String? = null,
    locator: String = markerLocator(ide.build, pid),
): BackendInfo = BackendInfo(
    backendName = backendName,
    type = "intellij",
    source = "marker",
    displayName = markerDisplayName(ide),
    locator = locator,
    routable = routable,
    reachable = reachable,
    managed = managed,
    pid = pid,
    ideProductCode = productCodeFromBuild(ide.build),
    build = ide.build,
    plugins = plugins,
    error = error,
    openProjects = openProjects,
)

@Serializable
data class PortBackendDetail(
    val baseUrl: String? = null,
    val productName: String? = null,
    val productFullName: String? = null,
    val edition: String? = null,
    val baselineVersion: Int? = null,
    val buildNumber: String? = null,
)

@Serializable
data class ManagedBackendDetail(
    val managedId: String,
    val productKey: String,
    val productCode: String,
    val version: String,
    val buildNumber: String? = null,
    val state: String,
    val installPath: String,
    val cachePath: String,
    val runningPid: Long? = null,
)

@Serializable
data class ListedProject(
    /** devrig: exposed disambiguated name; IDE-direct: the real project name. */
    @SerialName("project_name") val projectName: String,
    /** Raw folder name (R3.7) — kept so existing `jq '.projects[].name'` consumers do not break. */
    val name: String,
    val path: String,
    /** Owning backend's [BackendInfo.backendName]; null only when unknown. */
    @SerialName("backend_name") val backendName: String? = null,
)
