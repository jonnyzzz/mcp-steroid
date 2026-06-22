/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.markerLocator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun markerBackendDisplayName(ide: DiscoveredIde): String = ide.ide.displayName

fun markerBackendLocatorLabel(ide: DiscoveredIde): String =
    markerLocator(ide.ide.build, ide.pid)

fun portBackendDisplayName(ide: DiscoveredIdeByPort): String =
    ide.productFullName ?: ide.productName ?: "(unknown JetBrains IDE)"

fun portBackendLocatorLabel(ide: DiscoveredIdeByPort): String = buildString {
    ide.buildNumber?.let { append("build ").append(it).append(", ") }
    append("port ").append(ide.port)
}

fun backendDisplayName(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendDisplayName(row.ide)
    is BackendRow.FromPort -> portBackendDisplayName(row.ide)
    is BackendRow.FromManaged -> row.displayName
}

fun backendLocatorLabel(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> markerBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromPort -> portBackendLocatorLabel(row.ide) + if (row.managed) ", managed" else ""
    is BackendRow.FromManaged -> row.locatorLabel
}

fun backendPluginStatusText(row: BackendRow): String = when (row) {
    is BackendRow.FromMarker -> {
        val plugin = row.ide.plugin
        "${plugin.name.ifBlank { "MCP Steroid" }}: ${plugin.version.ifBlank { "unknown" }}"
    }
    is BackendRow.FromPort,
    is BackendRow.FromManaged -> "MCP Steroid: not installed"
}

/**
 * Identity extras for a port-discovered IDE. Still hand-built JSON because it backs the
 * `devrig backend provision` listing ([provisionTargetJson]); the `backend --json` / MCP path uses the
 * shared [com.jonnyzzz.mcpSteroid.server.BackendInfo] schema instead (see [backendInfoForRow]).
 */
fun portBackendIdentityJson(ide: DiscoveredIdeByPort): JsonObject = buildJsonObject {
    put("port", ide.port)
    put("baseUrl", ide.baseUrl)
    ide.productName?.let { put("productName", it) }
    ide.productFullName?.let { put("productFullName", it) }
    ide.edition?.let { put("edition", it) }
    ide.baselineVersion?.let { put("baselineVersion", it) }
    ide.buildNumber?.let { put("buildNumber", it) }
}

fun JsonObjectBuilder.putJsonFields(fields: JsonObject) {
    for ((key, value) in fields) {
        put(key, value)
    }
}
