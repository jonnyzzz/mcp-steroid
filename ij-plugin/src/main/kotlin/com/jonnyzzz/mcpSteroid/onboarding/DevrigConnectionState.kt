/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * The facts every surface reasons about: is the bridge installed, which version, and what the current
 * release is. Derived from two file reads plus — only where it is actually wanted — one HTTP call.
 */
data class DevrigConnectionState(
    val devrigInstalled: Boolean,
    /** Version read out of the stable launcher, or null when unknown (see [installedDevrigVersion]). */
    val installedVersion: String?,
    /** `version-base` from version.json, or null when it was not fetched (or could not be). */
    val latestBaseVersion: String?,
) {
    val outdated: Boolean get() = isDevrigOutdated(installedVersion, latestBaseVersion)

    val decision: OnboardingDecision
        get() = decideOnboarding(devrigInstalled = devrigInstalled, devrigOutdated = outdated)
}

/**
 * Computes [DevrigConnectionState] on demand. **Nothing is cached** — deliberately.
 *
 * The local half is a `Files.isRegularFile` plus reading a launcher script of a few hundred bytes, which
 * is cheaper than any scheme for keeping a cached copy honest would be. An earlier version did cache it,
 * and the cost was not the memory: it was a debounce clock, a "carry the last known remote version
 * forward" parameter, and a self-healing publish path that existed purely so a missed invalidation could
 * not strand the UI. All of that is gone.
 *
 * The remote half ([stateWithVersionCheck]) is a single HTTP GET of `version.json` and is not stored
 * either. Callers that need it ask at the moment they need it; callers that do not — the settings page,
 * the widget's existence check — never pay for it.
 */
@Service(Service.Level.APP)
class DevrigConnectionStateService(private val scope: CoroutineScope) {
    private val log = thisLogger()

    /**
     * Is the stable launcher there? One `stat`, no parsing — this is what the widget's existence hangs
     * on, so it is kept separate from [localState] and cheap enough to answer on the EDT.
     */
    fun devrigIsInstalled(): Boolean =
        devrigInstalled(Path.of(System.getProperty("user.home")), SystemInfo.isWindows)

    /**
     * Local facts only: installed, and which version. No network — [DevrigConnectionState.outdated] is
     * therefore false, which is the safe direction (we never claim "stale" without having checked).
     */
    fun localState(): DevrigConnectionState {
        val userHome = Path.of(System.getProperty("user.home"))
        val windows = SystemInfo.isWindows
        val installed = devrigInstalled(userHome, windows)
        val launcherText = if (installed) readTextOrNull(devrigBinPath(userHome, windows)) else null
        return DevrigConnectionState(
            devrigInstalled = installed,
            installedVersion = installedDevrigVersion(launcherText),
            latestBaseVersion = null,
        )
    }

    /**
     * The last `version-base` we fetched, for rendering only.
     *
     * This is **not** a cache: it never causes a fetch to be skipped — [stateWithVersionCheck] always
     * goes to the network. It exists because the status bar renders synchronously and cannot await an
     * HTTP call, so the answer has to be somewhere by the time `getText()` is asked. Null until the
     * first successful fetch, which reads as "not outdated" — we never claim stale without knowing.
     */
    @Volatile
    private var lastFetchedBaseVersion: String? = null

    /** [localState] plus whatever the last version check found. Synchronous; safe to call for painting. */
    fun state(): DevrigConnectionState = localState().copy(latestBaseVersion = lastFetchedBaseVersion)

    /** [localState] plus a **fresh** `version-base`, so the result can say "outdated". Always refetches. */
    suspend fun stateWithVersionCheck(): DevrigConnectionState {
        val local = withContext(Dispatchers.IO) { localState() }
        // "Outdated" is meaningless without an installed devrig, and that case is already an offer.
        if (!local.devrigInstalled) return local
        val latest = UpdateChecker.getInstance().fetchLatestBaseVersion()
        lastFetchedBaseVersion = latest
        return local.copy(latestBaseVersion = latest)
    }

    /**
     * Bring every open project's status bar in line with the current state.
     *
     * [StatusBarWidgetsManager.updateWidget] is idempotent — it creates the widget when it should exist,
     * disposes it when it should not, and returns early when it is already correct — so calling it
     * unconditionally is self-healing. It also honours a widget the user hid (the manager consults
     * `StatusBarWidgetSettings` first), so this never resurrects one by force. The repaint afterwards is
     * what picks up a changed label on a widget that stays.
     */
    fun refreshWidgets() {
        scope.launch(Dispatchers.EDT) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                project.service<StatusBarWidgetsManager>()
                    .updateWidget(DevrigStatusBarWidgetFactory::class.java)
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(DEVRIG_STATUS_WIDGET_ID)
            }
        }
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

/**
 * Re-checks the widget when the IDE window regains focus.
 *
 * The status bar never polls: it consults [DevrigStatusBarWidgetFactory.isAvailable] when it creates
 * widgets and when someone calls `updateWidget`. So a widget that removed itself cannot notice that
 * devrig was later deleted, and one that is showing cannot notice that devrig has arrived. Window focus
 * is the cheap, event-driven moment to look again — the user has just come back to the IDE, very
 * possibly from doing exactly that in a terminal.
 */
class DevrigFocusRefreshListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        if (!devrigWidgetEnabled()) return
        DevrigConnectionStateService.getInstance().refreshWidgets()
    }
}
