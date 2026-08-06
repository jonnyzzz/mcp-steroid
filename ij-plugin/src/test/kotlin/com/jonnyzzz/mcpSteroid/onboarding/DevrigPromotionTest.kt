/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

// The notification-group tests live with the group's owner: McpSteroidNotificationsTest.
class DevrigPromotionTest : BasePlatformTestCase() {
    fun `test promotion starts explicitly and repeated starts are no-ops`() {
        // Instantiating the service is side-effect free; the startup activity starts the one-shot
        // by name. With the registry gate off (the default) and the 12-35s delay, nothing is shown
        // from a test. The second call exercises the once-per-run guard.
        val promotion = DevrigPromotion.getInstance()
        assertNotNull(promotion)
        promotion.startPromotion()
        promotion.startPromotion()
    }

    fun `test the promotion delay range is 12 to 35 seconds, as specified`() {
        assertEquals(12.seconds, DevrigPromotion.PROMOTION_DELAY_RANGE.start)
        assertEquals(35.seconds, DevrigPromotion.PROMOTION_DELAY_RANGE.endInclusive)
    }

    fun `test every promotion delay draw lands inside the range and the draws vary`() {
        val draws = (0L until 1000L).map { seed ->
            DevrigPromotion.randomPromotionDelay(Random(seed))
        }
        for (draw in draws) {
            assertTrue(
                "the owner's spec says random 12-35 seconds after start; got $draw",
                draw in DevrigPromotion.PROMOTION_DELAY_RANGE,
            )
        }
        // Random, not a constant that merely lives inside the range.
        assertTrue(
            "expected many distinct draws across 1000 seeds; got ${draws.distinct().size}",
            draws.distinct().size > 100,
        )
    }

    fun `test the promotion is registry-gated off by default`() {
        assertFalse(
            "the promotion must stay opt-in until the owner ships it on",
            devrigPromotionEnabled(),
        )
    }
}
