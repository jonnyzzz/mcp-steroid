/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.Urls
import com.jonnyzzz.mcpSteroid.DevrigEndpointInfo
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.IntelliJWebServerInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.ide.BuiltInServerManager
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes the MCP server URL marker to the managed MCP Steroid marker directory.
 *
 * The marker file is `~/.mcp-steroid/markers/<pid>.mcp-steroid`, a JSON
 * document defined by [PidMarker]. External monitors, including devrig and the
 * Claude-plugin bootstrap, read it to discover where this IDE's MCP server is
 * reachable. The `.idea/mcp-steroid.md` per-project description is written
 * separately by [IdeaDescriptionWriter].
 *
 * The marker is refreshed on a [MARKER_HEARTBEAT] so it self-heals: the file is
 * only written at IDE startup / project-open, but `~/.mcp-steroid` can be wiped
 * and re-created mid-session (the devrig installer re-creates the tree; a user
 * may `rm -rf ~/.mcp-steroid`). Without the heartbeat the marker would stay gone
 * until the next IDE restart, and a running-IDE fast path (bootstrap Tier 1)
 * would never find this IDE. The heartbeat re-creates it within [MARKER_HEARTBEAT].
 */
@Service(Service.Level.APP)
class ServerUrlWriter(parentScope: CoroutineScope) : Disposable {
    private val log = thisLogger()
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var markerFile: Path? = null
    private val heartbeatStarted = AtomicBoolean(false)

    /**
     * Write the MCP server URL to the user's home as a JSON marker file, then keep it alive with a
     * periodic re-write so a wiped/re-created `~/.mcp-steroid` doesn't leave this IDE undiscoverable
     * until the next restart. Idempotent: safe to call from every startup path — the heartbeat starts
     * exactly once.
     *
     * The marker carries the IntelliJ-hosted MCP server's URL and bearer token so external monitors can
     * address the server without parsing the URL or guessing the token.
     *
     * @param serverUrl The MCP server URL.
     */
    fun writeServerUrlToUserHome(serverUrl: String) {
        writeMarkerFile(serverUrl)
        if (heartbeatStarted.compareAndSet(false, true)) {
            scope.launch {
                while (isActive) {
                    delay(markerHeartbeatMs)
                    writeMarkerFile(serverUrl)
                }
            }
        }
    }

    /**
     * Build and write the marker file for the current pid. Returns without throwing on any IO error
     * (logged). Logs at INFO only when it actually (re-)creates a missing file, so the heartbeat stays
     * quiet in the steady state.
     */
    private fun writeMarkerFile(serverUrl: String) {
        val userHome = Path.of(System.getProperty("user.home"))
        val pid = ProcessHandle.current().pid()
        val markerDir = PidMarker.markerDirectory(userHome)
        val file = markerDir.resolve(PidMarker.markerFileNameFor(pid))

        val bridgeHeaders = bearerHeaders(NpxBridgeService.getInstance().token)
        // The devrig bridge lives on the same Ktor server as `/mcp`, at DEVRIG_RPC_PATH_PREFIX. Advertise
        // the FULL base URL so devrig connects by reading it from the marker — never by stripping `/mcp`
        // or knowing the path prefix. This is split from mcpSteroidServer (the MCP-client endpoint) on
        // purpose: devrig uses devrigEndpoint only.
        val ktorBaseUrl = serverUrl.trimEnd('/').removeSuffix("/mcp")
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = serverUrl,
                headers = bridgeHeaders,
            ),
            devrigEndpoint = DevrigEndpointInfo(
                rpcBaseUrl = "$ktorBaseUrl$DEVRIG_RPC_PATH_PREFIX",
                headers = bridgeHeaders,
            ),
            ide = IdeInfo.ofApplication(),
            plugin = PluginInfo.ofCurrentPlugin(),
            createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            intellijWebServer = buildIntelliJWebServerInfo(),
            intellijMcpServer = IntelliJMcpServerProbe.getInstanceOrNull()?.probe(),
        )
        val content = PidMarkerJson.encode(marker)

        try {
            val wasMissing = !Files.exists(file)
            Files.createDirectories(markerDir)
            cleanupStaleMarkers(markerDir)
            Files.writeString(file, content)
            markerFile = file
            if (wasMissing) {
                log.info("MCP Steroid marker (re)created (pid=$pid): $file")
            } else {
                log.debug("MCP Steroid marker refreshed (pid=$pid)")
            }
        } catch (e: Exception) {
            log.warn("Failed to write MCP Steroid marker file", e)
        }
    }

    private fun buildIntelliJWebServerInfo(): IntelliJWebServerInfo? {
        return try {
            val rawManager = BuiltInServerManager.getInstance()
            val manager = if (ApplicationManager.getApplication().isDispatchThread) {
                rawManager
            } else {
                rawManager.waitForStart()
            }
            if (manager.serverDisposable == null) return null

            val host = "127.0.0.1"
            val authority = "$host:${manager.port}"
            val baseUrl = Urls.newHttpUrl(authority, "", null).toExternalForm()
            val aboutUrl = Urls.newHttpUrl(authority, "/api/about", null)
            val authorizedAboutUrl = manager.addAuthToken(aboutUrl)
            val token = extractQueryParameter(authorizedAboutUrl.parameters, "_ijt")

            IntelliJWebServerInfo(
                enabled = true,
                host = host,
                port = manager.port,
                baseUrl = baseUrl,
                aboutUrl = authorizedAboutUrl.toExternalForm(),
                headers = ijtHeaders(token.orEmpty()),
            )
        } catch (e: Exception) {
            log.warn("Failed to query IntelliJ's built-in web server", e)
            null
        }
    }

    private fun extractQueryParameter(parameters: String?, name: String): String? {
        val query = parameters?.removePrefix("?") ?: return null
        return query.split('&')
            .asSequence()
            .mapNotNull { pair ->
                val key = pair.substringBefore('=', missingDelimiterValue = pair)
                if (key != name) return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
            .firstOrNull()
    }

    /** Remove marker files left behind by IDE processes that no longer exist. */
    private fun cleanupStaleMarkers(markerDir: Path) {
        if (!Files.isDirectory(markerDir)) return
        try {
            Files.list(markerDir).use { stream ->
                stream.filter { file ->
                    val pid = PidMarker.pidFromFileName(file.fileName.toString())
                    pid != null && Files.isRegularFile(file) && !ProcessHandle.of(pid).isPresent
                }.forEach { staleFile ->
                    try {
                        Files.deleteIfExists(staleFile)
                        log.info("Removed stale MCP marker file: $staleFile")
                    } catch (e: Exception) {
                        log.warn("Failed to delete stale MCP marker file: $staleFile", e)
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to cleanup stale marker files in $markerDir", e)
        }
    }

    override fun dispose() {
        // The injected scope is cancelled by the platform on dispose, stopping the heartbeat.
        // Remove this IDE's marker so it doesn't linger for a dead pid.
        try {
            markerFile?.let { Files.deleteIfExists(it) }
        } catch (e: Exception) {
            log.warn("Failed to clean up MCP Steroid marker file on shutdown", e)
        }
    }

    companion object {
        /**
         * How often the marker is re-written so a wiped `~/.mcp-steroid` self-heals.
         * A `var` (not const) so tests can lower it to drive the heartbeat quickly.
         */
        internal var markerHeartbeatMs: Long = 10_000L

        fun getInstance(): ServerUrlWriter = service()
    }
}

private fun bearerHeaders(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $token")

private fun ijtHeaders(token: String): Map<String, String> =
    mapOf("x-ijt" to token)
