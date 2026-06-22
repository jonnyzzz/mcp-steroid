/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpMethods
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ProgressParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

@Service(Service.Level.APP)
class NpxBridgeService {
    private val log = thisLogger()
    private val seqCounter = AtomicLong(0)

    val instanceId: String = "npx-${UUID.randomUUID()}"
    val token: String = UUID.randomUUID().toString().replace("-", "")
    val schemaVersion: String = "1"

    fun isAuthorized(authorizationHeader: String?): Boolean {
        if (authorizationHeader.isNullOrBlank()) return false
        if (!authorizationHeader.startsWith("Bearer ")) return false
        return authorizationHeader.removePrefix("Bearer ").trim() == token
    }

    private fun nextSeq(): Long = seqCounter.incrementAndGet()

    private fun nowIso(): String = java.time.Instant.now().toString()

    /**
     * The `/windows` WIRE response (devrig<->IDE) — built from the raw [IdeWindowsCollector] snapshot
     * (pristine [WindowInfo]/[ProgressTaskInfo] + this IDE's own pid), NOT from the MCP
     * [ListWindowsResponse] (which is backend-attributed and never crosses the wire).
     */
    suspend fun buildWindows(mcpUrl: String): NpxBridgeWindowsResponse {
        val seq = nextSeq()
        val snapshot = service<IdeWindowsCollector>().collect()
        return NpxBridgeWindowsResponse(
            windows = snapshot.windows,
            backgroundTasks = snapshot.backgroundTasks,
            pid = ProcessHandle.current().pid(),
            mcpUrl = mcpUrl,
            instanceId = instanceId,
            seq = seq,
            schemaVersion = schemaVersion,
            updatedAt = nowIso()
        )
    }

    suspend fun streamToolCall(
        serverCore: McpServerCore,
        request: NpxBridgeToolCallRequest,
        emit: suspend (JsonObject) -> Unit
    ) {
        val session = serverCore.sessionManager.createSession()
        val progressToken = "npx-${UUID.randomUUID()}"

        val argsWithMeta = injectProgressMeta(request.arguments, progressToken)
        val rawParams = buildJsonObject {
            put("name", request.name)
            put("arguments", argsWithMeta)
        }
        val params = ToolCallParams(
            name = request.name,
            arguments = argsWithMeta,
            rawArguments = rawParams
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val emitMutex = Mutex()
        suspend fun emitEvent(event: JsonObject) {
            emitMutex.withLock {
                emit(event)
            }
        }
        lateinit var progressJob: Job
        var sessionRemoved = false
        suspend fun closeSessionAndDrainProgress() {
            if (!sessionRemoved) {
                serverCore.sessionManager.removeSession(session.id)
                sessionRemoved = true
            }
            progressJob.join()
        }
        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(NPX_STREAM_KEEPALIVE_INTERVAL_SECONDS.seconds)
                emitEvent(
                    buildJsonObject {
                        put("type", "heartbeat")
                        put("instanceId", instanceId)
                        put("seq", seqCounter.incrementAndGet())
                        put("updatedAt", nowIso())
                    }
                )
            }
        }
        progressJob = scope.launch {
            session.notifications().collect { notification ->
                if (notification.method != McpMethods.PROGRESS) return@collect
                val paramsElement = notification.params ?: return@collect
                val progress = runCatching { McpJson.decodeFromJsonElement(ProgressParams.serializer(), paramsElement) }.getOrNull()
                    ?: return@collect
                if (progress.progressToken.jsonPrimitive.content != progressToken) return@collect

                emitEvent(
                    buildJsonObject {
                        put("type", "progress")
                        put("instanceId", instanceId)
                        put("seq", seqCounter.incrementAndGet())
                        put("progress", progress.progress)
                        progress.total?.let { put("total", it) }
                        progress.message?.let { put("message", it) }
                        put("updatedAt", nowIso())
                    }
                )
            }
        }

        try {
            emitEvent(
                buildJsonObject {
                    put("type", "progress")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("message", "Tool call started: ${request.name}")
                    put("progress", 0.0)
                    put("updatedAt", nowIso())
                }
            )

            val result = serverCore.toolRegistry.callTool(params, session)
            heartbeatJob.cancel()
            closeSessionAndDrainProgress()
            emitEvent(
                buildJsonObject {
                    put("type", "result")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("updatedAt", nowIso())
                    put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                }
            )
        } catch (e: Exception) {
            log.warn("Failed to stream bridge tool call '${request.name}'", e)
            heartbeatJob.cancel()
            closeSessionAndDrainProgress()
            emitEvent(
                buildJsonObject {
                    put("type", "error")
                    put("instanceId", instanceId)
                    put("seq", seqCounter.incrementAndGet())
                    put("updatedAt", nowIso())
                    put("message", e.message ?: "Tool call failed")
                }
            )
        } finally {
            heartbeatJob.cancel()
            progressJob.cancel()
            scope.cancel()
            if (!sessionRemoved) {
                serverCore.sessionManager.removeSession(session.id)
            }
        }
    }

    private fun injectProgressMeta(arguments: JsonObject?, progressToken: String): JsonObject {
        val args = arguments ?: buildJsonObject { }
        val existingMeta = args["_meta"]?.jsonObject
        return buildJsonObject {
            for ((key, value) in args.entries) {
                if (key == "_meta") continue
                put(key, value)
            }
            putJsonObject("_meta") {
                if (existingMeta != null) {
                    for ((key, value) in existingMeta.entries) {
                        put(key, value)
                    }
                }
                put("progressToken", progressToken)
            }
        }
    }

    companion object {
        fun getInstance(): NpxBridgeService = service()
    }
}

