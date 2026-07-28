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
 * [lines] is one thought per element — what devrig is, then what it will cost or what is stale. The
 * renderer puts each on its own line: as a single paragraph the sentences ran together and the popup read
 * like a wall of text. The download size gets its own line, because a ~611 MB one-time download is the
 * fact a user needs before pressing the button.
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

/** Approximate size of a full devrig install (devrig + its pinned JDK), as the installer reports it. */
private const val INSTALL_SIZE = "~611 MB"

fun devrigWidgetPopupContent(state: DevrigConnectionState?): DevrigWidgetPopupContent {
    // State not computed yet (the widget was clicked before the first refresh finished): offer the
    // install anyway — it is the action the user came for, and the flow re-checks the state itself.
    val decision = state?.decision ?: OnboardingDecision.OFFER_ENABLE
    return when (decision) {
        OnboardingDecision.OFFER_ENABLE -> DevrigWidgetPopupContent(
            title = "devrig is not connected",
            lines = listOf(
                "devrig lets Claude Code drive this IDE — run and debug tests, refactor, and read " +
                    "inspections in the real IDE.",
                "Connecting downloads devrig once ($INSTALL_SIZE, it bundles its own JDK).",
            ),
            actionLabel = "Download and connect",
            action = DevrigWidgetAction.INSTALL,
        )
        OnboardingDecision.OFFER_UPDATE -> DevrigWidgetPopupContent(
            title = "devrig update available",
            lines = listOf(
                "Installed ${state?.installedVersion ?: "build"} is behind " +
                    "${state?.latestBaseVersion ?: "the current release"}.",
                "Updating keeps the IDE bridge — and the plugin it carries — in sync.",
            ),
            actionLabel = "Update devrig",
            action = DevrigWidgetAction.UPDATE,
        )
        OnboardingDecision.ALREADY_CONNECTED -> DevrigWidgetPopupContent(
            title = "devrig is connected",
            lines = listOf(
                ("Claude Code can drive this IDE through devrig ${state?.installedVersion ?: ""}").trim() + ".",
                "Start a new Claude session and ask it to run your tests here.",
            ),
            actionLabel = "Open settings",
            action = DevrigWidgetAction.OPEN_SETTINGS,
        )
        OnboardingDecision.OFFER_GET_AGENT -> DevrigWidgetPopupContent(
            title = "No AI agent found",
            lines = listOf(
                "devrig bridges a coding agent to this IDE, but no Claude Code CLI was found on this machine.",
                "Install one first, then devrig can connect it.",
            ),
            actionLabel = "How to get one",
            action = DevrigWidgetAction.LEARN_HOW,
        )
    }
}
