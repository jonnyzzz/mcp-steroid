/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.*

/**
 * MCP Protocol implementation following the 2025-11-25 specification.
 * https://modelcontextprotocol.io/specification/2025-11-25
 */

const val MCP_PROTOCOL_VERSION = "2025-11-25"
const val JSONRPC_VERSION = "2.0"

// ==================== JSON-RPC Base Types ====================

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = JSONRPC_VERSION,
    val id: JsonElement,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = JSONRPC_VERSION,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = JSONRPC_VERSION,
    val id: JsonElement,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

/**
 * Encode a JSON-RPC error response to a String — used by the transport
 * boundary to convert internal exceptions into well-formed JSON-RPC
 * error envelopes (HTTP 200 with `error.code/message` in the body)
 * instead of bare HTTP 500s. Mirrors the per-call shape `McpServerCore`
 * uses internally; lives here so layers above `McpServerCore` (transports,
 * health checks) can construct the same shape without exposing private API.
 */
fun encodeJsonRpcError(id: JsonElement, code: Int, message: String): String {
    val response = JsonRpcResponse(
        id = id,
        error = JsonRpcError(code = code, message = message),
    )
    return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
}

// Standard JSON-RPC error codes
object JsonRpcErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    /**
     * MCP-specific code for `resources/read` against an unknown URI. Defined in the
     * MCP 2025-11-25 spec ("Resources → Error Handling") inside the JSON-RPC
     * server-error reserved range (-32099..-32000).
     * https://modelcontextprotocol.io/specification/2025-11-25/server/resources#error-handling
     */
    const val RESOURCE_NOT_FOUND = -32002
}

// ==================== MCP Initialize ====================

@Serializable
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities,
    val clientInfo: ClientInfo,
)

@Serializable
data class ClientInfo(
    val name: String,
    val version: String,
    val title: String? = null,
)

@Serializable
data class ClientCapabilities(
    val roots: RootsCapability? = null,
    val sampling: JsonObject? = null,
    val elicitation: JsonObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
    val instructions: String? = null,
)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val title: String? = null,
)

@Serializable
data class ServerCapabilities(
    val tools: ToolsCapability? = null,
    val prompts: PromptsCapability? = null,
    val resources: ResourcesCapability? = null,
    val logging: JsonObject? = null,
    val completions: JsonObject? = null,
    val experimental: JsonObject? = null,
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null,
)

// ==================== MCP Tools ====================

@Serializable
data class Tool(
    val name: String,
    val description: String? = null,
    val title: String? = null,
    val inputSchema: JsonObject,
    val outputSchema: JsonObject? = null,
)

@Suppress("unused")
@Serializable
data class ToolsListParams(
    val cursor: String? = null,
)

@Serializable
data class ToolsListResult(
    val tools: List<Tool>,
    val nextCursor: String? = null,
)

@Serializable
data class ToolCallParams(
    val name: String,
    val arguments: JsonObject = buildJsonObject {  },

    @Transient
    val rawArguments: JsonObject = buildJsonObject {  },

    /** Transport-owned metadata that cannot be supplied through MCP JSON. */
    @Transient
    val trustedArguments: JsonObject = buildJsonObject {  },
)

@Serializable
data class ToolCallResult(
    val content: List<ContentItem>,
    val isError: Boolean = false,
    // NOTE: Structured results make the LLM ignore all content, so it gets blind.
    //val structuredContent: JsonElement? = null,
)

fun ToolCallResult.Companion.errorResult(message: String) = ToolCallResult(
    content = listOf(ContentItem.Text(text = "ERROR: $message")),
    isError = true
)

fun ToolCallResult.Companion.successTextResult(message: String) = ToolCallResult(
    content = listOf(ContentItem.Text(text = message)),
    isError = false
)

class ToolCallErrorException(override val message: String) : RuntimeException(message) {
    val toolCallResult: ToolCallResult get() = ToolCallResult.errorResult(message)
}

// ==================== Content Types ====================

@Serializable
sealed class ContentItem {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : ContentItem()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String,
    ) : ContentItem()

    @Serializable
    @SerialName("resource")
    data class Resource(
        val resource: EmbeddedResource,
    ) : ContentItem()
}

@Serializable
data class EmbeddedResource(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

// ==================== Progress Notifications ====================

@Serializable
data class ProgressParams(
    val progressToken: JsonElement,
    val progress: Double,
    val total: Double? = null,
    val message: String? = null,
)

// ==================== MCP Logging ====================

/**
 * Build a `notifications/message` (server logging) notification per the
 * MCP 2025-11-25 "Logging" utility. The server advertises the `logging`
 * capability and emits these at the requested [level] (we always use
 * "warning" for update notices). [data] is an arbitrary JSON value — we
 * pass a plain JSON string carrying the human-readable message.
 */
fun loggingMessageNotification(level: String, logger: String, data: JsonElement): JsonRpcNotification =
    JsonRpcNotification(
        method = McpMethods.LOGGING_MESSAGE,
        params = buildJsonObject {
            put("level", level)
            put("logger", logger)
            put("data", data)
        }
    )

// ==================== MCP Sampling ====================

/**
 * Parameters for sampling/createMessage request.
 * Server sends this to the client to request LLM completion.
 * Per MCP 2025-11-25 specification.
 */
@Serializable
data class CreateMessageParams(
    val messages: List<SamplingMessage>,
    val modelPreferences: ModelPreferences? = null,
    val systemPrompt: String? = null,
    val includeContext: String? = null, // "allServers" | "none" | "thisServer"
    val maxTokens: Int? = null,
)

@Serializable
data class SamplingMessage(
    val role: String, // "user" | "assistant"
    val content: SamplingContent,
)

@Serializable
sealed class SamplingContent {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : SamplingContent()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String,
    ) : SamplingContent()
}

@Serializable
data class ModelPreferences(
    val hints: List<ModelHint>? = null,
    val costPriority: Double? = null,
    val speedPriority: Double? = null,
    val intelligencePriority: Double? = null,
)

@Serializable
data class ModelHint(
    val name: String? = null,
)

/**
 * Result from sampling/createMessage.
 * Client returns this with the LLM's response.
 */
@Serializable
data class CreateMessageResult(
    val role: String, // "assistant"
    val content: SamplingContent,
    val model: String? = null,
    val stopReason: String? = null, // "endTurn" | "stopSequence" | "maxTokens" | etc.
)

// ==================== MCP Resources ====================

/**
 * MCP Resource - represents a piece of content the server can provide.
 */
@Serializable
data class Resource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)

@Suppress("unused")
@Serializable
data class ResourcesListParams(
    val cursor: String? = null,
)

@Serializable
data class ResourcesListResult(
    val resources: List<Resource>,
    val nextCursor: String? = null,
)

@Serializable
data class ResourceReadParams(
    val uri: String,
)

@Serializable
data class ResourceReadResult(
    val contents: List<ResourceContent>,
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

// ==================== MCP Prompts ====================

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean? = null,
)

@Serializable
data class Icon(
    val src: String,
    val mimeType: String? = null,
    val sizes: List<String>? = null,
)


@Serializable
data class Prompt(
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val icons: List<Icon>? = null,
    val arguments: List<PromptArgument>? = null,
)

@Serializable
data class PromptsListParams(
    val cursor: String? = null,
)

@Serializable
data class PromptsListResult(
    val prompts: List<Prompt>,
    val nextCursor: String? = null,
)

@Serializable
data class PromptGetParams(
    val name: String,
    val arguments: Map<String, String>? = null,
)

@Serializable
data class PromptMessage(
    val role: String, // "user" | "assistant"
    val content: PromptContent,
)

@Serializable
sealed class PromptContent {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
    ) : PromptContent()

    @Serializable
    @SerialName("image")
    data class Image(
        val data: String,
        val mimeType: String,
    ) : PromptContent()

    @Serializable
    @SerialName("audio")
    data class Audio(
        val data: String,
        val mimeType: String,
    ) : PromptContent()

    @Serializable
    @SerialName("resource")
    data class Resource(
        val resource: EmbeddedResource,
    ) : PromptContent()
}

@Serializable
data class PromptGetResult(
    val description: String? = null,
    val messages: List<PromptMessage>,
)

// ==================== MCP Roots ====================

/**
 * MCP Root - represents a filesystem boundary the client exposes to the server.
 * Per MCP 2025-11-25 specification.
 */
@Serializable
data class Root(
    val uri: String, // MUST be a file:// URI
    val name: String? = null, // Optional human-readable name
)

@Serializable
data class RootsListResult(
    val roots: List<Root>,
)

// ==================== MCP Methods ====================

object McpMethods {
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val PROGRESS = "notifications/progress"
    const val LOGGING_MESSAGE = "notifications/message"
    const val LOGGING_SET_LEVEL = "logging/setLevel"
    const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
    @Suppress("unused")
    const val PROMPTS_LIST_CHANGED = "notifications/prompts/list_changed"
    const val ROOTS_LIST = "roots/list"
    @Suppress("unused")
    const val ROOTS_LIST_CHANGED = "notifications/roots/list_changed"
    const val PING = "ping"
    const val SAMPLING_CREATE_MESSAGE = "sampling/createMessage"
}
