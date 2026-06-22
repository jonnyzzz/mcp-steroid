/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Instant.now
import java.time.format.DateTimeFormatter.ISO_INSTANT
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private fun JsonObjectBuilder.buildResponses(payload: ListProjectsResponse) {
    putJsonArray("projects") {
        payload.projects.forEach { project ->
            addJsonObject {
                put("name", project.name)
                put("path", project.path)
                put("project_name", project.projectName)
                put("backend_name", project.backendName)
            }
        }
    }
}

fun Route.installNpxBridgeRoutes(
    serverCoreProvider: () -> McpServerCore,
    mcpUrlProvider: () -> String
) {
    // Only the three endpoints devrig actually uses are exposed. The former GET /metadata,
    // /server-metadata, /products, /projects, /summary, /resources, /resources/read and the
    // non-streaming POST /tools/call were unused (no devrig caller) and were removed.
    route(DEVRIG_RPC_PATH_PREFIX) {
        post("/projects/stream") {
            if (!call.requireNpxBridgeAuthorization()) return@post
            val payload = service<ListProjectsToolHandler>().collectListProjectsResponse()
            val response = buildJsonObject {
                put("type", "snapshot")
                put("seq", "System.currentTimeMillis()")
                put("sentAt", ISO_INSTANT.format(now()))
                put("instanceId", "ide-not-used")
                put("pid", ProcessHandle.current().pid())
                buildResponses(payload)
            }

            call.respondTextWriter(contentType = NDJSON_CONTENT_TYPE) {
                write(NpxStreamJson.encodeJsonObject(response))
                write("\n")
                flush()
            }
        }

        get("projects") {
            if (!call.requireNpxBridgeAuthorization()) return@get
            //TODO: use specific payload
            val payload = service<ListProjectsToolHandler>().collectListProjectsResponse()
            call.respondJson {
                buildResponses(payload)
            }
        }

        get("/windows") {
            if (!call.requireNpxBridgeAuthorization()) return@get
            val bridge = NpxBridgeService.getInstance()
            //TODO: rework data objects
            val payload = bridge.buildWindows(mcpUrlProvider())
            call.respondJson(payload, NpxBridgeWindowsResponse.serializer())
        }

        post("/tools/call/stream") {
            if (!call.requireNpxBridgeAuthorization()) return@post
            val request = call.parseToolCallRequestOrRespondBadRequest() ?: return@post
            val bridge = NpxBridgeService.getInstance()
            call.respondTextWriter(contentType = NDJSON_CONTENT_TYPE) {
                bridge.streamToolCall(serverCoreProvider(), request) { event ->
                    write(NpxStreamJson.encodeObject(event))
                    write("\n")
                    flush()
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireNpxBridgeAuthorization(): Boolean {
    val authorizationHeader = request.headers[HttpHeaders.Authorization]
    if (NpxBridgeService.getInstance().isAuthorized(authorizationHeader)) return true
    respond(HttpStatusCode.Unauthorized, "Missing or invalid npx bridge token")
    return false
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    payload: T,
    serializer: KSerializer<T>
) {
    respondText(
        text = McpJson.encodeToString(serializer, payload),
        contentType = ContentType.Application.Json
    )
}

private suspend fun ApplicationCall.respondJson(
    payload: JsonObjectBuilder.() -> Unit,
) {
    respondText(
        text = NpxStreamJson.encodeJsonObject(buildJsonObject(payload)),
        contentType = ContentType.Application.Json
    )
}

private suspend fun ApplicationCall.parseToolCallRequestOrRespondBadRequest(): NpxBridgeToolCallRequest? {
    val body = receiveText()
    return try {
        McpJson.decodeFromString(NpxBridgeToolCallRequest.serializer(), body)
    } catch (e: Exception) {
        respond(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
        null
    }
}

private val NDJSON_CONTENT_TYPE: ContentType = ContentType.parse(NPX_NDJSON_MIME_TYPE)
