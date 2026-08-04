/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel

/** Widget id — also the key [StatusBar.updateWidget] refreshes (see [DevrigConnectionStateService]). */
const val DEVRIG_STATUS_WIDGET_ID: String = "jonnyzzz.mcp.steroid.devrig"

/** Registry key that turns the status-bar widget and the startup offer on. Off by default — see below. */
const val DEVRIG_WIDGET_REGISTRY_KEY: String = "mcp.steroid.devrig.widget.enabled"

/**
 * Whether the widget and the startup notification exist at all.
 *
 * Off by default. Status-bar space and startup balloons are the IDE's scarcest attention budget, and a
 * plugin taking either without being asked is exactly the kind of thing that gets flagged. The settings
 * page carries the same offer with room to explain itself, so nothing is lost by staying quiet here. The
 * key exists so we can run with it on ourselves and judge the idea before it reaches anyone else.
 */
fun devrigWidgetEnabled(): Boolean = Registry.`is`(DEVRIG_WIDGET_REGISTRY_KEY, false)

/**
 * Whether the widget should occupy status-bar space, given the current state.
 *
 * Even switched on it is a transient onboarding aid, not a fixture: it exists only while there is
 * something to act on and removes itself once devrig is installed and current. If that regresses — devrig
 * deleted, a newer release published — it comes back, see [DevrigFocusRefreshListener].
 *
 * An unknown state reads as ready ([DevrigStateCache.SAFE_DEFAULT]), so before the first background
 * refresh lands the widget stays out — not knowing must not become a nag.
 */
fun shouldShowDevrigWidget(state: DevrigConnectionState): Boolean =
    state.decision != OnboardingDecision.DEVRIG_READY

/**
 * The widget's tooltip: it states the situation, and every branch ends with the same "click for details"
 * promise the click keeps (the click opens the popup that explains and offers the action).
 *
 * Top-level and pure so `DevrigWidgetTooltipTest` can pin the wording. The missing-devrig branch must
 * offer, not overclaim: while the deprecated direct-HTTP path exists an agent can still reach the IDE
 * without devrig, so "no agent can reach this IDE" would be false.
 */
fun devrigWidgetTooltip(state: DevrigConnectionState): String = when (state.decision) {
    OnboardingDecision.DEVRIG_READY ->
        "devrig" + (state.installedVersion?.let { " $it" } ?: "") +
            " bridges your AI agent to this IDE — click for details"
    OnboardingDecision.OFFER_UPDATE ->
        "devrig ${state.installedVersion ?: ""} is behind " +
            "${state.latestBaseVersion ?: "the current release"} — click for details"
    OnboardingDecision.OFFER_INSTALL ->
        "devrig is not installed — install it to bridge an agent to this IDE — click for details"
}

/**
 * A calm, always-visible alternative to the startup balloon, for the sessions where it is switched on.
 *
 * The user can also remove it at any time: [isConfigurable] is left at the platform default (`true`), so
 * right-clicking the status bar offers to hide this widget and the choice is persisted by the platform.
 * We deliberately do not reimplement that in our own popup.
 */
class DevrigStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = DEVRIG_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "devrig"

    /**
     * Availability is re-read only when the platform creates widgets or when
     * [com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager.updateWidget] is called — the
     * status bar never polls, hence [DevrigFocusRefreshListener].
     *
     * Cheap on purpose: this runs on the EDT, so it reads only the service's cached state — the file
     * reads behind the answer happen in the service's background collector, never here.
     */
    override fun isAvailable(project: Project): Boolean =
        devrigWidgetEnabled() && shouldShowDevrigWidget(DevrigConnectionStateService.getInstance().state())

    override fun createWidget(project: Project): StatusBarWidget = DevrigStatusBarWidget(project)
}

/**
 * We implement only the non-deprecated [StatusBarWidget.getPresentation]. The Plugin Verifier still
 * reports 3 deprecated-API hits on `getPresentation(PlatformType)` for this class: `StatusBarWidget` is a
 * **Kotlin** interface whose deprecated overload has a default body, so the Kotlin compiler materialises a
 * bridge override in every Kotlin implementor (`javap` shows it delegating straight back to the interface
 * default). It is a compiler artifact, not a call of ours — there is no non-deprecated path to remove it
 * while implementing this interface in Kotlin, and it must NOT be "fixed" with `@Suppress("DEPRECATION")`
 * (banned) nor by overriding the deprecated overload (that would be a real usage). Rewriting the widget
 * in Java would silence it, at the cost of Kotlin-interop noise for every service it touches.
 */
private class DevrigStatusBarWidget(private val project: Project) : StatusBarWidget {
    override fun ID(): String = DEVRIG_STATUS_WIDGET_ID

    override fun install(statusBar: StatusBar) = Unit

    override fun dispose() = Unit

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = Presentation()

    private inner class Presentation : StatusBarWidget.TextPresentation {
        override fun getText(): String = when (state().decision) {
            OnboardingDecision.DEVRIG_READY -> "devrig: ready"
            OnboardingDecision.OFFER_UPDATE -> "devrig: update available"
            OnboardingDecision.OFFER_INSTALL -> "devrig: not installed"
        }

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        override fun getTooltipText(): String = devrigWidgetTooltip(state())

        // A click must explain before it acts: starting a ~611 MB download from a single unexplained click
        // on a status-bar label would be hostile, and doing nothing (the earlier behaviour of quietly
        // launching a background task) reads as a dead widget. So the click opens a small popup that says
        // what devrig is and puts the download behind one labelled button.
        override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
            com.intellij.util.Consumer { event -> showPopup(event) }

        // Paint path: the cached state only. The service's collector keeps it honest off the EDT.
        private fun state() = DevrigConnectionStateService.getInstance().state()
    }

    private fun showPopup(event: MouseEvent) {
        val content = devrigWidgetPopupContent(DevrigConnectionStateService.getInstance().state())
        val panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(12)
        }
        panel.add(JBLabel("<html><b>${escape(content.title)}</b></html>"), BorderLayout.NORTH)
        // One paragraph per thought, with a gap between them: as a single block the sentences ran together.
        // Everything interpolated (versions come from a parsed launcher path) is escaped before it reaches
        // the HTML renderer.
        val body = content.lines.joinToString("") { "<p style='margin:0 0 6px 0'>${escape(it)}</p>" }
        panel.add(
            JBLabel("<html><body style='width:320px'>$body</body></html>"),
            BorderLayout.CENTER,
        )

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        val docs = HyperlinkLabel("Learn more")
        val actionButton = JButton(content.actionLabel)
        buttons.add(docs)
        buttons.add(actionButton)
        panel.add(buttons, BorderLayout.SOUTH)

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, actionButton)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        // Tie the popup to the widget's lifetime, so closing the project or window while the popup is
        // open destroys it instead of leaving it orphaned (precedent: EditorBasedStatusBarPopup.showPopup).
        Disposer.register(this, popup)

        actionButton.addActionListener {
            popup.closeOk(null)
            runWidgetAction(content.action)
        }
        // Both exits close the popup first — otherwise it lingers over the IDE while the browser or the
        // progress bar takes over.
        docs.addHyperlinkListener {
            popup.closeOk(null)
            // Same target as the settings page's "What is devrig?": the site root, tagged with the IDE build.
            BrowserUtil.browse(McpSteroidConfigurable.whatIsDevrigUrl())
        }

        // Anchor above the widget: the status bar sits at the bottom of the screen, so a popup dropped
        // from the click point would open off-screen.
        val above = Point(0, -panel.preferredSize.height)
        popup.show(RelativePoint(event.component, above))
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)

    private fun runWidgetAction(action: DevrigWidgetAction) {
        analyticsBeacon.capture(
            "devrig_onboarding_action",
            project,
            mapOf("action" to "widget", "widget_action" to action.name),
        )
        when (action) {
            DevrigWidgetAction.INSTALL, DevrigWidgetAction.UPDATE -> DevrigSetupRunner().runInstall(project)
            DevrigWidgetAction.OPEN_SETTINGS ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSteroidConfigurable::class.java)
        }
    }
}
