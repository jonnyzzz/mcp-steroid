/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

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

    /** Debounce clock for [refreshLocalAsync]; see [LOCAL_RECHECK_INTERVAL_MS]. */
    private val lastLocalCheck = AtomicLong(0)

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
    suspend fun refresh(): DevrigConnectionState =
        publish(withContext(Dispatchers.IO) { compute() })

    /**
     * Re-check only the cheap local facts and publish the result. Runs when the IDE window regains focus
     * (see [DevrigFocusRefreshListener]), which is what brings the widget back inside the same session
     * after devrig is deleted or the Claude plugin is switched off outside the IDE — the widget cannot
     * notice that itself once [shouldShowDevrigWidget] has taken it away.
     *
     * No network: the published `version-base` is carried over from the last full [refresh], so this stays
     * a couple of file reads. Focus events fire often, hence the [LOCAL_RECHECK_INTERVAL_MS] debounce.
     */
    fun refreshLocalAsync() {
        val now = System.currentTimeMillis()
        val previous = lastLocalCheck.get()
        if (now - previous < LOCAL_RECHECK_INTERVAL_MS) return
        if (!lastLocalCheck.compareAndSet(previous, now)) return   // another focus event won the race
        scope.launch {
            try {
                publish(withContext(Dispatchers.IO) { computeLocal(cached?.latestBaseVersion) })
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("devrig connection-state local re-check failed", e)
            }
        }
    }

    /**
     * Cache the state and bring every open project's status bar in line with it.
     *
     * When the widget's availability flips we must go through [StatusBarWidgetsManager], which creates or
     * disposes the widget: the platform re-reads
     * [StatusBarWidgetFactory.isAvailable][com.intellij.openapi.wm.StatusBarWidgetFactory.isAvailable]
     * only when asked, so a plain `updateWidget(id)` repaint would leave a removed widget removed (and a
     * needed one absent). It also honours a widget the user hid, so this never resurrects one by force.
     */
    private suspend fun publish(state: DevrigConnectionState): DevrigConnectionState {
        val wasShown = shouldShowDevrigWidget(cached)
        cached = state
        val nowShown = shouldShowDevrigWidget(state)
        withContext(Dispatchers.EDT) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                if (wasShown != nowShown) {
                    project.service<StatusBarWidgetsManager>()
                        .updateWidget(DevrigStatusBarWidgetFactory::class.java)
                } else {
                    WindowManager.getInstance().getStatusBar(project)?.updateWidget(DEVRIG_STATUS_WIDGET_ID)
                }
            }
        }
        return state
    }

    private suspend fun compute(): DevrigConnectionState {
        // Only worth a network round-trip once devrig is actually installed — the not-installed case is
        // already an offer, and "outdated" is meaningless there.
        val installed = devrigInstalled(Path.of(System.getProperty("user.home")), SystemInfo.isWindows)
        val latest = if (installed) UpdateChecker.getInstance().fetchLatestBaseVersion() else null
        return computeLocal(latest)
    }

    /**
     * The local half of the state: file reads only, no network. [knownLatestBaseVersion] is threaded
     * through so a local re-check keeps the last fetched `version-base` instead of losing the "outdated"
     * signal (a null there would silently read as up-to-date).
     */
    private fun computeLocal(knownLatestBaseVersion: String?): DevrigConnectionState {
        val userHome = Path.of(System.getProperty("user.home"))
        val windows = SystemInfo.isWindows
        val installed = devrigInstalled(userHome, windows)
        val launcher = devrigBinPath(userHome, windows)
        val installedVersion = if (installed) installedDevrigVersion(readTextOrNull(launcher)) else null
        val settings = userHome.resolve(".claude").resolve("settings.json")

        return DevrigConnectionState(
            devrigInstalled = installed,
            installedVersion = installedVersion,
            latestBaseVersion = if (installed) knownLatestBaseVersion else null,
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
        /**
         * Shortest gap between two focus-triggered local re-checks. Alt-tabbing fires activation events
         * in bursts and each check touches the filesystem, so a burst must cost one check, not one per
         * event. Long enough to be free, short enough that coming back to the IDE after fixing something
         * outside it reflects almost immediately.
         */
        const val LOCAL_RECHECK_INTERVAL_MS: Long = 10_000

        fun getInstance(): DevrigConnectionStateService = service()
    }
}

/**
 * Re-checks the local devrig state when the IDE window regains focus.
 *
 * This is what lets the status-bar widget reappear within a session: once the migration looked finished
 * the widget removed itself, and a removed widget cannot notice that devrig was later deleted or that the
 * Claude plugin was switched off. Window focus is the cheap, event-driven moment to look again — the user
 * has just come back to the IDE, very possibly from doing exactly that.
 */
class DevrigFocusRefreshListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        DevrigConnectionStateService.getInstance().refreshLocalAsync()
    }
}
