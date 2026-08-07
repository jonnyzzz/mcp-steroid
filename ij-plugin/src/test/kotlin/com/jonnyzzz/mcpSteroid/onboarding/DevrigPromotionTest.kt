/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ApplicationInfo
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

    // --- the balloon body: it says what devrig IS, in the website's framing, and nothing machine-local ---

    fun `test the balloon says what devrig is`() {
        // Owner call: the body explains devrig — the CLI and MCP tooling connecting an agent to this
        // IDE — grounded in the published website pitch.
        val body = DevrigPromotion.devrigInstallOfferBody()
        assertTrue(body, body.contains("CLI and MCP"))
        assertTrue(body, body.contains("Claude Code, Codex"))
    }

    fun `test the balloon carries no download size and no local path`() {
        // Owner call: the old size figure was wrong and the devrig-home computation was not correct
        // either; this class must not compute machine state for copy at all.
        val body = DevrigPromotion.devrigInstallOfferBody()
        assertFalse("no size claim: $body", body.contains("MB"))
        assertFalse("no local path: $body", body.contains(".mcp-steroid"))
        assertFalse("no home path: $body", body.contains("/home/") || body.contains("\\Users\\"))
    }

    /**
     * The balloon's "What is devrig?" link goes to the site ROOT with the IDE build under a parameter
     * of its own — `fromIntelliJInstallAction`, distinct from the settings page's `fromIntelliJ` — so
     * the site can tell the balloon apart from the settings link. The build is injectable precisely
     * so this test can pin the exact shape.
     */
    fun `test the install offer site link targets the site root with its own query param`() {
        assertEquals(
            "https://devrig.dev/?fromIntelliJInstallAction=IU-261.25134.95",
            DevrigPromotion.installOfferSiteUrl("IU-261.25134.95"),
        )

        // The parameter name stays pinned on its own: the site keys its attribution on it.
        assertEquals("fromIntelliJInstallAction", DevrigPromotion.FROM_INTELLIJ_INSTALL_ACTION_PARAM)

        // The build value is URL-encoded, so an unexpected build string cannot corrupt the query.
        assertEquals(
            "https://devrig.dev/?fromIntelliJInstallAction=IU-261%2F95%26x",
            DevrigPromotion.installOfferSiteUrl("IU-261/95&x"),
        )

        // Production callers take the default — the running IDE's own build.
        val build = ApplicationInfo.getInstance().build.asString()
        assertEquals(
            DevrigPromotion.installOfferSiteUrl(build),
            DevrigPromotion.installOfferSiteUrl(),
        )
        assertTrue(
            "the default URL must start with the root + param prefix; got '${DevrigPromotion.installOfferSiteUrl()}'",
            DevrigPromotion.installOfferSiteUrl().startsWith("https://devrig.dev/?fromIntelliJInstallAction="),
        )
    }
}
