/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.util.messages.Topic
import com.jonnyzzz.mcpSteroid.updates.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Notified when the devrig install state changed — after an install, an update, or a re-check.
 *
 * The status bar can be told to re-read [DevrigStatusBarWidgetFactory.isAvailable], but an open settings
 * page cannot: its panel was built once. Without this, finishing an install left the page still offering
 * to install. The event carries the fresh state, already computed off the EDT, so a listener never has a
 * reason to read the filesystem from its (EDT) callback.
 */
fun interface DevrigStateListener {
    fun devrigStateChanged(state: DevrigConnectionState)
}

val DEVRIG_STATE_CHANGED: Topic<DevrigStateListener> =
    Topic.create("devrig install state changed", DevrigStateListener::class.java)

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
 * Computes [DevrigConnectionState] off the EDT and caches it for the EDT to read.
 *
 * The widget asks for the state from inside its paint path: [DevrigStatusBarWidgetFactory.isAvailable],
 * `getText()` and `getTooltipText()` all run on the EDT and are re-asked on every widget refresh — which
 * [DevrigFocusRefreshListener] triggers on every window-focus regain. An earlier version computed the two
 * file reads on the spot, right there; cheap as they usually are, `Files.isRegularFile` +
 * `Files.readString` on the paint path is still disk I/O on the EDT, and a slow home directory (network
 * mount, corporate AV) turns every alt-tab back into a UI stall.
 *
 * So the platform's own status-bar idiom (see `EditorBasedStatusBarPopup` and its update queue) is
 * followed instead: every "the answer may have changed" moment only signals [notifyStateChanged]; ONE
 * collector (in [DevrigStateCache]) debounces the signals, computes [localState] on [Dispatchers.IO],
 * stores it in a volatile cache, and then hops to the EDT to update the widgets and tell the settings
 * page. EDT presentation paths read only the cache, via [state].
 */
@Service(Service.Level.APP)
class DevrigConnectionStateService(scope: CoroutineScope) {
    private val log = thisLogger()

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

    private val cache = DevrigStateCache(
        scope = scope,
        debounceMs = REFRESH_DEBOUNCE_MS,
        compute = ::localState,
        push = { fresh, changed -> pushToUi(fresh, changed) },
    )

    /**
     * Local facts read from disk: installed, and which version. **Does file I/O — background threads
     * only**; EDT readers take [state], which is the cached result of this very computation. No network —
     * [DevrigConnectionState.outdated] is therefore false, which is the safe direction (we never claim
     * "stale" without having checked).
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
     * The cached facts plus whatever the last version check found. No I/O of any kind — safe to call
     * from painting code. Before the first background refresh lands this is [DevrigStateCache.SAFE_DEFAULT];
     * the cache is kept honest by [notifyStateChanged] and the focus listener below.
     */
    fun state(): DevrigConnectionState = cache.current.copy(latestBaseVersion = lastFetchedBaseVersion)

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
     * Tell the service the answer may have changed. Only a `tryEmit` — never blocks, never reads disk, so
     * it is safe from any thread including the EDT. The collector recomputes off the EDT (debounced by
     * [REFRESH_DEBOUNCE_MS]), refreshes every status bar, and — when the facts actually changed —
     * publishes [DEVRIG_STATE_CHANGED] with the fresh state (the settings page). Called after an install
     * finishes, and on every window-focus regain.
     */
    fun notifyStateChanged() {
        cache.requestRefresh()
    }

    /**
     * Bring every open project's status bar in line with [fresh], and tell the settings page when the
     * facts changed.
     *
     * [StatusBarWidgetsManager.updateWidget] is idempotent — it creates the widget when it should exist,
     * disposes it when it should not, and returns early when it is already correct — so calling it
     * unconditionally is self-healing. It also honours a widget the user hid (the manager consults
     * `StatusBarWidgetSettings` first), so this never resurrects one by force. The repaint afterwards is
     * what picks up a changed label on a widget that stays.
     *
     * The [DEVRIG_STATE_CHANGED] publish, by contrast, is gated on [changed]: rebuilding the settings
     * page resets its agent rows to "Checking…" and re-runs the agents' CLIs, so telling it about a
     * refresh that found nothing new (every window-focus regain) would not be free.
     */
    private suspend fun pushToUi(fresh: DevrigConnectionState, changed: Boolean) {
        // ModalityState.any(): a plain EDT dispatch is withheld while a modal dialog is up, so an install
        // started from the (modal) Settings dialog would not reach the status bar until it closed.
        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            for (project in ProjectManager.getInstance().openProjects) {
                if (project.isDisposed) continue
                project.service<StatusBarWidgetsManager>()
                    .updateWidget(DevrigStatusBarWidgetFactory::class.java)
                WindowManager.getInstance().getStatusBar(project)?.updateWidget(DEVRIG_STATUS_WIDGET_ID)
            }
            if (changed) {
                ApplicationManager.getApplication().messageBus
                    .syncPublisher(DEVRIG_STATE_CHANGED)
                    .devrigStateChanged(fresh.copy(latestBaseVersion = lastFetchedBaseVersion))
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
        /** How long a refresh request sits before the recompute, so a burst of focus events runs once. */
        const val REFRESH_DEBOUNCE_MS = 200L

        fun getInstance(): DevrigConnectionStateService = service()
    }
}

/**
 * The update-signal → cached-state machinery, factored out of the service so it can be tested as plain
 * logic (no IDE, no filesystem): [requestRefresh] only `tryEmit`s into a conflating flow (`replay = 1` +
 * `DROP_OLDEST`: it never suspends, never fails, and a burst collapses into one pending request); ONE
 * collector on [scope] waits out [debounceMs], runs [compute] on [Dispatchers.IO] (it may touch the
 * disk), stores the result in [current], and hands it to [push] together with whether the facts actually
 * changed since the last refresh.
 *
 * [current] is `@Volatile` and starts as [SAFE_DEFAULT]: any thread may read it at any moment and gets
 * either the default or a complete computed state, never a torn one. The seed request is emitted at
 * construction, so the real state replaces the default one debounce after the service exists — computed
 * on the collector, never on whichever thread (possibly the EDT) constructed the service.
 *
 * A failed [compute] or [push] is logged and the collector lives on: one bad refresh must not silence
 * the widget for the rest of the session.
 */
class DevrigStateCache(
    scope: CoroutineScope,
    private val debounceMs: Long,
    private val compute: () -> DevrigConnectionState,
    private val push: suspend (fresh: DevrigConnectionState, changed: Boolean) -> Unit,
) {
    private val log = thisLogger()

    /** The last computed state — [SAFE_DEFAULT] until the first refresh lands. */
    @Volatile
    var current: DevrigConnectionState = SAFE_DEFAULT
        private set

    private val updateRequests =
        MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch(Dispatchers.IO) {
            updateRequests.collect {
                delay(debounceMs)
                try {
                    refreshNow()
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("devrig state refresh failed", e)
                }
            }
        }
        // Seed: have the collector compute the real state once, right away.
        requestRefresh()
    }

    /** Ask for a recompute. Never blocks, never fails; callers may be on the EDT. */
    fun requestRefresh() {
        updateRequests.tryEmit(Unit)
    }

    private suspend fun refreshNow() {
        val previous = current
        val fresh = compute()
        current = fresh
        push(fresh, fresh != previous)
    }

    companion object {
        /**
         * What every reader sees until the first refresh lands: installed and current — nothing to act on.
         * That is the safe direction: an unknown state must not flash the widget in, nag about an install,
         * or claim staleness. Reality arrives one debounce later and corrects it.
         */
        val SAFE_DEFAULT = DevrigConnectionState(
            devrigInstalled = true,
            installedVersion = null,
            latestBaseVersion = null,
        )
    }
}

/**
 * Re-checks the devrig state when the IDE window regains focus.
 *
 * The status bar never polls: it consults [DevrigStatusBarWidgetFactory.isAvailable] when it creates
 * widgets and when someone calls `updateWidget`. So a widget that removed itself cannot notice that
 * devrig was later deleted, and one that is showing cannot notice that devrig has arrived. Window focus
 * is the cheap, event-driven moment to look again — the user has just come back to the IDE, very
 * possibly from doing exactly that in a terminal.
 *
 * Only a signal: [DevrigConnectionStateService.notifyStateChanged] is a `tryEmit`, so a burst of focus
 * events costs nothing here, and the actual file reads happen once, off the EDT, after the debounce.
 */
class DevrigFocusRefreshListener : ApplicationActivationListener {
    override fun applicationActivated(ideFrame: IdeFrame) {
        if (!devrigWidgetEnabled()) return
        DevrigConnectionStateService.getInstance().notifyStateChanged()
    }
}
