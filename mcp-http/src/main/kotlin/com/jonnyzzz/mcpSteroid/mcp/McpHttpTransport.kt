/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.thisLogger
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP Streamable HTTP Transport implementation for Ktor.
 * Follows the MCP 2025-11-25 specification.
 *
 * The transport supports:
 * - POST requests for sending messages (requests and notifications)
 * - GET requests for establishing SSE streams for server-to-client notifications
 * - OPTIONS requests for CORS preflight
 * - Session management via Mcp-Session-Id header
 *
 * Header requirements per MCP spec:
 * - Client MUST include Accept header with application/json (and optionally text/event-stream)
 * - Client MUST include Content-Type: application/json for POST requests
 * - Server responds with Content-Type: application/json for JSON responses
 */
object McpHttpTransport {
    private val log = thisLogger()

    const val SESSION_HEADER = "Mcp-Session-Id"
    const val SESSION_NOTICE_HEADER = "Mcp-Session-Notice"
    const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private const val CONTENT_TYPE_JSON = "application/json"
    private const val CONTENT_TYPE_SSE = "text/event-stream"
    private const val UNKNOWN_SESSION_NOTICE =
        "Unknown session; new session created. Update stored Mcp-Session-Id."
    /**
     * Install MCP routes at the specified path.
     */
    fun Route.installMcp(path: String, server: McpServerCore) {
        route(path) {
            // OPTIONS - CORS preflight
            options {
                handleOptions(call)
            }

            // POST - Handle incoming messages (requests and notifications)
            post {
                addCorsHeaders(call)
                handlePost(call, server)
            }

            // GET - SSE stream for server-to-client notifications
            // Per MCP spec:
            // - Client MUST include Accept header with text/event-stream
            // - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
            get {
                addCorsHeaders(call)
                handleGet(call, server)
            }

            // DELETE - Terminate session
            delete {
                addCorsHeaders(call)
                val remoteHost = call.request.local.remoteHost
                log.info("[MCP] DELETE request from $remoteHost")
                handleDelete(call, server)
            }
        }
    }

    /**
     * Handle OPTIONS preflight requests for CORS.
     */
    private suspend fun handleOptions(call: ApplicationCall) {
        val remoteHost = call.request.local.remoteHost
        log.info("[MCP] OPTIONS request from $remoteHost - returning CORS headers")
        addCorsHeaders(call)
        setProtocolVersionHeader(call, MCP_PROTOCOL_VERSION)
        call.respond(HttpStatusCode.NoContent)
    }

    /**
     * Add CORS headers to allow cross-origin requests from Claude CLI and other tools.
     */
    private fun addCorsHeaders(call: ApplicationCall) {
        call.response.header("Access-Control-Allow-Origin", "*")
        call.response.header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        call.response.header(
            "Access-Control-Allow-Headers",
            "Content-Type, Accept, $SESSION_HEADER, $SESSION_NOTICE_HEADER, $PROTOCOL_VERSION_HEADER"
        )
        call.response.header("Access-Control-Expose-Headers", "$SESSION_HEADER, $SESSION_NOTICE_HEADER, $PROTOCOL_VERSION_HEADER")
    }

    /**
     * Set the `MCP-Protocol-Version` response header. Per the MCP spec every response
     * carries it; on a message exchange it MUST be the version negotiated for the
     * session (so it matches the `initialize` result body), and on preflight / health /
     * teardown responses with no session it is our latest. `ktor`'s [call.response.header]
     * appends rather than replaces, so this must be the only place a value is set — never
     * in [addCorsHeaders], which runs first for every verb.
     */
    private fun setProtocolVersionHeader(call: ApplicationCall, version: String) {
        call.response.header(PROTOCOL_VERSION_HEADER, version)
    }

    private suspend fun handlePost(call: ApplicationCall, server: McpServerCore) {
        // Log incoming request details
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] POST request from $remoteHost (User-Agent: $userAgent)")

        // Log all headers for debugging
        call.request.headers.forEach { name, values ->
            log.debug("[MCP] Header: $name = ${values.joinToString(", ")}")
        }

        // Validate Accept header per MCP spec
        // Client MUST include Accept header that includes application/json
        val acceptHeader = call.request.accept()
        if (acceptHeader != null && !acceptsJson(acceptHeader)) {
            log.warn("[MCP] Rejecting request: Accept header '$acceptHeader' doesn't include application/json")
            call.respond(HttpStatusCode.NotAcceptable, "Accept header must include application/json")
            return
        }

        // Validate Content-Type header per MCP spec
        // Client MUST include Content-Type: application/json
        val contentType = call.request.contentType()
        if (!contentType.match(ContentType.Application.Json)) {
            log.warn("[MCP] Rejecting request: Content-Type '$contentType' is not application/json")
            call.respond(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/json")
            return
        }

        val body = call.receiveText()
        if (body.isBlank()) {
            log.warn("[MCP] Empty request body")
            call.respond(HttpStatusCode.BadRequest, "Empty request body")
            return
        }

        val sessionId = call.request.header(SESSION_HEADER)
        val (session, isNewSession, sessionNotice) = if (sessionId != null) {
            val existingSession = server.sessionManager.getSession(sessionId)
            if (existingSession != null) {
                log.debug("[MCP] Using existing session: $sessionId")
                Triple(existingSession, false, null)
            } else {
                log.info("[MCP] Unknown session ID: $sessionId (likely IDE was restarted)")
                log.info("[MCP] Creating new session for client (User-Agent: $userAgent)")
                Triple(server.sessionManager.createSession(), true, UNKNOWN_SESSION_NOTICE)
            }
        } else {
            log.info("[MCP] No session ID provided, creating new session")
            Triple(server.sessionManager.createSession(), true, null)
        }

        // Log the request body (truncated for large payloads)
        val truncatedBody = if (body.length > 500) body.take(500) + "...[truncated]" else body
        log.info("[MCP] Request body: $truncatedBody")

        // Set session-id headers BEFORE the boundary so a newly created
        // session is communicated to the client even when `handleMessage`
        // throws. Otherwise the client reconnects with its stale id and the
        // server creates a *second* session for the same logical client.
        if (isNewSession) {
            log.info("[MCP] Returning new session ID: ${session.id}")
            call.response.header(SESSION_HEADER, session.id)
        }
        if (sessionNotice != null) {
            call.response.header(SESSION_NOTICE_HEADER, sessionNotice)
        }

        // ── Boundary catch-all (issue #46 A0) ────────────────────────────────
        // Every code path below sends exactly one response, so the global
        // request-logging plugin's `ApplicationSendPipeline.After` interceptor
        // logs the terminal status line exactly once per call. The server-side
        // state stays healthy regardless of what `handleMessage` or the
        // response phase throws.
        //
        // `CancellationException` is rethrown without logging per the
        // `com.intellij.openapi.diagnostic.Logger` Javadoc contract for
        // control-flow exceptions — Kotlin coroutine cancellation is normal
        // structured flow, not an error to surface. Cancellation can arrive
        // from the client (connection closed) OR from a server-side
        // `withTimeout`; in either case ktor's pipeline finalizers handle
        // the call closure and the global send-interceptor logs the
        // terminal status exactly once via its `?: HttpStatusCode.OK`
        // fallback in `SteroidsMcpServer.requestLoggingPlugin`.
        //
        // Any other `Throwable` is converted to a well-formed JSON-RPC error
        // envelope (HTTP 200 with `error.code/message` in the body), not a
        // bare HTTP 500. JSON-RPC clients can decode the envelope and retry
        // or surface the error; a 500 with raw text looks like a transport
        // failure and stalls the session. The fallback `respondText` itself
        // is guarded — if the response stream is half-committed (the body
        // started flushing before the throw), a secondary throw would
        // otherwise escape the boundary and reintroduce #46's dual-status
        // log; instead we warn and let ktor close the call.
        try {
            val response = server.handleMessage(body, session)

            // Set AFTER handleMessage: an `initialize` negotiates the version inside it,
            // so the session carries the negotiated value only now. This makes the header
            // match the version in the initialize result body.
            setProtocolVersionHeader(call, session.negotiatedProtocolVersion)

            if (response != null) {
                val truncatedResponse = if (response.length > 500) response.take(500) + "...[truncated]" else response
                log.info("[MCP] Response: $truncatedResponse")
                call.respondText(response, ContentType.Application.Json)
            } else {
                log.info("[MCP] Notification processed, returning 202 Accepted")
                call.respond(HttpStatusCode.Accepted)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.error("[MCP] Unexpected error in handlePost", t)
            val errorResponse = encodeJsonRpcError(
                id = extractRequestId(body),
                code = JsonRpcErrorCodes.INTERNAL_ERROR,
                message = "Internal error: ${t.message ?: t.javaClass.simpleName}",
            )
            try {
                setProtocolVersionHeader(call, session.negotiatedProtocolVersion)
                call.respondText(errorResponse, ContentType.Application.Json)
            } catch (e: CancellationException) {
                throw e
            } catch (sendFailure: Throwable) {
                // Response stream is likely half-committed (client disconnect,
                // IO error mid-send). The primary failure is already logged
                // above; do not let this secondary throw escape into ktor,
                // which would emit a default 500 and double the log line.
                log.warn(
                    "[MCP] Failed to send JSON-RPC error response (response stream may be half-committed): " +
                        "${sendFailure.message}",
                    sendFailure,
                )
            }
        }
    }

    /**
     * Extract the JSON-RPC `id` from a raw request body for use in an error
     * envelope. Returns [JsonNull] if the body isn't valid JSON or has no `id`
     * field — the diagnostic must never itself throw.
     */
    private fun extractRequestId(body: String): JsonElement = try {
        McpJson.parseToJsonElement(body).jsonObject["id"] ?: JsonNull
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        JsonNull
    }

    /**
     * Handle GET requests.
     *
     * For SSE streams (Accept: text/event-stream only), per MCP spec:
     * - Server MUST return Content-Type: text/event-stream OR HTTP 405 Method Not Allowed
     *
     * For availability checks (Accept includes application/json or wildcard):
     * - Return server info JSON to indicate the server is available
     *
     * Important: Claude CLI sends "Accept: application/json, text/event-stream"
     * which means it accepts both - we should return JSON in this case.
     */
    private suspend fun handleGet(call: ApplicationCall, server: McpServerCore) {
        val remoteHost = call.request.local.remoteHost
        val userAgent = call.request.userAgent() ?: "unknown"
        log.info("[MCP] GET request from $remoteHost (User-Agent: $userAgent)")

        setProtocolVersionHeader(call, MCP_PROTOCOL_VERSION)

        val acceptHeader = call.request.accept()

        // If client accepts JSON (either directly or via wildcard), return server info
        // This is the common case for health checks from Claude CLI
        if (acceptHeader == null || acceptsJson(acceptHeader)) {
            log.info("[MCP] GET request from $remoteHost - returning server info (accepts JSON)")
            val serverInfo = server.serverInfo
            val payload = buildJsonObject {
                put("name", JsonPrimitive(serverInfo.name))
                put("version", JsonPrimitive(serverInfo.version))
                put("status", JsonPrimitive("available"))
            }
            call.respondText(McpJson.encodeToString(JsonObject.serializer(), payload), ContentType.Application.Json)
            return
        }

        // If client explicitly requests SSE only (not JSON), return 405 (we don't support it)
        if (acceptsSse(acceptHeader)) {
            log.info("[MCP] GET request from $remoteHost - returning 405 (SSE-only not supported)")
            call.respond(HttpStatusCode.MethodNotAllowed, "SSE notifications not supported")
            return
        }

        // For other accept types, return Not Acceptable
        log.warn("[MCP] GET request from $remoteHost - unsupported Accept header: $acceptHeader")
        call.respond(HttpStatusCode.NotAcceptable, "Unsupported Accept header")
    }

    /**
     * Check if the Accept header includes application/json.
     * Per MCP spec, clients MUST accept application/json for POST requests.
     */
    private fun acceptsJson(acceptHeader: String): Boolean {
        // Accept header can contain multiple types separated by comma
        // Each type can have parameters like q=0.9
        // Examples: "application/json", "*/*", "application/json, text/event-stream"
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_JSON || mediaType == "*/*" || mediaType == "application/*"
        }
    }

    /**
     * Check if the Accept header includes text/event-stream.
     * Per MCP spec, clients MUST accept text/event-stream for GET requests.
     */
    private fun acceptsSse(acceptHeader: String): Boolean {
        return acceptHeader.split(",").any { part ->
            val mediaType = part.trim().split(";").first().trim()
            mediaType == CONTENT_TYPE_SSE || mediaType == "*/*" || mediaType == "text/*"
        }
    }

    private suspend fun handleDelete(call: ApplicationCall, server: McpServerCore) {
        setProtocolVersionHeader(call, MCP_PROTOCOL_VERSION)
        val sessionId = call.request.header(SESSION_HEADER)
        if (sessionId != null) {
            log.info("[MCP] Terminating session: $sessionId")
            server.sessionManager.removeSession(sessionId)
            call.respond(HttpStatusCode.NoContent)
        } else {
            log.warn("[MCP] DELETE request without session ID")
            call.respond(HttpStatusCode.BadRequest, "Missing session ID")
        }
    }
}
