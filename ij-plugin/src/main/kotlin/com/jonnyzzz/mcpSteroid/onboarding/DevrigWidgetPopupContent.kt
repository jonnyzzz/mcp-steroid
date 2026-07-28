/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

/** What the widget's popup button does when pressed. */
enum class DevrigWidgetAction {
    /** Install devrig (the canonical installer) and connect Claude Code. */
    INSTALL,

    /** Re-run the installer to move a stale devrig onto the current release. */
    UPDATE,

    /** Nothing to install — show where the connection is described. */
    OPEN_SETTINGS,

    /** No agent CLI on this machine — send the user to the docs. */
    LEARN_HOW,
}

/**
 * Everything the status-bar popup shows, derived from the connection state. Kept as data (and therefore
 * unit-testable) so the wording of the one screen a user actually reads is not buried in Swing code.
 *
 * **Keep it terse.** This is a status-bar popup, not documentation: the title states the situation, the
 * button states the action, and [lines] adds only what neither of them says — normally a single short
 * sentence. Explanations of what devrig is, install sizes and next steps live in the docs behind the
 * "Learn more" link; crowding them in here made the popup unreadable. [lines] exists so that when a state
 * genuinely needs two facts (a version pair, say) each gets its own rendered line instead of running
 * together in one paragraph. `DevrigWidgetPopupContentTest` enforces the length budget.
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

fun devrigWidgetPopupContent(state: DevrigConnectionState?): DevrigWidgetPopupContent {
    // State not computed yet (the widget was clicked before the first refresh finished): offer the
    // install anyway — it is the action the user came for, and the flow re-checks the state itself.
    val decision = state?.decision ?: OnboardingDecision.OFFER_ENABLE
    return when (decision) {
        OnboardingDecision.OFFER_ENABLE -> DevrigWidgetPopupContent(
            title = "devrig is not connected",
            lines = listOf("Let Claude Code run, debug and refactor in this IDE."),
            actionLabel = "Download and connect",
            action = DevrigWidgetAction.INSTALL,
        )
        OnboardingDecision.OFFER_UPDATE -> DevrigWidgetPopupContent(
            title = "devrig update available",
            lines = listOf(
                "Installed ${state?.installedVersion ?: "build"}, current " +
                    "${state?.latestBaseVersion ?: "release is newer"}.",
            ),
            actionLabel = "Update devrig",
            action = DevrigWidgetAction.UPDATE,
        )
        OnboardingDecision.ALREADY_CONNECTED -> DevrigWidgetPopupContent(
            title = "devrig is connected",
            lines = listOf(
                ("Claude Code can drive this IDE through devrig ${state?.installedVersion ?: ""}").trim() + ".",
            ),
            actionLabel = "Open settings",
            action = DevrigWidgetAction.OPEN_SETTINGS,
        )
        OnboardingDecision.OFFER_GET_AGENT -> DevrigWidgetPopupContent(
            title = "No AI agent found",
            lines = listOf("Install Claude Code — devrig bridges it to this IDE."),
            actionLabel = "How to get one",
            action = DevrigWidgetAction.LEARN_HOW,
        )
    }
}
