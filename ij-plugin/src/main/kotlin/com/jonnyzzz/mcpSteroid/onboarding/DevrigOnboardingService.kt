/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-level service that, once per IDE session, inspects the connection state and either offers a
 * one-click "Enable" (install devrig + `devrig connect claude`) or — when no `claude` CLI is present — a
 * soft "Connect an AI agent" notification linking to the docs. Fully-wired IDEs see nothing.
 */
@Service(Service.Level.APP)
class DevrigOnboardingService {
    private val log = thisLogger()
    private val offered = AtomicBoolean(false)
    private val runner = DevrigSetupRunner()

    fun maybeOffer(project: Project) {
        if (!offered.compareAndSet(false, true)) return
        try {
            when (currentDecision()) {
                OnboardingDecision.ALREADY_CONNECTED -> Unit
                OnboardingDecision.OFFER_ENABLE -> offerEnable(project)
                OnboardingDecision.OFFER_GET_AGENT -> offerGetAgent()
            }
        } catch (e: Exception) {
            log.warn("devrig onboarding check failed", e)
        }
    }

    private fun currentDecision(): OnboardingDecision {
        val userHome = Path.of(System.getProperty("user.home"))
        val windows = SystemInfo.isWindows
        val settingsFile = userHome.resolve(".claude").resolve("settings.json")
        val settingsText = if (Files.isRegularFile(settingsFile)) Files.readString(settingsFile) else null
        return decideOnboarding(
            devrigInstalled = devrigInstalled(userHome, windows),
            claudePresent = findClaudeBinary(System.getenv("PATH"), userHome, windows) != null,
            claudePluginEnabled = isClaudePluginEnabled(settingsText),
        )
    }

    private fun offerEnable(project: Project) {
        group().createNotification(
            "Connect Claude Code to this IDE",
            "Enable devrig so Claude Code can drive this IDE — run, debug, refactor, and inspect it.",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring("Enable") {
            runner.runEnable(project)
        }).notify(project)
    }

    private fun offerGetAgent() {
        group().createNotification(
            "Connect an AI agent to this IDE",
            "Install a coding agent (e.g. Claude Code), then devrig can bridge it to this IDE.",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring("Learn how") {
            BrowserUtil.browse(McpSteroidConfigurable.DEVRIG_DOCS_URL)
        }).notify(null)
    }

    private fun group() = NotificationGroupManager.getInstance()
        .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")

    companion object {
        fun getInstance(): DevrigOnboardingService = service()
    }
}
