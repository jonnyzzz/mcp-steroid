/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Even where the widget is switched on, it must not hold status-bar space once there is nothing to act
 * on, and must come back when the situation regresses. Both are decided by [shouldShowDevrigWidget],
 * which is pure so the rule is pinned here rather than only observable by running an IDE.
 */
class DevrigWidgetVisibilityTest {

    private fun state(
        devrigInstalled: Boolean = true,
        installedVersion: String? = "1.0.0",
        latestBaseVersion: String? = "1.0.0",
    ) = DevrigConnectionState(
        devrigInstalled = devrigInstalled,
        installedVersion = installedVersion,
        latestBaseVersion = latestBaseVersion,
    )

    @Test
    fun `hidden once devrig is installed and current`() {
        val ready = state()
        assertEquals(OnboardingDecision.DEVRIG_READY, ready.decision)
        assertFalse("nothing to act on must not keep the widget", shouldShowDevrigWidget(ready))
    }

    @Test
    fun `shown whenever there is something to act on`() {
        assertTrue("devrig missing", shouldShowDevrigWidget(state(devrigInstalled = false, installedVersion = null)))
    }

    @Test
    fun `comes back when the installed devrig falls behind`() {
        val outdated = state(installedVersion = "1.0.0", latestBaseVersion = "2.0.0")
        assertEquals(OnboardingDecision.OFFER_UPDATE, outdated.decision)
        assertTrue(shouldShowDevrigWidget(outdated))
    }

    @Test
    fun `an unchecked version never reads as outdated`() {
        // localState() leaves latestBaseVersion null: not knowing must not become a nag.
        val unchecked = state(latestBaseVersion = null)
        assertEquals(OnboardingDecision.DEVRIG_READY, unchecked.decision)
        assertFalse(shouldShowDevrigWidget(unchecked))
    }

    @Test
    fun `regression after devrig was ready flips visibility back on`() {
        // The transition the focus re-check exists for: devrig deleted while the widget was already gone.
        assertFalse(shouldShowDevrigWidget(state()))
        assertTrue(shouldShowDevrigWidget(state(devrigInstalled = false, installedVersion = null)))
    }
}
