/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.server.NpxStreamJson
import com.jonnyzzz.mcpSteroid.thisLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Per-IDE monitoring state held by [IdeProjectMonitorService.stateSnapshot].
 * Re-emitted on every status / snapshot change.
 */
data class IdeMonitorState(
    val ide: DiscoveredIde,
    val projects: List<IdeProjectState> = emptyList(),
)

data class IdeProjectState(
    val name: String,
    val projectPath: String,
    val ideBackendName: String = "",
    val ideProjectName: String = name,
)

/**
 * Subscribes to every discovered IDE's `/npx/v1/projects/stream`, holds the
 * latest snapshot per IDE, and reconnects on connection break.
 */
class IdeProjectMonitorService(
    private val httpClient: HttpClient,
    private val discovery: IdePidDiscoveryService,
) {
    private val log = thisLogger()

    fun stateSnapshot(): List<IdeMonitorState> {
        return runBlocking(Dispatchers.IO.limitedParallelism(4)) {
            discovery
                .stateSnapshot()
                .map { ide ->
                    async {
                        //it should return state even if no project is open
                        val projects = collectProjects(ide)
                        IdeMonitorState(ide, projects)
                    }
                }
                .awaitAll()
        }
    }

    /** Single-attempt connect + drain. Returns when the stream ends or throws on error. */
    suspend fun collectProjects(ide: DiscoveredIde): List<IdeProjectState> {
        val result = withTimeoutOrNull(1.seconds) {
            try {
                collectProjectsImpl(ide)
            } catch (e: Throwable) {
                log.info("Failed to connect to IDE at ${ide.rpcBaseUrl}. ${e.message}", e)
                null
            }
        } ?: listOf()

        //let coroutine fail if it wants to
        yield()

        return result
    }

    private suspend fun collectProjectsImpl(ide: DiscoveredIde): List<IdeProjectState> {
        val url = ide.rpcBaseUrl + "/projects"

        return httpClient.prepareGet(url) {
            headers {
                append(HttpHeaders.ContentType, "application/json")
                for ((name, value) in ide.bridgeHeaders) {
                    append(name, value)
                }
            }
        }.execute { response ->
            if (!response.status.value.let { it in 200..299 }) {
                throw IllegalStateException("HTTP ${response.status.value} from ${ide.label}")
            }

            val response = response.bodyAsText()
            val payload = NpxStreamJson.decodeJsonObject(response)

            payload.getValue("projects").jsonArray.map { proj ->
                val name = proj.jsonObject.getValue("name").jsonPrimitive.content
                val path = proj.jsonObject.getValue("path").jsonPrimitive.content
                val ideBackendName = proj.jsonObject.getValue("backend_name").jsonPrimitive.content
                val ideProjectName = proj.jsonObject.getValue("project_name").jsonPrimitive.content

                IdeProjectState(
                    name = name,
                    projectPath = path,
                    ideBackendName = ideBackendName,
                    ideProjectName = ideProjectName
                )
            }
        }
    }
}
