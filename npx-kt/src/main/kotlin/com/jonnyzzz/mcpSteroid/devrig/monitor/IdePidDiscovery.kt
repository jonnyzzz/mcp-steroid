/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import org.slf4j.LoggerFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One IDE instance discovered through a `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON marker.
 * Equality is keyed off the marker contents that affect the connection
 * (pid + mcpUrl) so [IdePidDiscoveryService] can compare snapshots cheaply.
 */
data class DiscoveredIde(
    val backendName: String,

    @Deprecated("Use backend_name")
    val pid: Long,

    /**
     * Full base URL of the IDE's devrig bridge that support MCP Steroid communication
     */
    val rpcBaseUrl: String,

    /** Headers the bridge requires on every request, from [PidMarker.devrigEndpoint] (auth-scheme-agnostic). */
    val bridgeHeaders: Map<String, String>,

    /**
     * Information of the IDE instance of this backend
     */
    val ide: IdeInfo,

    /**
     * Information of the MCP Streroid plugin instance of this backend
     */
    val plugin: PluginInfo,
) {
    /** Stable, human-friendly identifier used in logs (`IntelliJ IDEA pid=12345`). */
    @Deprecated("Use backend_name")
    val label: String
        get() = "${ide.name} pid=$pid"
}

/**
 * Polling discovery layer for `~/.mcp-steroid/markers/<pid>.mcp-steroid` JSON markers.
 *
 * Exposes a typed [DiscoveredIde] flow and is the single source of truth for
 * the devrig monitor stack.
 */
class IdePidDiscoveryService(
    private val markersDir: Path,
    private val allowHosts: List<String>,
) {
    private val log = LoggerFactory.getLogger(IdePidDiscoveryService::class.java)

    fun stateSnapshot(): List<DiscoveredIde> {
        if (!Files.isDirectory(markersDir)) return emptyList()
        val files = try {
            Files.list(markersDir).use { it.toList() }
        } catch (e: Exception) {
            log.warn("Failed to list marker directory {}: {}", markersDir, e.message)
            return emptyList()
        }
        val out = mutableListOf<DiscoveredIde>()
        for (file in files) {
            if (!file.isRegularFile()) continue
            val pid = PidMarker.pidFromFileName(file.name) ?: continue
            if (!ProcessHandle.of(pid).isPresent) continue
            val text = try {
                file.readText()
            } catch (e: Exception) {
                log.warn("Failed to read marker file {}: {}", file, e.message)
                continue
            }
            // Cheap legacy-format sniff: a current marker is a JSON object, so the first
            // non-whitespace byte is '{'. Skip non-JSON files at DEBUG; only WARN on
            // text that LOOKS like JSON but fails to parse (truncated write, bit-flip, etc.).
            if (text.trimStart().firstOrNull() != '{') {
                log.debug("Skipping non-JSON marker file {}", file)
                continue
            }
            val marker = try {
                PidMarkerJson.decode(text)
            } catch (e: Exception) {
                log.warn("Skipping malformed marker file {}: {}", file, e.message)
                continue
            }
            // devrig speaks ONLY the bridge endpoint advertised by the marker — never the /mcp
            // endpoint. A marker without devrigEndpoint is from an IDE that doesn't expose the bridge
            // (e.g. an older plugin); skip it rather than fall back to the MCP server.
            val devrigEndpoint = marker.devrigEndpoint
            if (devrigEndpoint == null) {
                log.debug("Skipping marker {} — no devrigEndpoint (IDE not devrig-capable)", file)
                continue
            }
            if (!isAllowedHost(devrigEndpoint.rpcBaseUrl)) {
                log.debug("Skipping marker {} — host not in allowlist", file)
                continue
            }
            out += DiscoveredIde(
                backendName = backendNameForMarker(pid, marker.ide.build),

                pid = pid,

                rpcBaseUrl = devrigEndpoint.rpcBaseUrl,
                bridgeHeaders = devrigEndpoint.headers,
                ide = marker.ide,
                plugin = marker.plugin,
            )
        }
        return out
    }

    private fun isAllowedHost(url: String): Boolean = try {
        val host = URI(url).host ?: return false
        allowHosts.contains(host)
    } catch (_: Exception) {
        false
    }
}
