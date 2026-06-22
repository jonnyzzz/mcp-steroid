package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.NpxBridgeToolCallRequest
import com.jonnyzzz.mcpSteroid.server.NpxBridgeWindowsResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.collections.iterator
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

class DevrigToolBridgeClient(
    private val httpClient: HttpClient,
) {
    /** Fetches the live window/background-task snapshot from a single IDE's bridge `/windows` endpoint. */
    suspend fun fetchWindows(ide: DiscoveredIde): NpxBridgeWindowsResponse {
        val url = "${ide.rpcBaseUrl}/windows"
        val response = httpClient.get(url) {
            setupHeader(ide)
        }
        if (response.status.value !in 200..299) {
            error("HTTP ${response.status.value} from ${ide.backendName} bridge /windows: ${response.bodyAsText()}")
        }
        //TODO: parse JSON here explicitly and map projects
        return McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), response.bodyAsText())
    }


    suspend fun callProjectTool(
        route: ProjectRoute,
        toolName: String,
        progress: McpProgressReporter? = null,
        arguments: JsonObjectBuilder.() -> Unit,
    ): ToolCallResult {
        return callTool(route.route, toolName, progress) {
            put("project_name", route.originalProjectName)
            arguments()
        }
    }

    suspend fun callTool(
        route: DiscoveredIde,
        toolName: String,
        progress: McpProgressReporter? = null,
        arguments: JsonObjectBuilder.() -> Unit,
    ): ToolCallResult {
        val args = buildJsonObject { arguments() }
        val requestBody = McpJson.encodeToString(
            NpxBridgeToolCallRequest.serializer(),
            NpxBridgeToolCallRequest(name = toolName, arguments = args),
        )
        val url = "${route.rpcBaseUrl}/tools/call/stream"
        var result: ToolCallResult? = null
        var errorMessage: String? = null

        httpClient.preparePost(url) {
            setupJsonContentType()
            setupHeader(route)
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


private fun HttpRequestBuilder.setupJsonContentType() {
    headers {
        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    }
}

private fun HttpRequestBuilder.setupHeader(ide: DiscoveredIde) {
    headers {
        for ((name, value) in ide.bridgeHeaders) {
            append(name, value)
        }
    }
}

private suspend fun readNdjson(
    channel: ByteReadChannel,
    emit: suspend (line: String) -> Unit,
) {
    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line() ?: break
        if (line.isBlank()) continue
        emit(line)
    }
}
