/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.serialization.json.*

/**
 * Core MCP server logic that handles JSON-RPC message dispatch.
 * Independent of transport layer.
 */
class McpServerCore(
    val serverInfo: ServerInfo,
    private val capabilities: ServerCapabilities,
    private val instructions: String? = null,
    /**
     * Invoked exactly once per session right after a successful `initialize`,
     * carrying the just-initialized [McpSession], this server's [ServerInfo],
     * and the client's requested protocol version. Used for a one-time
     * analytics beacon. Platform-agnostic — no IntelliJ types.
     */
    private val onSessionInitialized: (session: McpSession, serverInfo: ServerInfo, clientProtocolVersion: String) -> Unit = { _, _, _ -> },
) {
    private val log = thisLogger()
    val sessionManager = McpSessionManager()
    val toolRegistry = McpToolRegistry()
    val resourceRegistry = McpResourceRegistry()
    val promptRegistry = McpPromptRegistry()

    /**
     * Handle an incoming JSON-RPC message and return a response (if applicable).
     * Returns null for notifications that don't require a response.
     */
    suspend fun handleMessage(message: String, session: McpSession): String? {
        val jsonElement = try {
            McpJson.parseToJsonElement(message)
        } catch (e: Exception) {
            return encodeError(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error: ${e.message}")
        }

        return when (jsonElement) {
            is JsonArray -> handleBatch(jsonElement, session)
            is JsonObject -> handleSingle(jsonElement, session)
            else -> encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request")
        }
    }

    private suspend fun handleBatch(batch: JsonArray, session: McpSession): String? {
        // JSON-RPC 2.0 §6: an empty batch is a single Invalid Request error response,
        // not an empty array.
        if (batch.isEmpty()) {
            return encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Empty batch")
        }
        val responses = batch.mapNotNull { element ->
            if (element is JsonObject) {
                handleSingle(element, session)?.let { McpJson.parseToJsonElement(it) }
            } else {
                McpJson.parseToJsonElement(
                    encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST, "Invalid request in batch")
                )
            }
        }
        // §6 again: if every entry was a notification, the response MUST be omitted
        // entirely — return null, not "[]". The transport layer treats null as
        // "write nothing".
        if (responses.isEmpty()) return null
        return McpJson.encodeToString(JsonArray.serializer(), JsonArray(responses))
    }

    private suspend fun handleSingle(json: JsonObject, session: McpSession): String? {
        // Per JSON-RPC 2.0 §4: jsonrpc MUST be exactly "2.0".
        // https://www.jsonrpc.org/specification#request_object
        val jsonrpcField = json["jsonrpc"]
        if (jsonrpcField !is JsonPrimitive || !jsonrpcField.isString || jsonrpcField.content != JSONRPC_VERSION) {
            return encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST,
                "Invalid Request: jsonrpc must be \"$JSONRPC_VERSION\"")
        }

        // id MUST be string, number, or null when present. Object/array/boolean ids are
        // invalid. We tolerate fractional numbers (the spec only "discourages" them).
        val rawId = json["id"]
        val isNotification = rawId == null
        if (rawId != null && !isValidJsonRpcId(rawId)) {
            return encodeError(JsonNull, JsonRpcErrorCodes.INVALID_REQUEST,
                "Invalid Request: id must be a string, number, or null")
        }

        // method MUST be a string. method-on-notification with non-string drops silently
        // (notifications never produce error responses); method-on-request with non-string
        // is a -32600.
        val methodElement = json["method"]
        val method = if (methodElement is JsonPrimitive && methodElement.isString) methodElement.content else null

        // Notification path: no response under any circumstances.
        if (isNotification) {
            if (method != null) handleNotification(method)
            return null
        }

        // No method-field at all: this might be a response to a server-initiated request
        // (sampling/createMessage, roots/list). If the id matches a pending request, the
        // session consumes the response and we emit no reply. Otherwise it's a malformed
        // request (a request MUST have a method).
        if (methodElement == null) {
            val routed = tryRouteServerResponse(rawId, json, session)
            if (routed) return null
            return encodeError(rawId, JsonRpcErrorCodes.INVALID_REQUEST, "Missing method")
        }
        // method present but not a string
        if (method == null) {
            return encodeError(rawId, JsonRpcErrorCodes.INVALID_REQUEST,
                "Invalid Request: method must be a string")
        }

        // Per JSON-RPC 2.0 §4.2: params, when present, MUST be a structured value
        // (object or array). MCP only uses object params, so we reject primitives /
        // arrays as -32600. `null` and missing are equivalent to "no params".
        val paramsElement = json["params"]
        val params: JsonObject? = when {
            paramsElement == null || paramsElement is JsonNull -> null
            paramsElement is JsonObject -> paramsElement
            else -> return encodeError(rawId, JsonRpcErrorCodes.INVALID_REQUEST,
                "Invalid Request: params must be an object")
        }

        return handleRequest(rawId, method, params, session)
    }

    /**
     * If [json] is a response to a pending server-initiated request, route it through
     * the [session]'s response handler and return `true`. Otherwise return `false`.
     * Stray responses (id matches no pending request) are silently dropped per JSON-RPC §5
     * — also `true` to suppress an error reply we don't have the right id for.
     */
    private fun tryRouteServerResponse(id: JsonElement, json: JsonObject, session: McpSession): Boolean {
        val result = json["result"]
        val error = json["error"]
        if (result == null && error == null) return false
        val idString = (id as? JsonPrimitive)?.contentOrNull ?: id.toString().trim('"')

        if (result != null) {
            session.handleResponse(idString, result)
            return true
        }
        if (error != null) {
            val rpcError = try {
                McpJson.decodeFromJsonElement<JsonRpcError>(error)
            } catch (e: Exception) {
                log.warn("Failed to decode incoming JSON-RPC error response", e)
                JsonRpcError(code = -1, message = "Unknown error")
            }
            session.handleErrorResponse(idString, rpcError)
            return true
        }
        return false
    }

    private fun isValidJsonRpcId(id: JsonElement): Boolean = when (id) {
        is JsonNull -> true
        is JsonPrimitive -> id.isString || id.longOrNull != null || id.doubleOrNull != null
        else -> false
    }

    private suspend fun handleRequest(
        id: JsonElement,
        method: String,
        params: JsonObject?,
        session: McpSession
    ): String {
        return when (method) {
            McpMethods.INITIALIZE -> handleInitialize(id, params, session)
            McpMethods.PING -> handlePing(id)
            McpMethods.TOOLS_LIST -> handleToolsList(id)
            McpMethods.TOOLS_CALL -> handleToolsCall(id, params, session)
            McpMethods.PROMPTS_LIST -> handlePromptsList(id, params)
            McpMethods.PROMPTS_GET -> handlePromptsGet(id, params)
            McpMethods.RESOURCES_LIST -> handleResourcesList(id)
            McpMethods.RESOURCES_READ -> handleResourcesRead(id, params)
            McpMethods.LOGGING_SET_LEVEL -> handleLoggingSetLevel(id)
            else -> encodeError(id, JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun handleNotification(method: String) {
        // Notifications never produce a response per JSON-RPC §4.1; we just record them.
        // Per-method handlers can be added here when they need to do work.
        log.info("Client notification: $method")
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject?, session: McpSession): String {
        // Per MCP 2025-11-25 §Lifecycle/Initialization, initialize MUST carry params with
        // protocolVersion, capabilities, and clientInfo. Treat missing params as -32602
        // so the client gets a clear protocol error instead of silently returning an
        // InitializeResult against an uninitialized session.
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS,
                "initialize requires params with protocolVersion, capabilities, clientInfo")
        }
        val initParams = try {
            McpJson.decodeFromJsonElement<InitializeParams>(params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid initialize params: ${e.message}")
        }

        session.markInitialized(initParams.clientInfo, initParams.capabilities)

        // Fire the once-per-session initialization beacon. Guard against a duplicate
        // `initialize` double-firing it, and never let a beacon error break the
        // initialize handshake.
        if (!session.initBeaconFired) {
            session.initBeaconFired = true
            try {
                onSessionInitialized(session, serverInfo, initParams.protocolVersion)
            } catch (e: Exception) {
                log.warn("onSessionInitialized beacon failed", e)
            }
        }

        // Version negotiation (MCP Lifecycle → Version Negotiation): echo the client's
        // requested revision when we support it; otherwise reply with our own latest and
        // let the client decide whether it can proceed.
        val negotiatedVersion = if (initParams.protocolVersion in SUPPORTED_PROTOCOL_VERSIONS) {
            initParams.protocolVersion
        } else {
            MCP_PROTOCOL_VERSION
        }
        session.markProtocolVersion(negotiatedVersion)

        val result = InitializeResult(
            protocolVersion = negotiatedVersion,
            capabilities = capabilities,
            serverInfo = serverInfo,
            instructions = instructions
        )

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePing(id: JsonElement): String {
        return encodeResult(id, buildJsonObject {  })
    }

    /**
     * `logging/setLevel` — we accept the request and return an empty result so
     * compliant clients don't error. We always emit logging notifications at
     * "warning" regardless of the requested level.
     */
    private fun handleLoggingSetLevel(id: JsonElement): String {
        return encodeResult(id, buildJsonObject {  })
    }

    private fun handleToolsList(id: JsonElement): String {
        val tools = toolRegistry.listTools()
        val result = ToolsListResult(tools = tools)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private suspend fun handleToolsCall(id: JsonElement, params: JsonObject?, session: McpSession): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing tool call params")
        }

        val callParams = try {
            McpJson
                .decodeFromJsonElement<ToolCallParams>(params)
                .copy(rawArguments = params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid tool call params: ${e.message}")
        }

        val result = toolRegistry.callTool(callParams, session)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handleResourcesList(id: JsonElement): String {
        val resources = resourceRegistry.listResources()
        log.info("MCP resources/list: ${resources.size} resources")
        val result = ResourcesListResult(resources = resources)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePromptsList(id: JsonElement, params: JsonObject?): String {
        if (params != null) {
            try {
                McpJson.decodeFromJsonElement<PromptsListParams>(params)
            } catch (e: Exception) {
                return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid prompts list params: ${e.message}")
            }
        }
        val prompts = promptRegistry.listPrompts()
        val result = PromptsListResult(prompts = prompts)
        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handlePromptsGet(id: JsonElement, params: JsonObject?): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing prompt get params")
        }

        val getParams = try {
            McpJson.decodeFromJsonElement<PromptGetParams>(params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid prompt get params: ${e.message}")
        }

        val result = promptRegistry.getPrompt(getParams)
            ?: return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Prompt not found: ${getParams.name}")

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun handleResourcesRead(id: JsonElement, params: JsonObject?): String {
        if (params == null) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Missing resource read params")
        }

        val readParams = try {
            McpJson.decodeFromJsonElement<ResourceReadParams>(params)
        } catch (e: Exception) {
            return encodeError(id, JsonRpcErrorCodes.INVALID_PARAMS, "Invalid resource read params: ${e.message}")
        }

        val result = resourceRegistry.readResource(readParams.uri)
            ?: return encodeError(id, JsonRpcErrorCodes.RESOURCE_NOT_FOUND, "Resource not found: ${readParams.uri}")

        log.info("MCP resource read: ${readParams.uri}")

        return encodeResult(id, McpJson.encodeToJsonElement(result))
    }

    private fun encodeResult(id: JsonElement, result: JsonElement): String {
        val response = JsonRpcResponse(id = id, result = result)
        return McpJson.encodeToString(JsonRpcResponse.serializer(), response)
    }

    private fun encodeError(id: JsonElement, code: Int, message: String): String =
        encodeJsonRpcError(id, code, message)

    /**
     * Send tools/list_changed notification to all sessions.
     * Currently unused but part of MCP protocol API for future use.
     */
    @Suppress("unused")
    fun notifyToolsListChanged() {
        val notification = JsonRpcNotification(method = McpMethods.TOOLS_LIST_CHANGED)
        sessionManager.getAllSessions().forEach { it.sendNotification(notification) }
    }

    /**
     * Broadcast a `notifications/message` (server logging) notification to all
     * initialized sessions. Used to deliver "update available" notices over a
     * transport that drains notifications continuously (devrig stdio). The
     * HTTP/plugin transport has no out-of-band server→client channel and does
     * not use this — the plugin surfaces updates via its own IDE notification.
     */
    fun broadcastLogMessage(level: String, logger: String, data: JsonElement) {
        val notification = loggingMessageNotification(level, logger, data)
        sessionManager.getAllSessions()
            .filter { it.initialized }
            .forEach { it.sendNotification(notification) }
    }
}
