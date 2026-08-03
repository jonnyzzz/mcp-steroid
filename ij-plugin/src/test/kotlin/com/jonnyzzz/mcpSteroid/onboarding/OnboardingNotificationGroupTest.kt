/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.Duration

class OnboardingNotificationGroupTest : BasePlatformTestCase() {
    fun `test onboarding notification group is registered`() {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_NOTIFICATION_GROUP)
        assertNotNull("The onboarding notification group must be registered in plugin.xml", group)
    }

    fun `test onboarding notification group is sticky`() {
        // A plain BALLOON auto-hides in ~10s, at project open — which is exactly why the migration offer
        // used to go unnoticed. The offer must stay on screen until the user acts on it.
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_NOTIFICATION_GROUP)
        assertEquals(
            "The onboarding offer must not auto-hide",
            NotificationDisplayType.STICKY_BALLOON,
            group.displayType,
        )
    }

    fun `test install results are reported in an auto-hiding group`() {
        // Stickiness has to be earned. "devrig is installed" reports an action the user started and is
        // watching, and the same fact lands on the settings page and the widget — leaving it on screen
        // until dismissed spends attention twice for one event.
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_RESULTS_NOTIFICATION_GROUP)
        assertNotNull("The install-results notification group must be registered in plugin.xml", group)
        assertEquals(
            "An install result the user is watching must auto-hide",
            NotificationDisplayType.BALLOON,
            group.displayType,
        )
    }

    fun `test Later snoozes the startup offer instead of only closing the balloon`() {
        val properties = PropertiesComponent.getInstance()
        val previous = properties.getValue(DevrigOnboardingService.OFFER_SNOOZED_UNTIL_KEY)
        try {
            properties.unsetValue(DevrigOnboardingService.OFFER_SNOOZED_UNTIL_KEY)
            val service = DevrigOnboardingService.getInstance()
            assertEquals("nothing is snoozed until the user says Later", 0L, service.offerSnoozedUntilMs())

            service.snoozeOffer()

            val remainingMs = service.offerSnoozedUntilMs() - System.currentTimeMillis()
            val snoozeMs = Duration.ofDays(OFFER_SNOOZE_DAYS).toMillis()
            assertTrue(
                "Later must hold for about OFFER_SNOOZE_DAYS; ${remainingMs}ms left of ${snoozeMs}ms",
                remainingMs in (snoozeMs - 60_000)..snoozeMs,
            )
        } finally {
            if (previous == null) {
                properties.unsetValue(DevrigOnboardingService.OFFER_SNOOZED_UNTIL_KEY)
            } else {
                properties.setValue(DevrigOnboardingService.OFFER_SNOOZED_UNTIL_KEY, previous)
            }
        }
    }

    fun `test onboarding service instantiates`() {
        assertNotNull(DevrigOnboardingService.getInstance())
    }

    fun `test connection state service instantiates`() {
        assertNotNull(DevrigConnectionStateService.getInstance())
    }

    fun `test devrig status bar widget factory is registered`() {
        // The always-visible fallback: whatever happens to the balloon, the status bar keeps showing
        // whether this IDE is bridged, and one click starts the same install/update flow.
        val factory = StatusBarWidgetFactory.EP_NAME.extensionList
            .singleOrNull { it.id == DEVRIG_STATUS_WIDGET_ID }
        assertNotNull("The devrig status-bar widget factory must be registered in plugin.xml", factory)
        assertTrue(
            "The factory must be our implementation, got ${factory!!.javaClass.name}",
            factory is DevrigStatusBarWidgetFactory,
        )
        assertEquals("devrig", factory.displayName)
    }
}
