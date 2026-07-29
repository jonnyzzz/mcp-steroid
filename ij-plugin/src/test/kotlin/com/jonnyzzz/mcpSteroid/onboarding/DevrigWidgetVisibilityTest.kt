/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget must not hold status-bar space once there is nothing to act on, and must come back when the
 * situation regresses. Both are decided by [shouldShowDevrigWidget], which is pure so the rule is pinned
 * here rather than only observable by running an IDE.
 */
class DevrigWidgetVisibilityTest {

    private fun state(
        devrigInstalled: Boolean = true,
        installedVersion: String? = "1.0.0",
        latestBaseVersion: String? = "1.0.0",
        claudePresent: Boolean = true,
        claudePluginEnabled: Boolean = true,
    ) = DevrigConnectionState(
        devrigInstalled = devrigInstalled,
        installedVersion = installedVersion,
        latestBaseVersion = latestBaseVersion,
        claudePresent = claudePresent,
        claudePluginEnabled = claudePluginEnabled,
    )

    @Test
    fun `hidden once everything is connected`() {
        val connected = state()
        assertEquals(OnboardingDecision.ALREADY_CONNECTED, connected.decision)
        assertFalse("a finished migration must not keep the widget", shouldShowDevrigWidget(connected))
    }

    @Test
    fun `hidden while the state is still unknown`() {
        // Otherwise a fully-connected IDE flashes a widget for the duration of the first refresh and then
        // takes it away again.
        assertFalse(shouldShowDevrigWidget(null))
    }

    @Test
    fun `shown whenever there is something to act on`() {
        assertTrue("devrig missing", shouldShowDevrigWidget(state(devrigInstalled = false, installedVersion = null)))
        assertTrue("plugin not enabled", shouldShowDevrigWidget(state(claudePluginEnabled = false)))
        assertTrue("no agent on the machine", shouldShowDevrigWidget(state(claudePresent = false)))
    }

    @Test
    fun `comes back when the installed devrig falls behind`() {
        val outdated = state(installedVersion = "1.0.0", latestBaseVersion = "2.0.0")
        assertEquals(OnboardingDecision.OFFER_UPDATE, outdated.decision)
        assertTrue(shouldShowDevrigWidget(outdated))
    }

    @Test
    fun `regression after being connected flips visibility back on`() {
        // The transition the focus re-check exists for: devrig deleted while the widget was already gone.
        assertFalse(shouldShowDevrigWidget(state()))
        assertTrue(shouldShowDevrigWidget(state(devrigInstalled = false, installedVersion = null)))
    }
}
