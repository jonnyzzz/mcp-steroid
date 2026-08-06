/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.Urls
import com.jonnyzzz.mcpSteroid.DevrigEndpointInfo
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.IntelliJWebServerInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginDescriptorProvider
import com.jonnyzzz.mcpSteroid.PluginInfo
import org.jetbrains.ide.BuiltInServerManager
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Writes the MCP server URL marker to the managed MCP Steroid marker directory.
 *
 * The marker file is `~/.mcp-steroid/markers/<pid>.mcp-steroid`, a JSON
 * document defined by [PidMarker]. External monitors, including devrig, read
 * it to discover where this IDE's MCP server is reachable. The `.idea/mcp-steroid.md` per-project description is written
 * separately by [IdeaDescriptionWriter].
 */
@Service(Service.Level.APP)
class ServerUrlWriter(
    private val remoteDevelopmentBackend: Boolean,
) : Disposable {
    constructor() : this(isRemoteDevBackend())

    private val log = thisLogger()
    private var markerFile: Path? = null

    /**
     * Write the MCP server URL to the user's home as a JSON marker file.
     * Stale marker files from dead processes are cleaned up first.
     *
     * The marker carries the IntelliJ-hosted MCP server's port (matches
     * [SteroidsMcpServer.port]) and bearer auth token (matches
     * [NpxBridgeService.token]) so external monitors can address the
     * server without parsing the URL or guessing the token.
     *
     * @param serverUrl The MCP server URL.
     */
    fun writeServerUrlToUserHome(serverUrl: String) {
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
                pluginPath = pluginInstallPath(),
            ),
            devrigEndpoint = DevrigEndpointInfo(
                rpcBaseUrl = "$ktorBaseUrl$DEVRIG_RPC_PATH_PREFIX",
                headers = bridgeHeaders,
            ),
            ide = IdeInfo.ofApplication(),
            plugin = PluginInfo.ofCurrentPlugin(),
            createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            ideHome = PathManager.getHomePath(),
            remoteDevelopmentBackend = remoteDevelopmentBackend,
            intellijWebServer = buildIntelliJWebServerInfo(),
            intellijMcpServer = IntelliJMcpServerProbe.getInstanceOrNull()?.probe(),
        )
        val content = PidMarkerJson.encode(marker)
        log.info("Writing MCP Steroid marker (pid=$pid)\n$content")

        try {
            Files.createDirectories(markerDir)
            cleanupStaleMarkers(markerDir)
            Files.writeString(file, content)
            markerFile = file
            log.info("MCP Steroid marker file created: $file")
        } catch (e: Exception) {
            log.warn("Failed to create MCP Steroid marker file", e)
        }

        Disposer.register(this) {
            try {
                markerFile?.let { Files.deleteIfExists(it) }
            } catch (e: Exception) {
                log.warn("Failed to clean up MCP Steroid marker file on shutdown", e)
            }
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

    override fun dispose() = Unit

    companion object {
        fun getInstance(): ServerUrlWriter = service()
    }
}

/**
 * Returns the absolute plugin install folder for the mcp-steroid plugin, or `null` if
 * it cannot be resolved (e.g. in a test environment where no descriptor is registered).
 * Null-safe: callers must never crash when this returns null — older markers omit the field.
 */
private val pluginInstallPathLogger = Logger.getInstance(ServerUrlWriter::class.java)

private fun pluginInstallPath(): String? = try {
    PluginDescriptorProvider.getInstance().descriptor.pluginPath?.toString()
} catch (e: Exception) {
    pluginInstallPathLogger.warn("could not resolve plugin install path (marker will omit pluginPath)", e)
    null
}

private fun bearerHeaders(token: String): Map<String, String> =
    mapOf("Authorization" to "Bearer $token")

private fun ijtHeaders(token: String): Map<String, String> =
    mapOf("x-ijt" to token)
