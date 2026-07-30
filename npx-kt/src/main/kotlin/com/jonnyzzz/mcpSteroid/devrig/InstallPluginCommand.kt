/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.monitor.aboutJson
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory

/** The MCP Steroid plugin id, as declared in `ij-plugin/.../META-INF/plugin.xml`. */
const val MCP_STEROID_PLUGIN_ID = "com.jonnyzzz.mcp-steroid"

/**
 * The one-shot command that installs (or updates) MCP Steroid into every running JetBrains IDE over
 * REST — each IDE then shows its own native "Choose Plugins to Install or Enable" dialog. Promoted from
 * every CLI listing that surfaces an IDE without a compatible plugin (`devrig backend`, `devrig project`).
 */
const val INSTALL_PLUGIN_COMMAND = "devrig install plugin"

/**
 * Origin sent on every `/api/installPlugin` request. The built-in server trusts any request whose
 * Origin host is localhost (`RestService.isLocalhost`), so this bypasses the *host-trust* dialog while
 * leaving the plugin-install confirmation modal (`installAndEnable(..., showDialog=true)`) intact — that
 * modal is the consent gate we deliberately keep.
 */
private const val LOCALHOST_ORIGIN = "http://localhost"

/** What happened (or would happen) for one IDE during `devrig install plugin`. */
enum class PluginInstallOutcome {
    /** `install`: the IDE accepted the request and is (or will be) showing its native install dialog. */
    REQUESTED,
    /** `--check`: compatible; a real run would ask this IDE to open its install dialog. */
    WOULD_REQUEST,
    /** The IDE already has the MCP Steroid plugin (matched via a `~/.mcp-steroid` marker). */
    ALREADY_INSTALLED,
    /** Marketplace has no build of the plugin compatible with this IDE. */
    INCOMPATIBLE,
    /** The IDE did not answer the compatibility probe (not reachable / not the expected server). */
    UNREACHABLE,
    /** The IDE was reached but rejected the install request. */
    FAILED,
}

data class PluginInstallReport(
    val ide: DiscoveredIdeByPort,
    val outcome: PluginInstallOutcome,
)

/**
 * REST seam for the plugin-install endpoint. Extracted so the orchestrator can be unit-tested with a
 * fake — the real implementation ([KtorPluginRestClient]) needs a live [HttpClient].
 */
interface PluginRestClient {
    /**
     * `action=checkCompatibility` — a pure Marketplace query (no dialog, no install). Returns whether
     * a compatible build exists, or `null` when the IDE could not be reached / did not answer with the
     * expected JSON.
     */
    suspend fun checkCompatibility(baseUrl: String, pluginId: String): Boolean?

    /**
     * `action=install` — asks the IDE to open its native install confirmation dialog. Returns `true`
     * when the IDE accepted the request (HTTP OK); the actual install still requires the user to approve
     * the dialog inside the IDE.
     */
    suspend fun requestInstall(baseUrl: String, pluginId: String): Boolean
}

class KtorPluginRestClient(private val httpClient: HttpClient) : PluginRestClient {
    private val log = LoggerFactory.getLogger(KtorPluginRestClient::class.java)

    override suspend fun checkCompatibility(baseUrl: String, pluginId: String): Boolean? {
        // A not-running / refused IDE is a normal "unreachable" outcome, not an error: log at debug and
        // return null (never swallow silently) so the caller reports UNREACHABLE and moves on.
        val body = try {
            val response = httpClient.get("${baseUrl.trimEnd('/')}/api/installPlugin") {
                parameter("pluginId", pluginId)
                parameter("action", "checkCompatibility")
                accept(ContentType.Application.Json)
                header(HttpHeaders.Origin, LOCALHOST_ORIGIN)
            }
            if (!response.status.isSuccess()) {
                log.debug("checkCompatibility at {} returned HTTP {}", baseUrl, response.status.value)
                return null
            }
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.debug("checkCompatibility at {} failed: {}", baseUrl, e.message)
            return null
        }
        val obj = try {
            aboutJson.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            log.debug("checkCompatibility at {} returned non-JSON body: {}", baseUrl, e.message)
            return null
        }
        // Single-id form collapses to {"compatible": <bool>}; the multi-id form keys by plugin id. Accept
        // either so a future endpoint tweak doesn't silently read as "incompatible".
        val flag = obj["compatible"] ?: obj[pluginId]
        return (flag as? JsonPrimitive)?.booleanOrNull
    }

    override suspend fun requestInstall(baseUrl: String, pluginId: String): Boolean {
        val response = httpClient.get("${baseUrl.trimEnd('/')}/api/installPlugin") {
            parameter("pluginId", pluginId)
            parameter("action", "install")
            header(HttpHeaders.Origin, LOCALHOST_ORIGIN)
        }
        return response.status.isSuccess()
    }
}

/**
 * `devrig install plugin` — install the MCP Steroid plugin into every locally-running JetBrains IDE that
 * doesn't already have it, by driving each IDE's built-in REST `/api/installPlugin` endpoint (which opens
 * the IDE's own native install dialog). Discovery reuses the port scan behind `devrig backend provision`
 * (widened to cover sandbox / unit-test IDEs at 64463), cross-referenced with the `~/.mcp-steroid` markers
 * so IDEs that already have the plugin are skipped.
 *
 * Non-interactive: devrig itself never prompts. The IDE's modal is the only confirmation, and it is shown
 * by the IDE, not devrig — so this is safe to call from the fully non-interactive `devrig install devrig`
 * path (see [tryInstallPluginIntoRunningIdesQuietly]).
 */
fun DevrigServices.runInstallPluginCommand(command: DevrigCommand.DevrigCommandInstallPlugin): Int {
    val markers = scanMarkersOnce()
    runBlocking(Dispatchers.IO) {
        val targets = detectProvisionTargets(portDiscovery)
        installPluginIntoRunningIdes(
            out = mcpStdout,
            err = System.err,
            check = command.check,
            pluginId = MCP_STEROID_PLUGIN_ID,
            targets = targets,
            markers = markers,
            client = KtorPluginRestClient(commandHttpClient),
        )
    }
    // Best-effort by design: per-IDE status is printed above, and the real install completes only when the
    // user approves each IDE's dialog. A blanket exit 0 keeps the command safe to chain from `install devrig`.
    return 0
}

/**
 * Best-effort plugin install into running IDEs, called from `devrig install devrig`. Silent when no IDE is
 * running; never throws (any failure is logged to stderr and swallowed) so it can never fail the bootstrap
 * install, and never reads stdin.
 */
fun DevrigServices.tryInstallPluginIntoRunningIdesQuietly() {
    try {
        val markers = scanMarkersOnce()
        runBlocking(Dispatchers.IO) {
            val targets = detectProvisionTargets(portDiscovery)
            // Nothing running → say nothing. The bootstrap installer output stays quiet unless there is an
            // IDE we can actually act on.
            if (targets.isEmpty()) return@runBlocking
            mcpStdout.println()
            installPluginIntoRunningIdes(
                out = mcpStdout,
                err = System.err,
                check = false,
                pluginId = MCP_STEROID_PLUGIN_ID,
                targets = targets,
                markers = markers,
                client = KtorPluginRestClient(commandHttpClient),
            )
        }
    } catch (e: Exception) {
        System.err.println(
            "[mcp-steroid] installing the plugin into running IDEs was skipped: ${e.message ?: e::class.simpleName}",
        )
    }
}

/**
 * The pure orchestrator: narrates what is about to happen, skips already-provisioned IDEs, then for each
 * remaining IDE checks Marketplace compatibility and (unless [check]) fires the native install dialog.
 * Returns one [PluginInstallReport] per IDE considered. Prints to [out]; per-IDE failures go to [err].
 */
suspend fun installPluginIntoRunningIdes(
    out: PrintStream,
    err: PrintStream,
    check: Boolean,
    pluginId: String,
    targets: List<ProvisionTarget>,
    markers: List<DiscoveredIde>,
    client: PluginRestClient,
): List<PluginInstallReport> {
    val candidates = filterAlreadyProvisionedTargets(targets, markers)
    val candidateIds = candidates.map { it.id }.toSet()
    val already = targets.filter { it.id !in candidateIds }

    if (candidates.isEmpty()) {
        if (targets.isEmpty()) {
            out.println("No running JetBrains IDE answered on the scanned ports " +
                "(${describePortRanges(IntelliJPortDiscovery.DEFAULT_PORT_RANGES)}).")
            out.println("If an IDE is running, it may use a non-default port. Open it and retry, or install")
            out.println("from Settings -> Plugins -> Marketplace -> search \"MCP Steroid\" -> Install.")
            return emptyList()
        }
        out.println("Every running IDE already has the MCP Steroid plugin — nothing to install:")
        already.forEach { out.println("  - ${describeTarget(it)}") }
        return already.map { PluginInstallReport(it.ide, PluginInstallOutcome.ALREADY_INSTALLED) }
    }

    printPreamble(out, check)

    if (already.isNotEmpty()) {
        out.println("Already have the MCP Steroid plugin (skipped):")
        already.forEach { out.println("  - ${describeTarget(it)}") }
        out.println()
    }

    out.println(
        if (check) "IDEs that could receive the plugin:"
        else "Requesting the install dialog in ${candidates.size} IDE(s):",
    )
    val reports = candidates.map { target -> processTarget(out, err, check, pluginId, target, client) }
    out.println()

    if (check) {
        val ready = reports.count { it.outcome == PluginInstallOutcome.WOULD_REQUEST }
        out.println(
            if (ready > 0) "$ready IDE(s) would be asked to open the install dialog. " +
                "Run 'devrig install plugin' (no --check) to do it."
            else "No IDE is ready for an automatic install right now.",
        )
    } else {
        val requested = reports.count { it.outcome == PluginInstallOutcome.REQUESTED }
        if (requested > 0) {
            out.println("Asked $requested IDE(s) to install MCP Steroid.")
            out.println("Each IDE now shows its OWN \"Choose Plugins to Install or Enable\" dialog — switch to")
            out.println("the IDE window, click OK to confirm, and restart the IDE if it asks. devrig never")
            out.println("installs silently.")
        } else {
            out.println("No install dialog could be opened. See the per-IDE notes above.")
        }
    }

    return reports + already.map { PluginInstallReport(it.ide, PluginInstallOutcome.ALREADY_INSTALLED) }
}

private suspend fun processTarget(
    out: PrintStream,
    err: PrintStream,
    check: Boolean,
    pluginId: String,
    target: ProvisionTarget,
    client: PluginRestClient,
): PluginInstallReport {
    val label = describeTarget(target)
    val compatible = try {
        client.checkCompatibility(target.ide.baseUrl, pluginId)
    } catch (e: Exception) {
        err.println("[mcp-steroid] $label: compatibility check failed: ${e.message ?: e::class.simpleName}")
        null
    }
    return when (compatible) {
        null -> {
            out.println("  - $label: could not reach the IDE — skipped.")
            PluginInstallReport(target.ide, PluginInstallOutcome.UNREACHABLE)
        }
        false -> {
            out.println("  - $label: Marketplace has no compatible MCP Steroid build for this IDE — skipped.")
            PluginInstallReport(target.ide, PluginInstallOutcome.INCOMPATIBLE)
        }
        true -> {
            if (check) {
                out.println("  - $label: compatible — would open the IDE's install dialog.")
                return PluginInstallReport(target.ide, PluginInstallOutcome.WOULD_REQUEST)
            }
            out.println("  - $label: compatible -> asking the IDE to open its install dialog now…")
            val accepted = try {
                client.requestInstall(target.ide.baseUrl, pluginId)
            } catch (e: Exception) {
                err.println("[mcp-steroid] $label: install request failed: ${e.message ?: e::class.simpleName}")
                false
            }
            if (accepted) {
                out.println("      dialog requested — approve it in the IDE window.")
                PluginInstallReport(target.ide, PluginInstallOutcome.REQUESTED)
            } else {
                out.println("      the IDE did not accept the request — skipped.")
                PluginInstallReport(target.ide, PluginInstallOutcome.FAILED)
            }
        }
    }
}

private fun printPreamble(out: PrintStream, check: Boolean) {
    if (check) {
        out.println("Checking which running IDEs could receive the MCP Steroid plugin.")
        out.println("Read-only: no install dialog is shown — devrig only asks each IDE / Marketplace")
        out.println("about compatibility.")
        out.println()
        return
    }
    out.println("Installing the MCP Steroid plugin into your running JetBrains IDE(s).")
    out.println()
    out.println("What will happen (this is NOT a silent install):")
    out.println("  - For each compatible IDE, devrig asks it — over that IDE's own local REST server —")
    out.println("    to install the plugin.")
    out.println("  - The IDE then shows its OWN native plugin dialog — the standard JetBrains")
    out.println("    \"Choose Plugins to Install or Enable\" window, exactly like installing from")
    out.println("    Marketplace. Switch to the IDE window to see it, then click OK to confirm.")
    out.println("  - Nothing is installed until you approve that dialog. Restart the IDE if it asks.")
    out.println("  - devrig never installs silently and never restarts your IDE for you.")
    out.println()
}

private fun describeTarget(target: ProvisionTarget): String =
    "${portBackendDisplayName(target.ide)} (${portBackendLocatorLabel(target.ide)})"

private fun describePortRanges(ranges: List<IntRange>): String =
    ranges.joinToString(", ") { "${it.first}-${it.last}" }
