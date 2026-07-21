/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DevrigVersionInfo(
    @kotlinx.serialization.SerialName("version-base")
    val versionBase: String,
)

suspend fun fetchVersionInfo(): DevrigVersionInfo? {
    val json = Json { ignoreUnknownKeys = true }

    class HttpDevrigVersionFetcher

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 10_000
            socketTimeoutMillis = 10_000
        }
        expectSuccess = false
    }

    return try {
        val response = client.get("https://devrig.dev/version.json") {
            header("Accept", "application/json")
            header("User-Agent", "devrig/${DevrigVersionMetadata.getDevrigVersion()}")
        }
        if (!response.status.isSuccess()) return null
        return json.decodeFromString<DevrigVersionInfo>(response.bodyAsText())
    } catch (e: Throwable) {
        logger<HttpDevrigVersionFetcher>().debug("Update check failed. ${e.message}", e)
        null
    } finally {
        client.close()
    }
}

/**
 * Checks for a newer devrig release. Always reports on stderr (the existing channel);
 * additionally invokes [onNotice] with the human-readable message so callers can
 * surface it over MCP (e.g. a `notifications/message` broadcast).
 */
suspend fun checkForUpdates(onNotice: (String) -> Unit = {}) {
    val remoteVersion = fetchVersionInfo() ?: return
    val currentVersion = DevrigVersionMetadata.getDevrigVersion()
    if (currentVersion.startsWith(remoteVersion.versionBase)) return

    val newVersion = remoteVersion.versionBase
    val message = buildString {
        appendLine()
        appendLine("A new version of devrig is available: $newVersion (current: $currentVersion)")
        appendLine("Download update from: https://devrig.dev/releases/")
        appendLine()
    }
    System.err.println(message)
    onNotice(message)
}
