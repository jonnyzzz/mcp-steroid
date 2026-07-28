/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OnboardingNotificationGroupTest : BasePlatformTestCase() {
    fun `test onboarding notification group is registered`() {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")
        assertNotNull("The onboarding notification group must be registered in plugin.xml", group)
    }

    fun `test onboarding notification group is sticky`() {
        // A plain BALLOON auto-hides in ~10s, at project open — which is exactly why the migration offer
        // used to go unnoticed. The offer must stay on screen until the user acts on it.
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")
        assertEquals(
            "The onboarding offer must not auto-hide",
            NotificationDisplayType.STICKY_BALLOON,
            group.displayType,
        )
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
