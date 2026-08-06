/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.io.HttpRequests
import com.jonnyzzz.mcpSteroid.getBuildVersion
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotificationKind
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotifications
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Application-level service that periodically checks for plugin updates.
 *
 * Fetches version info from https://devrig.dev/version.json
 * and notifies the user ONCE per IDE session when a newer version is available.
 *
 * The check continues running even after an update is detected, but the notification
 * is shown only once per IDE run.
 *
 * The network stays sealed inside this service: the whole public surface is [startUpdates], which
 * only launches the polling loop on the injected background [scope] — no caller can pull the fetch
 * onto its own thread, so nothing here can ever run on (or block) the EDT.
 */
@Service(Service.Level.APP)
class UpdateChecker(
    /**
     * The platform-injected service scope, used as-is. It is already a supervisor —
     * `ComponentManagerImpl.instanceCoroutineScope` creates one fresh scope per service instance via
     * `childScope(pluginClass.name)`, whose `supervisor` parameter defaults to `true` — so one failed
     * check cannot cancel sibling coroutines, and the platform cancels the scope when the plugin
     * unloads. A hand-rolled `SupervisorJob` child of it would duplicate both guarantees, which is
     * why this service has no scope of its own and no `dispose()`.
     */
    private val scope: CoroutineScope,
) {
    private val log = thisLogger()

    /** Whether we've already shown the update notification in this IDE session */
    private val notificationShown = AtomicBoolean(false)

    /**
     * Fetch the published `version-base` from version.json, or null when the request or parse fails.
     */
    private suspend fun fetchLatestBaseVersion(): String? {
        val currentVersion = getBuildVersion()
        val ijBuild = ApplicationInfo.getInstance().build.asString()
        val url = "https://devrig.dev/version.json?intellij-version=$ijBuild"
        log.debug("Checking for updates at $url (current version: $currentVersion)")

        val response = withContext(Dispatchers.IO) {
            try {
                HttpRequests.request(url)
                    .userAgent(buildUserAgent(currentVersion.value, ijBuild))
                    .connectTimeout(10_000)
                    .readTimeout(10_000)
                    .readString()
            } catch (e: Exception) {
                log.debug("Update check failed: ${e.message}")
                null
            }
        } ?: return null

        val versionInfo = try {
            json.decodeFromString<VersionInfo>(response)
        } catch (e: Exception) {
            log.debug("Failed to parse version response: ${e.message}")
            return null
        }
        return versionInfo.versionBase
    }

    private suspend fun checkForUpdates() {
        val currentVersion = getBuildVersion()
        val remoteVersion = fetchLatestBaseVersion() ?: return

        val promotedVersion = DevrigVersion.parse(remoteVersion)
        log.info("Promoted version: $promotedVersion, current version: $currentVersion")

        if (DevrigVersion.isUpdateAvailable(current = currentVersion, promoted = promotedVersion)) {
            log.info("MCP Steroid plugin update available: $remoteVersion (current: $currentVersion)")

            // Show notification only once per IDE session
            if (notificationShown.compareAndSet(false, true)) {
                showUpdateNotification(currentVersion.value, remoteVersion)
            }
        }
    }

    /**
     * Extracts the base version from a full version string.
     * E.g., "0.86.0-SNAPSHOT-20260212-193000-a1b2c3d" -> "0.86.0"
     */
    private fun extractBaseVersion(fullVersion: String): String {
        // Handle SNAPSHOT versions: take everything before "-SNAPSHOT"
        val snapshotIndex = fullVersion.indexOf("-SNAPSHOT")
        if (snapshotIndex > 0) {
            return fullVersion.substring(0, snapshotIndex)
        }
        // Handle other suffixes: take everything before first dash
        val dashIndex = fullVersion.indexOf('-')
        if (dashIndex > 0) {
            return fullVersion.substring(0, dashIndex)
        }
        return fullVersion
    }

    private fun showUpdateNotification(currentVersion: String, newVersion: String) {
        McpSteroidNotifications.getInstance().notify(
            McpSteroidNotificationKind.PLUGIN_UPDATE, null, NotificationType.INFORMATION,
            "MCP Steroid plugin update available",
            "A new version of MCP Steroid is available: $newVersion (current: ${
                extractBaseVersion(
                    currentVersion
                )
            })",
            NotificationAction.createSimpleExpiring("Download") {
                BrowserUtil.browse("https://devrig.dev/releases/")
            },
        )
    }

    private fun buildUserAgent(pluginVersion: String, ijBuild: String): String {
        return "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)"
    }

    private val updateIsStarted = AtomicBoolean(false)

    /**
     * Starts the periodic update poll — the service's only public method, called explicitly from the
     * platform startup callback. Idempotent: the first call launches the loop on the service [scope]
     * (on [Dispatchers.IO], off the EDT), every later call is a no-op.
     */
    fun startUpdates() {
        if (!updateIsStarted.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            // Initial delay: wait a bit for IDE to fully start
            delay(30.seconds)

            while (isActive) {
                yield()

                // Check if updates are enabled
                if (Registry.`is`("mcp.steroid.updates.enabled", true)) {
                    try {
                        checkForUpdates()
                    } catch (e: Exception) {
                        log.debug("Failed to check for updates: ${e.message}", e)
                    }
                }

                // Wait before next check
                delay(15.minutes)
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        fun getInstance(): UpdateChecker = service()
    }
}

@Serializable
private data class VersionInfo(
    @kotlinx.serialization.SerialName("version-base")
    val versionBase: String
)
