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
            DevrigPromotion.devrigPromotionEnabled(),
        )
    }

    // --- the balloon body: the cost disclosure a one-click install surface owes the user ---
    // The Install button starts a ~611 MB download into the devrig home; the settings page disclosed
    // that cost and destination, so the balloon — an identical one-click surface — must too.

    fun `test the install offer balloon discloses the download size and destination`() {
        val body = DevrigPromotion.devrigInstallOfferBody("/home/user/.mcp-steroid")
        assertTrue(body, body.contains("Downloads ~611 MB into <code>/home/user/.mcp-steroid</code>."))
    }

    fun `test the balloon still says what devrig is for`() {
        // The disclosure is appended, not a replacement: the body must keep explaining the value first.
        val body = DevrigPromotion.devrigInstallOfferBody("/home/user/.mcp-steroid")
        assertTrue(body, body.contains("bridges"))
        assertTrue(body, body.indexOf("bridges") < body.indexOf("Downloads"))
    }
}
