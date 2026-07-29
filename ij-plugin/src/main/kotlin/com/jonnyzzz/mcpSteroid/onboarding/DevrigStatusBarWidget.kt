/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
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

/**
 * Whether the widget should occupy status-bar space at all.
 *
 * A plugin should not claim status-bar space permanently, so this widget is a transient onboarding aid
 * rather than a fixture: it exists only while there is something to act on, and removes itself once the
 * migration onto devrig is finished. If the situation regresses — devrig deleted, the Claude plugin
 * disabled, a new release published — it comes back, see [DevrigConnectionStateService.refreshLocalAsync].
 *
 * An unknown state (before the first refresh) reports `false` so a fully-connected IDE never flashes a
 * widget it is about to remove again. The first refresh runs at project open from
 * [DevrigOnboardingService], which materialises the widget if there is anything to offer.
 */
fun shouldShowDevrigWidget(state: DevrigConnectionState?): Boolean =
    state != null && state.decision != OnboardingDecision.ALREADY_CONNECTED

/**
 * The calm counterpart to the startup offer: the notification is one balloon among many at the noisiest
 * moment of the session and is easy to miss, so the same state also sits in the status bar — until the
 * migration is done, at which point [isAvailable] takes the widget away again.
 *
 * The user can also remove it at any time: [isConfigurable] and [isEnabledByDefault] are left at their
 * platform defaults (both `true`), so right-clicking the status bar offers to hide this widget and the
 * choice is persisted by the platform. We deliberately do not reimplement that in our own popup.
 */
class DevrigStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = DEVRIG_STATUS_WIDGET_ID

    override fun getDisplayName(): String = "devrig"

    /**
     * Availability is re-read only when the platform creates widgets or when
     * [com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager.updateWidget] is called — the
     * status bar never polls. [DevrigConnectionStateService] makes that call whenever this flips.
     */
    override fun isAvailable(project: Project): Boolean =
        shouldShowDevrigWidget(DevrigConnectionStateService.getInstance().current())

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

    override fun install(statusBar: StatusBar) {
        // Normally unreachable: the factory reports unavailable while the state is unknown, so the widget
        // is only created after a refresh. Kept as a safety net — if some other path ever creates it
        // early, this stops the widget sitting on "devrig: …" forever.
        if (DevrigConnectionStateService.getInstance().current() == null) {
            DevrigConnectionStateService.getInstance().refreshAsync()
        }
    }

    override fun dispose() = Unit

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = Presentation()

    private inner class Presentation : StatusBarWidget.TextPresentation {
        override fun getText(): String = when (state()?.decision) {
            null -> "devrig: …"
            OnboardingDecision.ALREADY_CONNECTED -> "devrig: connected"
            OnboardingDecision.OFFER_UPDATE -> "devrig: update available"
            OnboardingDecision.OFFER_ENABLE -> "devrig: not connected"
            OnboardingDecision.OFFER_GET_AGENT -> "devrig: no agent"
        }

        override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

        // The tooltip states the situation; the click opens the popup that explains it and offers the
        // action — so every branch ends with the same "click for details" promise the click keeps.
        override fun getTooltipText(): String {
            val state = state() ?: return "Checking whether this IDE is connected to an AI agent — click for details"
            return when (state.decision) {
                OnboardingDecision.ALREADY_CONNECTED ->
                    "Claude Code can drive this IDE through devrig" +
                        (state.installedVersion?.let { " ($it)" } ?: "") + " — click for details"
                OnboardingDecision.OFFER_UPDATE ->
                    "devrig ${state.installedVersion ?: ""} is behind " +
                        "${state.latestBaseVersion ?: "the current release"} — click for details"
                OnboardingDecision.OFFER_ENABLE ->
                    "This IDE is not bridged to Claude Code yet — click for details"
                OnboardingDecision.OFFER_GET_AGENT ->
                    "No Claude Code CLI found on this machine — click for details"
            }
        }

        // A click must explain before it acts: starting a ~611 MB download from a single unexplained click
        // on a status-bar label would be hostile, and doing nothing (the earlier behaviour of quietly
        // launching a background task) reads as a dead widget. So the click opens a small popup that says
        // what devrig is and puts the download behind one labelled button.
        override fun getClickConsumer(): com.intellij.util.Consumer<MouseEvent> =
            com.intellij.util.Consumer { event -> showPopup(event) }

        private fun state() = DevrigConnectionStateService.getInstance().current()
    }

    private fun showPopup(event: MouseEvent) {
        val content = devrigWidgetPopupContent(DevrigConnectionStateService.getInstance().current())
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

        actionButton.addActionListener {
            popup.closeOk(null)
            runWidgetAction(content.action)
        }
        // Both exits close the popup first — otherwise it lingers over the IDE while the browser or the
        // progress bar takes over.
        docs.addHyperlinkListener {
            popup.closeOk(null)
            BrowserUtil.browse(McpSteroidConfigurable.DEVRIG_DOCS_URL)
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
            DevrigWidgetAction.INSTALL, DevrigWidgetAction.UPDATE -> DevrigSetupRunner().runEnable(project)
            DevrigWidgetAction.OPEN_SETTINGS ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSteroidConfigurable::class.java)
            DevrigWidgetAction.LEARN_HOW -> BrowserUtil.browse(McpSteroidConfigurable.DEVRIG_DOCS_URL)
        }
    }
}
