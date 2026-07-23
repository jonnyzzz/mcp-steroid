/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import java.io.PrintStream

/** Plugin id of the MCP Steroid IntelliJ plugin (matches plugin.xml `<id>`). */
const val MCP_STEROID_PLUGIN_ID = "com.jonnyzzz.mcp-steroid"

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

/** Canonical Marketplace one-click install deep link (browser fallback). */
const val MARKETPLACE_INSTALL_URL = "https://plugins.jetbrains.com/embeddable/install/$MCP_STEROID_PLUGIN_ID"

/** Custom plugin repository URL for the manual last-resort instructions. */
const val CUSTOM_REPO_URL = "https://mcp-steroid.jonnyzzz.com/updatePlugins.xml"

/** Outcome of a connect-ide attempt, for the caller to narrate/decide on. */
enum class ConnectIdeOutcome { ALREADY_CONNECTED, NO_IDE, OFFERED_VIA_HTTP, OFFERED_VIA_BROWSER, MANUAL_INSTRUCTIONS }

/**
 * Process exit code for a [ConnectIdeOutcome]: 2 when no IDE was found at all (nothing to act on yet —
 * callers like the onboarding hook should retry later without burning a one-shot offer), 1 when the
 * caller was left with manual instructions (an IDE was found but nothing could be offered
 * automatically), 0 otherwise.
 */
fun connectIdeExitCode(outcome: ConnectIdeOutcome): Int =
    when (outcome) {
        ConnectIdeOutcome.NO_IDE -> 2
        ConnectIdeOutcome.MANUAL_INSTRUCTIONS -> 1
        ConnectIdeOutcome.ALREADY_CONNECTED, ConnectIdeOutcome.OFFERED_VIA_HTTP, ConnectIdeOutcome.OFFERED_VIA_BROWSER -> 0
    }

enum class HostOs { MAC, WINDOWS, LINUX }

/** Map a `System.getProperty("os.name")` value to a HostOs (defaults to LINUX for unknown/unix). */
fun detectHostOs(osName: String): HostOs {
    val n = osName.lowercase()
    return when {
        n.contains("mac") || n.contains("darwin") -> HostOs.MAC
        n.contains("win") -> HostOs.WINDOWS
        else -> HostOs.LINUX
    }
}

/** Argv to open [url] in the OS default browser. Windows `start` needs an empty title arg first. */
fun browserOpenArgv(os: HostOs, url: String): List<String> = when (os) {
    HostOs.MAC -> listOf("open", url)
    HostOs.WINDOWS -> listOf("cmd", "/c", "start", "", url)
    HostOs.LINUX -> listOf("xdg-open", url)
}

/** Seam for opening a URL in the browser; returns false if the launch failed. */
fun interface BrowserLauncher {
    fun open(url: String): Boolean
}

/**
 * Offer to install the MCP Steroid plugin into a running JetBrains IDE.
 *
 * - If any IDE already advertises the plugin ([pluginMarkerCount] > 0), the bridge already works →
 *   [ConnectIdeOutcome.ALREADY_CONNECTED], nothing offered.
 * - Else, for each built-in-server IDE, try the in-IDE install dialog via `installPlugin` ("B"). If any
 *   returns 2xx → [OFFERED_VIA_HTTP].
 * - If B is rejected for all candidates, open the Marketplace install page in the browser ("A") →
 *   [OFFERED_VIA_BROWSER]; the browser carries the trusted jetbrains origin, so the IDE shows its dialog.
 * - If the browser cannot be launched, print manual instructions ("text") → [MANUAL_INSTRUCTIONS].
 */
suspend fun runConnectIde(
    discovered: Set<DiscoveredIdeByPort>,
    pluginMarkerCount: Int,
    client: InstallPluginClient,
    browser: BrowserLauncher,
    out: PrintStream,
    err: PrintStream,
    pluginId: String = MCP_STEROID_PLUGIN_ID,
): ConnectIdeOutcome {
    if (pluginMarkerCount > 0) {
        out.println("A running JetBrains IDE already has the MCP Steroid plugin — devrig is connected.")
        return ConnectIdeOutcome.ALREADY_CONNECTED
    }
    val candidates = builtInServerCandidates(discovered)
    if (candidates.isEmpty()) {
        out.println("No running JetBrains IDE was found (probed built-in-server ports $BUILTIN_SERVER_PORTS).")
        return ConnectIdeOutcome.NO_IDE
    }

    var httpOffered = false
    for (ide in candidates) {
        val label = ide.productFullName ?: ide.productName ?: ide.baseUrl
        try {
            val compat = client.request(installPluginUrl(ide.baseUrl, "checkCompatibility", pluginId))
            if (compat.statusCode !in 200..299) {
                err.println("[devrig] $label: compatibility check returned HTTP ${compat.statusCode}; offering install anyway.")
            }
            val install = client.request(installPluginUrl(ide.baseUrl, "install", pluginId))
            if (install.statusCode in 200..299) {
                httpOffered = true
                out.println("Requested the MCP Steroid plugin install in $label (port ${ide.port}).")
            } else {
                err.println("[devrig] $label: install request returned HTTP ${install.statusCode}.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            err.println("[devrig] $label: install offer failed: ${e.message ?: e::class.simpleName}")
        }
    }
    if (httpOffered) {
        out.println()
        out.println(">>> Open your JetBrains IDE and approve the MCP Steroid plugin install dialog. <<<")
        return ConnectIdeOutcome.OFFERED_VIA_HTTP
    }

    // B rejected for all candidates → A: open the Marketplace install page in the browser.
    out.println("Opening the MCP Steroid plugin page in your browser to install it…")
    if (browser.open(MARKETPLACE_INSTALL_URL)) {
        out.println("Approve the install in the page (and the IDE dialog it triggers).")
        return ConnectIdeOutcome.OFFERED_VIA_BROWSER
    }

    // A failed → text last-resort.
    out.println("Could not offer the install automatically.")
    out.println("Install \"MCP Steroid\" via Settings > Plugins (Marketplace),")
    out.println("or add this plugin repository: $CUSTOM_REPO_URL")
    return ConnectIdeOutcome.MANUAL_INSTRUCTIONS
}
