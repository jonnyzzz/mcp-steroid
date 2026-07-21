/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.NotificationGroupManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OnboardingNotificationGroupTest : BasePlatformTestCase() {
    fun `test onboarding notification group is registered`() {
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")
        assertNotNull("The onboarding notification group must be registered in plugin.xml", group)
    }

    fun `test onboarding service instantiates`() {
        assertNotNull(DevrigOnboardingService.getInstance())
    }
}
