/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * JSON document written by IntelliJ plugin's DiscoveryService to
 * `<commonDataPath>/discovery/<pid>-ide-instance.json`.
 *
 * Provides IDE path information for plugin installation without requiring
 * the MCP Steroid plugin to already be installed.
 */
@Serializable
data class IdeInstanceInfo(
    val pid: Long,
    val paths: DiscoveryPaths,
    val ideInfo: IdeInstanceInfoDetails,
    val properties: DiscoveryProperties,
) {
    val pluginsDir: Path get() = Path.of(paths.plugins)
    val configDir: Path get() = Path.of(paths.config)
}

@Serializable
data class DiscoveryPaths(
    val config: String,
    val plugins: String,
)

@Serializable
data class IdeInstanceInfoDetails(
    val productCode: String,
)

@Serializable
data class DiscoveryProperties(
    val openProjectPaths: List<String> = emptyList(),
)

object IdeInstanceDiscoveryJson {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun decode(text: String): IdeInstanceInfo = json.decodeFromString(IdeInstanceInfo.serializer(), text)
}