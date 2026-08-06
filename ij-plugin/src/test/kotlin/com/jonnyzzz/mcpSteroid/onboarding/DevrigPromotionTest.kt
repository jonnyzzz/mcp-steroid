/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.time.Duration.Companion.seconds

// The notification-group tests live with the group's owner: McpSteroidNotificationsTest.
class DevrigPromotionTest : BasePlatformTestCase() {
    fun `test promotion starts explicitly and repeated starts are no-ops`() {
        // Instantiating the service is side-effect free; the startup activity starts the one-shot
        // by name. With the registry gate off (the default) and the 12s delay, nothing is shown
        // from a test. The second call exercises the once-per-run guard.
        val promotion = DevrigPromotion.getInstance()
        assertNotNull(promotion)
        promotion.startPromotion()
        promotion.startPromotion()
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
