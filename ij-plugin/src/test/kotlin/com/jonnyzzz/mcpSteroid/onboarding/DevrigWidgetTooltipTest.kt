/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the widget tooltip's wording. Two rules: every branch ends with the "click for details" promise
 * the click keeps, and no branch overclaims — while the deprecated direct-HTTP path exists an agent can
 * still reach the IDE without devrig, so the missing-devrig tooltip must offer, not threaten.
 */
class DevrigWidgetTooltipTest {

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
    fun `a missing devrig offers the install without overclaiming`() {
        val tooltip = devrigWidgetTooltip(state(devrigInstalled = false, installedVersion = null))
        assertTrue(tooltip, tooltip.contains("not installed"))
        assertTrue(tooltip, tooltip.contains("install it to bridge an agent to this IDE"))
        // False while the deprecated direct-HTTP path works — an agent CAN still reach the IDE.
        assertFalse("must not claim agents are cut off: $tooltip", tooltip.contains("no agent can reach"))
    }

    @Test
    fun `a stale devrig names both versions`() {
        val tooltip = devrigWidgetTooltip(state(installedVersion = "0.100", latestBaseVersion = "0.101"))
        assertTrue(tooltip, tooltip.contains("0.100"))
        assertTrue(tooltip, tooltip.contains("0.101"))
    }

    @Test
    fun `a ready devrig names the installed version`() {
        val tooltip = devrigWidgetTooltip(state())
        assertTrue(tooltip, tooltip.contains("1.0.0"))
        assertTrue(tooltip, tooltip.contains("bridges your AI agent"))
    }

    @Test
    fun `every branch ends with the click-for-details promise the click keeps`() {
        val states = listOf(
            state(devrigInstalled = false, installedVersion = null, latestBaseVersion = null),
            state(installedVersion = "0.100", latestBaseVersion = "0.101"),
            state(),
            state(latestBaseVersion = null),
        )
        for (s in states) {
            val tooltip = devrigWidgetTooltip(s)
            assertTrue("must end with the promise for $s: $tooltip", tooltip.endsWith("click for details"))
        }
    }
}
