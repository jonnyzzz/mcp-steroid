/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.devrig.devrigHomeDisplayPath
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotificationKind
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotifications
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Registry key gating the once-per-run devrig promotion. Off by default: a startup balloon is not
 * ours to take uninvited, and the settings page carries the same offer with room to explain it.
 * The key id predates the (since deleted) status-bar widget and is kept stable so machines that
 * already flipped it keep their choice.
 */
const val DEVRIG_PROMOTION_REGISTRY_KEY = "mcp.steroid.devrig.widget.enabled"

fun devrigPromotionEnabled(): Boolean = Registry.`is`(DEVRIG_PROMOTION_REGISTRY_KEY, false)

/**
 * The promotion balloon's body. A pure function so a test can pin the one fact a one-click install
 * surface owes the user before the click: the button starts a ~611 MB download, and where it lands.
 * [devrigHome] follows the display policy of [devrigHomeDisplayPath]: the real absolute home, never `~`.
 */
fun devrigInstallOfferBody(devrigHome: String): String =
    "devrig bridges Claude Code, Codex or Gemini to this IDE — so an agent can run, debug, " +
        "refactor and inspect it.<br>Downloads ~611 MB into <code>$devrigHome</code>."

/**
 * Offers, at most once per IDE run, to install devrig. The promotion has exactly ONE reason to
 * exist: devrig is not installed. A stale devrig is not this service's business — devrig updates
 * itself (see `docs/updates-check/devrig-auto-update.md`).
 *
 * The whole policy is [startPromotion]. Activities start from an explicit platform callback, never
 * as a constructor side effect, so [com.jonnyzzz.mcpSteroid.server.SteroidsMcpServerStartupActivity]
 * calls [startPromotion] by name on every project open; the [started] guard turns every call after
 * the first into a no-op, and the application service being one-per-IDE-run makes that guard
 * once-per-run. The launched coroutine waits out a random delay from [PROMOTION_DELAY_RANGE]
 * (past the noisy project-open moment), computes the state on a background dispatcher BEFORE
 * showing anything, and only then
 * fires one non-sticky balloon whose single action is the existing install flow
 * ([DevrigSetupRunner.runInstall]). If the balloon auto-hides unseen, nothing is lost: the same
 * offer lives on the settings page, and the message stays in the Notifications tool window.
 * There is nothing to snooze and nothing to monitor.
 */
@Service(Service.Level.APP)
class DevrigPromotion(private val scope: CoroutineScope) {
    private val log = thisLogger()
    private val started = AtomicBoolean(false)

    /** Starts the once-per-run promotion one-shot; every call after the first is a no-op. */
    fun startPromotion() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            delay(randomPromotionDelay(Random.Default))
            try {
                maybePromote()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nobody asked for this check, so its failure is a log line and nothing else.
                log.warn("devrig promotion check failed", e)
            }
        }
    }

    private suspend fun maybePromote() {
        if (!devrigPromotionEnabled()) return
        val installed = withContext(Dispatchers.IO) { devrigInstalled() }
        // Any open project only anchors the balloon; the offer is about this machine, not a project.
        val project = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }
        analyticsBeacon.capture(
            "devrig_state_at_startup",
            project,
            mapOf("devrig_installed" to installed),
        )
        if (installed) return
        offerInstall(project)
    }

    /** [McpSteroidNotifications.notify] is thread-safe, so this stays on the background coroutine. */
    private fun offerInstall(project: Project?) {
        val home = devrigHomeDisplayPath(System.getProperty("user.home"), SystemInfo.isWindows)
        McpSteroidNotifications.getInstance().notify(
            McpSteroidNotificationKind.DEVRIG_INSTALL_OFFER, project, NotificationType.INFORMATION,
            "Install devrig to connect an AI agent",
            devrigInstallOfferBody(home),
            NotificationAction.createSimpleExpiring("Install devrig") {
                analyticsBeacon.capture(
                    "devrig_onboarding_action",
                    project,
                    mapOf("action" to "install"),
                )
                DevrigSetupRunner.getInstance().runInstall(project)
            },
        )
        analyticsBeacon.capture("devrig_onboarding_offered", project, emptyMap())
    }

    companion object {
        /**
         * The range the per-run promotion delay is drawn from, uniformly at random (owner-specified:
         * random 12-35 seconds). The lower bound keeps the balloon past the noisy project-open and
         * indexing moment; the upper bound keeps it reading as "on start". Randomizing within that
         * window keeps the balloon off the fixed instant every other timed on-start surface fires at.
         */
        val PROMOTION_DELAY_RANGE: ClosedRange<Duration> = 12.seconds..35.seconds

        /**
         * Draws the per-run delay uniformly from [PROMOTION_DELAY_RANGE] (millisecond granularity,
         * both bounds inclusive). [random] is a parameter so a test can pin the draw with a seeded
         * [Random]; production passes [Random.Default].
         */
        fun randomPromotionDelay(random: Random): Duration =
            random.nextLong(
                PROMOTION_DELAY_RANGE.start.inWholeMilliseconds,
                PROMOTION_DELAY_RANGE.endInclusive.inWholeMilliseconds + 1,
            ).milliseconds

        fun getInstance(): DevrigPromotion = service()
    }
}
