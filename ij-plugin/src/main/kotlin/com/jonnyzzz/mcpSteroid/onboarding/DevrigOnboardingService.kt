/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level service that offers, once per IDE run, to finish the migration onto devrig: install
 * it (or update a stale one) and enable the Claude Code plugin. Fully-wired IDEs see nothing.
 *
 * The offer is deliberately persistent. The notification group is a STICKY_BALLOON (it does not
 * auto-hide) and there is **no "don't ask again"**: an unconnected IDE is offered again on the next IDE
 * run, and "Later" only dismisses the current balloon. Between offers the always-visible status-bar
 * widget ([DevrigStatusBarWidgetFactory]) carries the same state, so the user is never left without a
 * way back to it.
 */
@Service(Service.Level.APP)
class DevrigOnboardingService(private val scope: CoroutineScope) {
    private val log = thisLogger()
    private val offered = AtomicBoolean(false)
    private val runner = DevrigSetupRunner()

    fun maybeOffer(project: Project) {
        if (!offered.compareAndSet(false, true)) return
        scope.launch {
            try {
                val state = DevrigConnectionStateService.getInstance().refresh()
                val decision = state.decision
                analyticsBeacon.capture(
                    "devrig_state_at_startup",
                    project,
                    buildMap {
                        put("decision", decision.name)
                        put("devrig_installed", state.devrigInstalled)
                        put("claude_present", state.claudePresent)
                        put("claude_plugin_enabled", state.claudePluginEnabled)
                        state.installedVersion?.let { put("devrig_version", it) }
                    },
                )
                when (decision) {
                    OnboardingDecision.ALREADY_CONNECTED -> Unit
                    OnboardingDecision.OFFER_ENABLE -> offerEnable(project, decision)
                    OnboardingDecision.OFFER_UPDATE -> offerUpdate(project, state, decision)
                    OnboardingDecision.OFFER_GET_AGENT -> offerGetAgent(project, decision)
                }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("devrig onboarding check failed", e)
            }
        }
    }

    private fun offerEnable(project: Project, decision: OnboardingDecision) {
        group().createNotification(
            "Connect Claude Code to this IDE",
            "Enable devrig so Claude Code can drive this IDE — run, debug, refactor, and inspect it.",
            NotificationType.INFORMATION,
        ).withMigrationActions(project, decision, actionLabel = "Enable").notify(project)
        captureOffered(project, decision)
    }

    private fun offerUpdate(project: Project, state: DevrigConnectionState, decision: OnboardingDecision) {
        val installed = state.installedVersion ?: "an older build"
        val latest = state.latestBaseVersion ?: "a newer release"
        group().createNotification(
            "Update devrig",
            "devrig $installed is behind $latest. Updating keeps the IDE bridge — and the plugin it " +
                "carries — current.",
            NotificationType.INFORMATION,
        ).withMigrationActions(project, decision, actionLabel = "Update").notify(project)
        captureOffered(project, decision)
    }

    /**
     * The two actions every migration offer carries. "Later" exists so that dismissing is a deliberate
     * click rather than a stray one on the balloon's close button — it does NOT suppress future offers.
     */
    private fun Notification.withMigrationActions(
        project: Project,
        decision: OnboardingDecision,
        actionLabel: String,
    ): Notification =
        addAction(NotificationAction.createSimpleExpiring(actionLabel) {
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "enable", "decision" to decision.name),
            )
            runner.runEnable(project)
        }).addAction(NotificationAction.createSimpleExpiring("Later") {
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "later", "decision" to decision.name),
            )
        })

    private fun offerGetAgent(project: Project, decision: OnboardingDecision) {
        group().createNotification(
            "Connect an AI agent to this IDE",
            "Install a coding agent (e.g. Claude Code), then devrig can bridge it to this IDE.",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring("Learn how") {
            analyticsBeacon.capture(
                "devrig_onboarding_action",
                project,
                mapOf("action" to "learn_how", "decision" to decision.name),
            )
            BrowserUtil.browse(McpSteroidConfigurable.DEVRIG_DOCS_URL)
        }).notify(null)
        captureOffered(project, decision)
    }

    private fun captureOffered(project: Project, decision: OnboardingDecision) {
        analyticsBeacon.capture("devrig_onboarding_offered", project, mapOf("decision" to decision.name))
    }

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")

    companion object {
        fun getInstance(): DevrigOnboardingService = service()
    }
}
