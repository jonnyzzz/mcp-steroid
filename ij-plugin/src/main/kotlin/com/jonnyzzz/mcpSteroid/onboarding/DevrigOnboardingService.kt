/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.aiAgents.devrigHomeDisplayPath
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sticky group: the startup offer, and every failure. Both are things the user must be able to read after
 * looking away — a balloon that auto-hides during project open is a balloon nobody saw.
 * Must match `plugin.xml`.
 */
const val ONBOARDING_NOTIFICATION_GROUP = "jonnyzzz.mcp.steroid.onboarding"

/**
 * Auto-hiding group: the outcome of an install the user started and is watching. Must match `plugin.xml`.
 */
const val ONBOARDING_RESULTS_NOTIFICATION_GROUP = "jonnyzzz.mcp.steroid.onboarding.results"

/**
 * How long "Later" holds. The offer is a migration nudge, not an alert: repeating it at every project open
 * because the user has not acted yet is how a plugin turns into noise, and the same state is one click away
 * in Settings (plus the widget, when enabled) the whole time.
 */
const val OFFER_SNOOZE_DAYS = 14L

/**
 * The install offer's balloon body. A pure function so a test can pin the one fact a one-click install
 * surface owes the user before the click: the button starts a ~611 MB download, and where it lands.
 * The settings page disclosed both; this balloon used to start the identical download saying neither.
 * [devrigHome] follows the display policy of [devrigHomeDisplayPath]: the real absolute home, never `~`.
 */
fun devrigInstallOfferBody(devrigHome: String): String =
    "devrig bridges Claude Code, Codex or Gemini to this IDE — so an agent can run, debug, " +
        "refactor and inspect it.<br>Downloads ~611 MB into <code>$devrigHome</code>."

/**
 * Offers, once per IDE run, to install devrig or update a stale one.
 *
 * **Off unless [devrigWidgetEnabled] says otherwise.** A balloon at project open competes with the
 * noisiest moment of the session, and we do not yet know when showing it is worth the interruption —
 * "often" is clearly wrong. Until that is answered, the settings page is where the offer lives, because
 * a user who goes there is asking, and there is room to explain. This service stays so the question can
 * be settled by running with the key on, rather than by argument.
 */
@Service(Service.Level.APP)
class DevrigOnboardingService(private val scope: CoroutineScope) {
    private val log = thisLogger()
    private val offered = AtomicBoolean(false)
    private val runner = DevrigSetupRunner()

    fun maybeOffer(project: Project) {
        if (!devrigWidgetEnabled()) return
        if (offerSnoozedUntilMs() > System.currentTimeMillis()) return
        if (!offered.compareAndSet(false, true)) return
        scope.launch {
            try {
                val state = DevrigConnectionStateService.getInstance().stateWithVersionCheck()
                val decision = state.decision
                analyticsBeacon.capture(
                    "devrig_state_at_startup",
                    project,
                    buildMap {
                        put("decision", decision.name)
                        put("devrig_installed", state.devrigInstalled)
                        state.installedVersion?.let { put("devrig_version", it) }
                    },
                )
                when (decision) {
                    OnboardingDecision.DEVRIG_READY -> Unit
                    OnboardingDecision.OFFER_INSTALL -> offerInstall(project, decision)
                    OnboardingDecision.OFFER_UPDATE -> offerUpdate(project, state, decision)
                }
                DevrigConnectionStateService.getInstance().notifyStateChanged()
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nobody asked for this check, so its failure is a log line and nothing else.
                log.warn("devrig state check failed", e)
            }
        }
    }

    private fun offerInstall(project: Project, decision: OnboardingDecision) {
        val home = devrigHomeDisplayPath(System.getProperty("user.home"), SystemInfo.isWindows)
        group().createNotification(
            "Install devrig to connect an AI agent",
            devrigInstallOfferBody(home),
            NotificationType.INFORMATION,
        ).withOfferActions(project, decision, actionLabel = "Install devrig").notify(project)
        captureOffered(project, decision)
    }

    private fun offerUpdate(project: Project, state: DevrigConnectionState, decision: OnboardingDecision) {
        val installed = state.installedVersion ?: "an older build"
        val latest = state.latestBaseVersion ?: "a newer release"
        group().createNotification(
            "Update devrig",
            // <br> on purpose: without it the two sentences run together in the balloon.
            "devrig $installed is behind $latest.<br>Updating keeps the IDE bridge — and the plugin it " +
                "carries — current.",
            NotificationType.INFORMATION,
        ).withOfferActions(project, decision, actionLabel = "Update").notify(project)
        captureOffered(project, decision)
    }

    /**
     * The two actions every offer carries.
     *
     * "Later" means later, and is recorded: it snoozes the startup offer for [OFFER_SNOOZE_DAYS]. It used to
     * suppress nothing, so the same balloon returned at every project open until the user gave in —
     * a dismissal the product ignores is not a dismissal, and repeating an unanswered nudge is exactly how
     * the interruption stops being worth its attention. Nothing is hidden by snoozing: the state, and the
     * same install button, stay on the settings page (and on the widget, when it is enabled).
     */
    private fun Notification.withOfferActions(
        project: Project,
        decision: OnboardingDecision,
        actionLabel: String,
    ): Notification =
        addAction(NotificationAction.createSimpleExpiring(actionLabel) {
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "install", "decision" to decision.name),
            )
            runner.runInstall(project)
        }).addAction(NotificationAction.createSimpleExpiring("Later") {
            snoozeOffer()
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "later", "decision" to decision.name),
            )
        })

    /** Record the snooze application-wide: the offer is about this machine's devrig, not about one project. */
    fun snoozeOffer() {
        val until = System.currentTimeMillis() + Duration.ofDays(OFFER_SNOOZE_DAYS).toMillis()
        PropertiesComponent.getInstance().setValue(OFFER_SNOOZED_UNTIL_KEY, until.toString())
        log.info("devrig onboarding offer snoozed for $OFFER_SNOOZE_DAYS days")
    }

    /** 0 when never snoozed, or when the stored value is not a number we wrote. */
    fun offerSnoozedUntilMs(): Long =
        PropertiesComponent.getInstance().getValue(OFFER_SNOOZED_UNTIL_KEY)?.toLongOrNull() ?: 0L

    private fun captureOffered(project: Project, decision: OnboardingDecision) {
        analyticsBeacon.capture("devrig_onboarding_offered", project, mapOf("decision" to decision.name))
    }

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup(ONBOARDING_NOTIFICATION_GROUP)

    companion object {
        /** Application-level, so "Later" is not re-asked by the next project window. */
        const val OFFER_SNOOZED_UNTIL_KEY = "mcp.steroid.devrig.offer.snoozed.until"

        fun getInstance(): DevrigOnboardingService = service()
    }
}
