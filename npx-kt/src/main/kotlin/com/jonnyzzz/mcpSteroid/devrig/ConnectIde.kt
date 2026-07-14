/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import java.io.PrintStream

/** Plugin id of the MCP Steroid IntelliJ plugin (matches plugin.xml `<id>`). */
const val MCP_STEROID_PLUGIN_ID = "com.jonnyzzz.mcpSteroid"

/**
 * The IntelliJ Platform built-in Netty server picks the first free port starting at 63342 (19 fallbacks).
 * `/api/installPlugin` lives on THIS server — not on the MCP plugin's 64342 range — so only these ports
 * are valid `installPlugin` targets.
 */
val BUILTIN_SERVER_PORTS: IntRange = 63342..63361

/** Discovered IDEs answering on a built-in-server port, sorted by port for deterministic ordering. */
fun builtInServerCandidates(discovered: Set<DiscoveredIdeByPort>): List<DiscoveredIdeByPort> =
    discovered.filter { it.port in BUILTIN_SERVER_PORTS }.sortedBy { it.port }

/** Build an `/api/installPlugin` URL for [baseUrl] with the given [action] and [pluginId]. */
fun installPluginUrl(baseUrl: String, action: String, pluginId: String): String =
    "${baseUrl.trimEnd('/')}/api/installPlugin?action=$action&pluginId=$pluginId"

data class InstallPluginResponse(val statusCode: Int, val body: String)

/** HTTP seam so tests inject a fake instead of a live Ktor client. */
fun interface InstallPluginClient {
    suspend fun request(url: String): InstallPluginResponse
}

/**
 * For every reachable IDE on a built-in-server port, ask its `installPlugin` endpoint to install the
 * MCP Steroid plugin. The endpoint forces a user confirmation dialog by design (ASK_CONFIRMATION), so
 * this only *offers* the install; the user approves it in the IDE. `checkCompatibility` is queried first
 * for a helpful log line. Returns 0 when at least one IDE was offered the install, 1 when none reachable.
 */
suspend fun runConnectIde(
    discovered: Set<DiscoveredIdeByPort>,
    client: InstallPluginClient,
    out: PrintStream,
    err: PrintStream,
    pluginId: String = MCP_STEROID_PLUGIN_ID,
): Int {
    val candidates = builtInServerCandidates(discovered)
    if (candidates.isEmpty()) {
        out.println("No running JetBrains IDE with a built-in server was found (probed ports $BUILTIN_SERVER_PORTS).")
        out.println("Start your IDE and re-run 'devrig connect ide'.")
        return 1
    }
    for (ide in candidates) {
        val label = ide.productFullName ?: ide.productName ?: ide.baseUrl
        val compat = client.request(installPluginUrl(ide.baseUrl, "checkCompatibility", pluginId))
        if (compat.statusCode !in 200..299) {
            err.println("[devrig] $label: compatibility check returned HTTP ${compat.statusCode}; offering install anyway.")
        }
        client.request(installPluginUrl(ide.baseUrl, "install", pluginId))
        out.println("Requested the MCP Steroid plugin install in $label (port ${ide.port}).")
    }
    out.println()
    out.println(">>> Open your JetBrains IDE and approve the MCP Steroid plugin install dialog. <<<")
    return 0
}
