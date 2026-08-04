/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.aiAgents.devrigHomeDisplayPath

/** What the widget's popup button does when pressed. */
enum class DevrigWidgetAction {
    /** Install devrig by running the canonical installer. Registering an agent is a separate step. */
    INSTALL,

    /** Re-run the installer to move a stale devrig onto the current release. */
    UPDATE,

    /** Nothing to install — show where connecting an agent is described. */
    OPEN_SETTINGS,
}

/**
 * Everything the status-bar popup shows, derived from the connection state. Kept as data (and therefore
 * unit-testable) so the wording of the one screen a user actually reads is not buried in Swing code.
 *
 * **Keep it terse.** This is a status-bar popup, not documentation: the title states the situation, the
 * button states the action, and [lines] adds only what neither of them says — normally a single short
 * sentence. Explanations of what devrig is and next steps live in the docs behind the "Learn more" link;
 * crowding them in here made the popup unreadable. The one fact that must NOT be deferred to the docs is
 * cost: the install button starts a ~611 MB download into the devrig home, and a surface that starts it
 * with one click owes the user that number and destination before the click — the settings page already
 * disclosed both. [lines] exists so that when a state genuinely needs two facts (a version pair, the cost
 * disclosure) each gets its own rendered line instead of running together in one paragraph.
 * `DevrigWidgetPopupContentTest` enforces the length budget.
 */
data class DevrigWidgetPopupContent(
    val title: String,
    val lines: List<String>,
    val actionLabel: String,
    val action: DevrigWidgetAction,
) {
    /** The whole message as flat text — for logs and assertions, never for rendering. */
    val message: String get() = lines.joinToString(" ")
}

fun devrigWidgetPopupContent(
    state: DevrigConnectionState,
    // The real home, never `~` — see devrigHomeDisplayPath. A parameter so tests can pin a fixed path.
    devrigHome: String = devrigHomeDisplayPath(System.getProperty("user.home"), SystemInfo.isWindows),
): DevrigWidgetPopupContent =
    when (state.decision) {
        OnboardingDecision.OFFER_INSTALL -> DevrigWidgetPopupContent(
            title = "devrig is not installed",
            lines = listOf(
                "It lets an AI agent run, debug and refactor in this IDE.",
                "Downloads ~611 MB into $devrigHome.",
            ),
            actionLabel = "Install devrig",
            action = DevrigWidgetAction.INSTALL,
        )
        OnboardingDecision.OFFER_UPDATE -> DevrigWidgetPopupContent(
            title = "devrig update available",
            lines = listOf(
                "Installed ${state.installedVersion ?: "build"}, current " +
                    "${state.latestBaseVersion ?: "release is newer"}.",
            ),
            actionLabel = "Update devrig",
            action = DevrigWidgetAction.UPDATE,
        )
        OnboardingDecision.DEVRIG_READY -> DevrigWidgetPopupContent(
            title = "devrig is ready",
            lines = listOf(
                ("devrig ${state.installedVersion ?: ""} can bridge your agent to this IDE").trim() + ".",
            ),
            actionLabel = "Open settings",
            action = DevrigWidgetAction.OPEN_SETTINGS,
        )
    }
