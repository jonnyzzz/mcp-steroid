/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.time.Duration.Companion.seconds

class DevrigPromotionTest : BasePlatformTestCase() {
    fun `test the single notification group is registered and auto-hides`() {
        // ONE group for everything the plugin says: plugin updates, the devrig promotion, and
        // install/register results. Plain BALLOON — the promotion balloon may disappear (owner call);
        // anything missed stays in the Notifications tool window.
        val group = NotificationGroupManager.getInstance()
            .getNotificationGroup(MCP_STEROID_NOTIFICATION_GROUP)
        assertNotNull("The plugin's notification group must be registered in plugin.xml", group)
        assertEquals(
            "The promotion balloon must NOT be sticky",
            NotificationDisplayType.BALLOON,
            group.displayType,
        )
    }

    fun `test the retired onboarding groups are gone`() {
        // The sticky onboarding group and the separate results group were merged into the one above.
        // A leftover registration would resurrect the split (and the sticky balloon) silently.
        val manager = NotificationGroupManager.getInstance()
        assertFalse(
            "the sticky onboarding group must not be registered any more",
            manager.isGroupRegistered("jonnyzzz.mcp.steroid.onboarding"),
        )
        assertFalse(
            "the separate results group must not be registered any more",
            manager.isGroupRegistered("jonnyzzz.mcp.steroid.onboarding.results"),
        )
    }

    fun `test promotion service instantiates`() {
        // Instantiating the service arms its one-shot coroutine; with the registry gate off (the
        // default) and the 12s delay, nothing is shown from a test.
        assertNotNull(DevrigPromotion.getInstance())
    }

    fun `test the promotion waits 10 to 15 seconds, as specified`() {
        assertTrue(
            "the owner's spec says 10-15 seconds after start; got ${DevrigPromotion.PROMOTION_DELAY}",
            DevrigPromotion.PROMOTION_DELAY in 10.seconds..15.seconds,
        )
    }

    fun `test the promotion is registry-gated off by default`() {
        assertFalse(
            "the promotion must stay opt-in until the owner ships it on",
            devrigPromotionEnabled(),
        )
    }
}
