/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendVersionSkew
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.devrig.collectBackendInfos
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.NpxBridgeToolCallRequest
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.listed
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class DevrigListWindowsToolHandler(
    private val states: () -> Collection<IdeMonitorState>,
    private val httpClient: HttpClient,
    private val routing: DevrigProjectRoutingService,
    private val inventory: BackendInventory,
) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = coroutineScope {
        val monitored = states().toList()
        val responses = monitored.map { state ->
            async {
                val response = httpClient.get("${state.ide.rpcBaseUrl}/windows") {
                    headers {
                        for ((name, value) in state.ide.bridgeHeaders) {
                            append(name, value)
                        }
                    }
                }
                if (response.status.value !in 200..299) {
                    error("HTTP ${response.status.value} from ${state.ide.label} bridge /windows: ${response.bodyAsText()}")
                }
                state to McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), response.bodyAsText())
            }
        }.awaitAll()

        // Every window/background-task is bound to its source IDE via backend_name — the same R3.3 id
        // the inventory computes for that IDE's marker row, so entries join backends[] by name.
        ListWindowsResponse(
            windows = responses.flatMap { (state, response) ->
                val backendName = backendNameForMarker(state.ide.pid, state.ide.marker.ide.build)
                // The IDE already stamped each window's within-IDE-unique project_name (#92); pass it through.
                response.windows.map { it.listed(backendName) }
            },
            backgroundTasks = responses.flatMap { (state, response) ->
                val backendName = backendNameForMarker(state.ide.pid, state.ide.marker.ide.build)
                response.backgroundTasks.map { it.listed(backendName) }
            },
            backends = inventory.collectBackendInfos(),
        )
    }
}

class DevrigExecuteCodeToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val beacon: DevrigBeacon,
) : ExecuteCodeToolHandler {
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        // Version-base skew check on every routed exec_code call (devrig scenario only; stderr).
        BackendVersionSkew.warnOnExecCode(pid = route.idePid, pluginVersion = route.plugin.version)
        val result = bridge.callTool(route, "steroid_execute_code", callProgress) {
            // Forward the within-IDE-unique name (== the IDE's own, shared scheme) so the IDE re-derives
            // + matches the exact project, never the first same-named one (issue #92).
            put("project_name", route.exposedProjectName)
            put("code", execCodeParams.code)
            put("task_id", execCodeParams.taskId)
            put("reason", execCodeParams.reason)
            put("timeout", execCodeParams.timeout)
            put("modal", execCodeParams.modal.wire)
        }
        beacon.capture("exec_code", mapOf("result" to if (result.isError == true) "error" else "success"))
        return result
    }
}

class DevrigExecuteFeedbackToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val beacon: DevrigBeacon,
) : ExecuteFeedbackToolHandler {
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        val result = bridge.callTool(route, "steroid_execute_feedback") {
            // Forward the within-IDE-unique name (== the IDE's own, shared scheme) so the IDE re-derives
            // + matches the exact project, never the first same-named one (issue #92).
            put("project_name", route.exposedProjectName)
            put("task_id", params.taskId)
            put("success_rating", params.successRating)
            params.explanation?.let { put("explanation", it) }
            params.code?.let { put("code", it) }
        }
        // Mirror the ij-plugin's status_score event: 0.0-1.0 rating -> 0-100 score.
        beacon.captureScore((params.successRating * 100).toInt(), context = "feedback")
        return result
    }
}

class DevrigVisionScreenshotToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionScreenshotToolHandler {
    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        return bridge.callTool(route, "steroid_take_screenshot", mcpProgressReporter) {
            // Forward the within-IDE-unique name (== the IDE's own, shared scheme) so the IDE re-derives
            // + matches the exact project, never the first same-named one (issue #92).
            put("project_name", route.exposedProjectName)
            put("task_id", screenshotParams.taskId)
            put("reason", screenshotParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            screenshotParams.windowId?.let { put("window_id", it) }
        }
    }
}

class DevrigVisionInputToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : VisionInputToolHandler {
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val route = bridge.routing.requireProject(projectName)
        val rawSequence = inputParams.rawSequence
            ?: return ToolCallResult.errorResult("Input sequence cannot be forwarded without the original sequence string")
        return bridge.callTool(route, "steroid_input") {
            // Forward the within-IDE-unique name (== the IDE's own, shared scheme) so the IDE re-derives
            // + matches the exact project, never the first same-named one (issue #92).
            put("project_name", route.exposedProjectName)
            put("task_id", inputParams.taskId)
            put("reason", inputParams.reason)
            // window_id is unique within the IDE resolved by project_name; forward it as-is.
            put("window_id", inputParams.windowId)
            put("sequence", rawSequence)
        }
    }
}

class DevrigOpenProjectToolHandler(
    private val bridge: DevrigToolBridgeClient,
) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult {
        val requestedBackend = openProjectParams.backendName?.trim()?.takeIf { it.isNotEmpty() }

        // When the agent named a backend (devrig surface, where backend_name is REQUIRED), resolve it to
        // that exact IDE. When it is absent (direct-handler / E2E callers pass null), keep the existing
        // auto-pick: prefer a devrig-managed backend, else the newest discovered IDE.
        // Off the call dispatcher: the managed-pid lookup scans the local backends dir + checks pid liveness.
        val ide = withContext(Dispatchers.IO) {
            if (requestedBackend != null) {
                bridge.routing.resolveBackend(requestedBackend)
            } else {
                bridge.routing.openProjectTargetIde()
            }
        }

        if (ide == null) {
            return if (requestedBackend != null) {
                // The id is now an opaque hash (R3.3) — no prefix to key a hint off. resolveBackend only ever
                // returns routable marker IDEs, so a miss means the requested name is not a currently-routable
                // backend. Self-correct by listing the routable backend_names; the agent likely copied a
                // non-routable id (a port-only or not-yet-running managed backend) from `devrig backend --json`.
                val routable = bridge.routing.discoveredBackends().map { it.first }
                ToolCallResult.errorResult(
                    "Unknown backend_name '$requestedBackend'. Only running IDEs with the MCP Steroid plugin " +
                        "are routable for open_project; port-only and not-yet-running managed backends listed by " +
                        "'devrig backend --json' are not. " +
                        if (routable.isEmpty()) "No routable IDE backends are currently discovered; start an IDE or call steroid_list_projects."
                        else "Routable backends: ${routable.joinToString(", ")}. Call steroid_list_projects to refresh."
                )
            } else {
                ToolCallResult.errorResult(
                    "steroid_open_project requires at least one discovered IDE with the MCP Steroid plugin; start an IDE or call steroid_list_projects"
                )
            }
        }

        val route = ProjectRoute(
            idePid = ide.pid,
            bridgeBaseUrl = ide.rpcBaseUrl,
            headers = ide.bridgeHeaders,
            originalProjectName = "",
            exposedProjectName = "",
            projectPath = "",
            realProjectHome = java.nio.file.Path.of(".").toAbsolutePath().normalize(),
            projectHash = "",
            ide = ide.marker.ide,
            plugin = ide.marker.plugin,
        )
        return bridge.callTool(route, "steroid_open_project") {
            put("project_path", openProjectParams.projectPath)
            put("trust_project", openProjectParams.trustProject)
            put("task_id", "open-project")
            put("reason", "Open project through devrig")
        }
    }
}

class DevrigToolBridgeClient(
    val routing: DevrigProjectRoutingService,
    private val httpClient: HttpClient,
) {
    suspend fun callTool(
        route: ProjectRoute,
        toolName: String,
        progress: McpProgressReporter? = null,
        arguments: JsonObjectBuilder.() -> Unit,
    ): ToolCallResult {
        val args = buildJsonObject { JsonObjectBuilder(this).arguments() }
        val requestBody = McpJson.encodeToString(
            NpxBridgeToolCallRequest.serializer(),
            NpxBridgeToolCallRequest(name = toolName, arguments = args),
        )
        val url = "${route.bridgeBaseUrl}/tools/call/stream"
        var result: ToolCallResult? = null
        var errorMessage: String? = null

        httpClient.preparePost(url) {
            headers {
                append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                for ((name, value) in route.headers) {
                    append(name, value)
                }
            }
            setBody(requestBody)
        }.execute { response ->
            if (response.status.value !in 200..299) {
                errorMessage = "HTTP ${response.status.value} from $url: ${response.bodyAsText()}"
                return@execute
            }
            readNdjson(response.bodyAsChannel()) { line ->
                if (errorMessage != null) return@readNdjson
                val json = try {
                    McpJson.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    errorMessage = "Malformed NDJSON data from $url: ${e.javaClass.simpleName}: ${e.message}; data=${line.take(200)}"
                    return@readNdjson
                }
                when (json["type"]?.jsonPrimitive?.contentOrNull) {
                    "progress" -> json["message"]?.jsonPrimitive?.contentOrNull?.let { progress?.report(it) }
                    "result" -> {
                        val resultElement = json["result"]
                        if (resultElement == null) {
                            errorMessage = "NDJSON result message did not include result from $url"
                            return@readNdjson
                        }
                        result = try {
                            McpJson.decodeFromJsonElement(ToolCallResult.serializer(), resultElement)
                        } catch (e: Exception) {
                            errorMessage = "Malformed NDJSON result from $url: ${e.javaClass.simpleName}: ${e.message}; data=${line.take(200)}"
                            return@readNdjson
                        }
                    }
                    "error" -> errorMessage = json["message"]?.jsonPrimitive?.contentOrNull ?: "Tool call failed"
                }
            }
        }

        errorMessage?.let { return ToolCallResult.errorResult(it) }
        return result ?: ToolCallResult.errorResult("No result received from $url")
    }
}

class JsonObjectBuilder(private val target: kotlinx.serialization.json.JsonObjectBuilder) {
    fun put(key: String, value: String) = target.put(key, value)
    fun put(key: String, value: Int) = target.put(key, value)
    fun put(key: String, value: Double) = target.put(key, value)
    fun put(key: String, value: Boolean) = target.put(key, value)
    fun put(key: String, value: JsonElement) = target.put(key, value)
    fun putJsonArray(key: String, builder: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) =
        target.putJsonArray(key, builder)
}

suspend fun readNdjson(
    channel: ByteReadChannel,
    emit: suspend (line: String) -> Unit,
) {
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        if (line.isBlank()) continue
        emit(line)
    }
}
