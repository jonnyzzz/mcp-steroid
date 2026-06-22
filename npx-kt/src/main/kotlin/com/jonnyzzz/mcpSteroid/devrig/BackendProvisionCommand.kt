/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.io.PrintStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val SUGGESTED_DESTINATION_NOTE =
    "Suggested destination assumes the default plugins directory; user-customised paths require manual adjustment."

fun runBackendProvisionListCommand(
    out: PrintStream,
    json: Boolean,
    targets: suspend (HttpClient) -> List<ProvisionTarget> = { httpClient -> detectProvisionTargets(httpClient) },
    markers: () -> List<DiscoveredIde> = { scanMarkersOnce() },
    httpClientFactory: () -> HttpClient = ::createProvisionHttpClient,
    closeHttpClient: Boolean = true,
) {
    val (rawTargets, markerRows) = withProvisionHttpClient(httpClientFactory, closeHttpClient) { httpClient ->
        runBlocking(Dispatchers.IO) {
            targets(httpClient) to markers()
        }
    }
    val rows = filterAlreadyProvisionedTargets(rawTargets, markerRows)
    val discoveryNote = provisionDiscoveryNote(rawTargets, rows, markerRows)
    if (json) {
        renderBackendProvisionListJson(rows, out, discoveryNote)
    } else {
        renderBackendProvisionListText(rows, out, markerRows)
    }
}

fun runBackendProvisionCommand(
    out: PrintStream,
    command: DevrigCommand.DevrigCommandBackendProvision,
    provision: suspend (HttpClient) -> ProvisionResult = { httpClient ->
        val id = command.id
            ?: error("backend provision id is required")
        provisionBackend(id, httpClient)
    },
    markers: () -> List<DiscoveredIde> = { scanMarkersOnce() },
    httpClientFactory: () -> HttpClient = ::createProvisionHttpClient,
    closeHttpClient: Boolean = true,
): Int {
    val id = command.id ?: run {
        runBackendProvisionListCommand(
            out = out,
            json = command.json,
            markers = markers,
            httpClientFactory = httpClientFactory,
            closeHttpClient = closeHttpClient,
        )
        return 0
    }
    if (!isSupportedProvisionTargetId(id)) {
        return unknownArguments(
            listOf("backend", "provision", id),
            "Run `devrig backend provision` with no id to list valid backend ids.",
        )
    }
    if (command.json) {
        return runBackendActionJson(out, action = PROVISION_ACTION_ID, id = id) {
            val result = withProvisionHttpClient(httpClientFactory, closeHttpClient) { httpClient ->
                runBlocking(Dispatchers.IO) {
                    provision(httpClient)
                }
            }
            provisionResultJson(result)
        }
    }

    val result = withProvisionHttpClient(httpClientFactory, closeHttpClient) { httpClient ->
        runBlocking(Dispatchers.IO) {
            provision(httpClient)
        }
    }
    renderProvisionInstructionsText(result, out)
    return 0
}

fun DevrigServices.runBackendProvisionCommand(command: DevrigCommand.DevrigCommandBackendProvision): Int =
    runBackendProvisionCommand(
        out = mcpStdout,
        command = command,
        provision = { httpClient ->
            val id = command.id
                ?: error("backend provision id is required")
            backendProvisioner.provision(id, httpClient) {
                detectProvisionTargets(portDiscovery)
            }
        },
        markers = { scanMarkersOnce() },
        httpClientFactory = { commandHttpClient },
        closeHttpClient = false,
    )

fun isSupportedProvisionTargetId(raw: String): Boolean = Regex("""port-\d{1,5}""").matches(raw)

fun renderBackendProvisionListText(
    rows: List<ProvisionTarget>,
    out: PrintStream,
    markerRows: List<DiscoveredIde> = emptyList(),
) {
    if (rows.isEmpty()) {
        if (markerRows.isNotEmpty()) {
            out.println("All running IDEs already have MCP Steroid installed.")
            out.println("Run \"devrig backend\" to see them.")
        } else {
            out.println("No port-discovered IDEs are available.")
        }
        out.println()
        return
    }

    out.println("Port-discovered IDEs that can be provisioned:")
    out.println()
    val indexWidth = rows.size.toString().length + 2
    val idWidth = rows.maxOf { it.id.length }
    for ((index, row) in rows.withIndex()) {
        val indexLabel = "[${index + 1}]".padEnd(indexWidth)
        out.println("  $indexLabel ${row.id.padEnd(idWidth)}  ${portBackendDisplayName(row.ide)} (${portBackendLocatorLabel(row.ide)})")
        out.println("        run: ${row.command}")
    }
    out.println()
    out.println("Run:  devrig backend provision <id>")
    out.println()
}

fun renderBackendProvisionListJson(
    rows: List<ProvisionTarget>,
    out: PrintStream,
    discoveryNote: String? = null,
) {
    val payload = buildJsonObject {
        putToolJson()
        discoveryNote?.let { put("discoveryNote", it) }
        put("targets", buildJsonArray {
            for (row in rows) {
                add(provisionTargetJson(row))
            }
        })
    }
    out.println(backendPrettyJson.encodeToString(JsonObject.serializer(), payload))
}

fun filterAlreadyProvisionedTargets(
    targets: List<ProvisionTarget>,
    markerRows: List<DiscoveredIde>,
): List<ProvisionTarget> {
    val markerBuilds = markerRows
        .mapNotNull { normaliseBuildForDedup(it.ide.build) }
        .toSet()
    return targets.filter { target ->
        val build = normaliseBuildForDedup(target.ide.buildNumber)
        build == null || build !in markerBuilds
    }
}

private fun provisionDiscoveryNote(
    rawTargets: List<ProvisionTarget>,
    rows: List<ProvisionTarget>,
    discoveredMarkers: List<DiscoveredIde>,
): String? {
    if (discoveredMarkers.isNotEmpty() && rawTargets.isNotEmpty() && rows.size < rawTargets.size) {
        return "Filtered ${rawTargets.size - rows.size} entries already provisioned. " +
            "Use 'backend --json' for the full set."
    }
    return null
}

fun provisionTargetJson(target: ProvisionTarget): JsonObject = buildJsonObject {
    put("id", target.id)
    put("displayName", portBackendDisplayName(target.ide))
    put("locator", portBackendLocatorLabel(target.ide))
    putJsonFields(portBackendIdentityJson(target.ide))
    put("actions", buildJsonArray {
        add(provisionActionJson(target.id))
    })
}

fun provisionActionJson(id: String): JsonObject = buildJsonObject {
    put("id", PROVISION_ACTION_ID)
    put("label", "Install MCP Steroid plugin")
    put("command", provisionCommand(id))
}

private fun renderProvisionInstructionsText(result: ProvisionResult, out: PrintStream) {
    val productName = provisionTargetProductName(result.about, result.selector)
    val version = provisionTargetVersion(result.about)
    out.println("Target: $productName $version (port ${result.ide.port})")
    out.println()
    out.println("MCP Steroid is not installed in this IDE. To install:")
    out.println()
    out.println("  (a) From within the IDE")
    out.println("      → Settings → Plugins → Marketplace → search \"MCP Steroid\" → Install")
    out.println("      → restart the IDE.")
    out.println()
    out.println("  (b) Manual file install (advanced)")
    out.println("      Plugin source on this machine:")
    out.println("        ${result.pluginSource}")
    out.println("      Suggested install path:")
    out.println("        ${result.suggestedDestination}")
    out.println("      (the actual plugins folder may differ if the user customised it")
    out.println("       under Settings → Appearance & Behavior → System Settings → Path Variables)")
    out.println("      → restart the IDE.")
    out.println()
}

private fun provisionResultJson(result: ProvisionResult): JsonObject = buildJsonObject {
    putToolJson()
    put("action", PROVISION_ACTION_ID)
    put("id", result.id)
    put("target", buildJsonObject {
        put("productName", provisionTargetProductName(result.about, result.selector))
        put("version", provisionTargetVersion(result.about))
        result.about.buildNumber?.let { put("buildNumber", it) }
        put("port", result.ide.port)
    })
    put("instructions", buildJsonArray {
        add(buildJsonObject {
            put("step", "marketplace")
            put("description", "Use Settings → Plugins → Marketplace from within the IDE.")
        })
        add(buildJsonObject {
            put("step", "files")
            put("pluginSource", result.pluginSource.toString())
            put("suggestedDestination", result.suggestedDestination.toString())
            put("note", SUGGESTED_DESTINATION_NOTE)
        })
    })
    put("note", SUGGESTED_DESTINATION_NOTE)
}

private fun <T> withProvisionHttpClient(
    httpClientFactory: () -> HttpClient,
    closeHttpClient: Boolean,
    block: (HttpClient) -> T,
): T {
    val httpClient = httpClientFactory()
    return try {
        block(httpClient)
    } finally {
        if (closeHttpClient) {
            httpClient.close()
        }
    }
}

private fun createProvisionHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }
}
