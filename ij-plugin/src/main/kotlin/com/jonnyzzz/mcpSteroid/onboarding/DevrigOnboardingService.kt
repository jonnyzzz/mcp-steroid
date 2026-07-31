/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
                DevrigConnectionStateService.getInstance().refreshWidgets()
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
        group().createNotification(
            "Install devrig to connect an AI agent",
            "devrig bridges Claude Code, Codex or Gemini to this IDE — so an agent can run, debug, " +
                "refactor and inspect it.",
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
     * The two actions every offer carries. "Later" exists so that dismissing is a deliberate click rather
     * than a stray one on the balloon's close button — it does NOT suppress future offers.
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
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "later", "decision" to decision.name),
            )
        })

    private fun captureOffered(project: Project, decision: OnboardingDecision) {
        analyticsBeacon.capture("devrig_onboarding_offered", project, mapOf("decision" to decision.name))
    }

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")

    companion object {
        fun getInstance(): DevrigOnboardingService = service()
    }
}
