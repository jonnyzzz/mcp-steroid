/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The facts the onboarding notification and the status-bar widget both reason about, so the two can
 * never disagree. Everything here is derived from cheap local state plus the published `version-base`.
 */
data class DevrigConnectionState(
    val devrigInstalled: Boolean,
    /** Version read out of the stable launcher, or null when unknown (see [installedDevrigVersion]). */
    val installedVersion: String?,
    /** `version-base` from version.json, or null when it could not be fetched yet. */
    val latestBaseVersion: String?,
    val claudePresent: Boolean,
    val claudePluginEnabled: Boolean,
) {
    val outdated: Boolean get() = isDevrigOutdated(installedVersion, latestBaseVersion)

    val decision: OnboardingDecision
        get() = decideOnboarding(
            devrigInstalled = devrigInstalled,
            claudePresent = claudePresent,
            claudePluginEnabled = claudePluginEnabled,
            devrigOutdated = outdated,
        )
}

/**
 * Application service that computes [DevrigConnectionState] and pushes the result to the status-bar
 * widget. Single source of truth: the startup offer ([DevrigOnboardingService]) and the widget
 * ([DevrigStatusBarWidgetFactory]) both read the cache instead of probing the filesystem themselves.
 *
 * Nothing here blocks: [current] returns the last computed state (null before the first refresh) and
 * [refreshAsync] recomputes off the EDT. The state is refreshed at startup, and again after an install
 * or update finishes.
 */
@Service(Service.Level.APP)
class DevrigConnectionStateService(private val scope: CoroutineScope) {
    private val log = thisLogger()

    @Volatile
    private var cached: DevrigConnectionState? = null

    /** The last computed state, or null if the first refresh has not finished yet. */
    fun current(): DevrigConnectionState? = cached

    /** Recompute in the background, then refresh the status-bar widget of every open project. */
    fun refreshAsync() {
        scope.launch {
            try {
                refresh()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("devrig connection-state refresh failed", e)
            }
        }
    }

    /** Recompute the state, cache it, and update the widgets. Returns the fresh state. */
    suspend fun refresh(): DevrigConnectionState {
        val state = withContext(Dispatchers.IO) { compute() }
        cached = state
        withContext(Dispatchers.EDT) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(DEVRIG_STATUS_WIDGET_ID)
            }
        }
        return state
    }

    private suspend fun compute(): DevrigConnectionState {
        val userHome = Path.of(System.getProperty("user.home"))
        val windows = SystemInfo.isWindows
        val installed = devrigInstalled(userHome, windows)
        val launcher = devrigBinPath(userHome, windows)
        val installedVersion = if (installed) installedDevrigVersion(readTextOrNull(launcher)) else null
        // Only worth a network round-trip once devrig is actually installed — the not-installed case is
        // already an offer, and "outdated" is meaningless there.
        val latest = if (installed) UpdateChecker.getInstance().fetchLatestBaseVersion() else null
        val settings = userHome.resolve(".claude").resolve("settings.json")

        return DevrigConnectionState(
            devrigInstalled = installed,
            installedVersion = installedVersion,
            latestBaseVersion = latest,
            claudePresent = findClaudeBinary(System.getenv("PATH"), userHome, windows) != null,
            claudePluginEnabled = isClaudePluginEnabled(readTextOrNull(settings)),
        )
    }

    private fun readTextOrNull(path: Path): String? = try {
        if (Files.isRegularFile(path)) Files.readString(path) else null
    } catch (e: Exception) {
        log.debug("cannot read $path: ${e.message}")
        null
    }

    companion object {
        fun getInstance(): DevrigConnectionStateService = service()
    }
}
