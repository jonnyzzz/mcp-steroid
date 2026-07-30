/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
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
 * The PASSIVE update notice for short CLI commands and opted-out sessions. Always reports on stderr
 * (the existing channel); additionally invokes [onNotice] with the human-readable message so callers
 * can surface it over MCP (e.g. a `notifications/message` broadcast).
 *
 * Marker-aware (docs/updates-check/devrig-auto-update.md → Notifications): while another process's
 * install is in flight it prints NOTHING (no notification before the install script completes), and
 * once an install completed it proposes a RESTART instead of telling the user to download a version
 * that is already on disk. [homePaths] = null keeps the legacy banner-only behavior (tests).
 */
suspend fun checkForUpdates(homePaths: HomePaths? = null, onNotice: (String) -> Unit = {}) {
    val remoteVersion = fetchVersionInfo() ?: return
    val currentVersion = DevrigVersionMetadata.getBuildVersion()
    val promotedVersion = DevrigVersion.parse(remoteVersion.versionBase)
    if (!DevrigVersion.isUpdateAvailable(current = currentVersion, promoted = promotedVersion)) return

    val notice = if (homePaths == null) PassiveUpdateNotice.DOWNLOAD_BANNER else passiveUpdateNotice(
        promoted = promotedVersion,
        coordination = UpdateCoordination(homePaths.updateDir),
    )

    val newVersion = remoteVersion.versionBase
    val message = when (notice) {
        PassiveUpdateNotice.NONE -> return
        PassiveUpdateNotice.RESTART -> buildString {
            appendLine()
            appendLine("devrig $newVersion is installed — restart your agent session to use it (current: $currentVersion).")
            appendLine()
        }
        PassiveUpdateNotice.DOWNLOAD_BANNER -> buildString {
            appendLine()
            appendLine("A new version of devrig is available: $newVersion (current: $currentVersion)")
            appendLine("Download update from: https://devrig.dev/releases/")
            appendLine()
        }
    }
    System.err.println(message)
    onNotice(message)
}

enum class PassiveUpdateNotice { NONE, RESTART, DOWNLOAD_BANNER }

/**
 * Pure decision for the passive paths — exactly two cheap file checks (it never reads
 * `update-failed-*`): a live in-progress marker → say nothing (no notification before an install
 * script completes); `updated-<promoted>` present → propose a restart; otherwise → the banner.
 */
fun passiveUpdateNotice(
    promoted: DevrigVersion,
    coordination: UpdateCoordination,
): PassiveUpdateNotice = when {
    coordination.isUpdateInFlight() -> PassiveUpdateNotice.NONE
    coordination.hasUpdatedMarker(baseVersionString(promoted.value)) -> PassiveUpdateNotice.RESTART
    else -> PassiveUpdateNotice.DOWNLOAD_BANNER
}
