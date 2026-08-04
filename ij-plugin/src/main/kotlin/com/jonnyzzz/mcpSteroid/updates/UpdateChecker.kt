/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.io.HttpRequests
import com.jonnyzzz.mcpSteroid.getBuildVersion
import com.jonnyzzz.mcpSteroid.onboarding.MCP_STEROID_NOTIFICATION_GROUP
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 */
@Service(Service.Level.APP)
class UpdateChecker(
    parentScope: CoroutineScope
) : Disposable {
    private val log = thisLogger()

    /**
     * The polling loop's scope, a **child** of the injected one. A bare `SupervisorJob()` would not be:
     * `+` replaces the context's Job with the new, parentless one, so cancelling the injected scope would
     * never stop the poller. Passing the parent Job explicitly makes this scope die with the service.
     * Same pattern and full rationale as [com.jonnyzzz.mcpSteroid.server.ServerUrlWriter]. (The platform's
     * named `childScope(name, ...)` overload is still `@ApiStatus.Experimental` on 261, so it is avoided.)
     */
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO
    )

    /** Whether we've already shown the update notification in this IDE session */
    private val notificationShown = AtomicBoolean(false)

    /**
     * Fetch the published `version-base` from version.json, or null when the request or parse fails.
     */
    suspend fun fetchLatestBaseVersion(): String? {
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

    suspend fun checkForUpdates() {
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
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup(MCP_STEROID_NOTIFICATION_GROUP)

        notificationGroup.createNotification(
            "MCP Steroid plugin update available",
            "A new version of MCP Steroid is available: $newVersion (current: ${
                extractBaseVersion(
                    currentVersion
                )
            })",
            NotificationType.INFORMATION
        ).addAction(NotificationAction.createSimpleExpiring("Download") {
            BrowserUtil.browse("https://devrig.dev/releases/")
        }).notify(null)
    }

    private fun buildUserAgent(pluginVersion: String, ijBuild: String): String {
        return "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)"
    }

    override fun dispose() {
        scope.cancel()
    }

    private val updateIsStarted = AtomicBoolean(false)

    fun startUpdates() {
        if (!updateIsStarted.compareAndSet(false, true)) return

        scope.launch {
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
