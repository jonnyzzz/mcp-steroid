/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the cost disclosure on the startup balloon. Its Install button starts a ~611 MB download into the
 * devrig home; the settings page disclosed that cost and destination, so the balloon — an identical
 * one-click surface — must too.
 */
class DevrigInstallOfferBodyTest {
    @Test
    fun `the install offer balloon discloses the download size and destination`() {
        val body = devrigInstallOfferBody("/home/user/.mcp-steroid")
        assertTrue(body, body.contains("Downloads ~611 MB into <code>/home/user/.mcp-steroid</code>."))
    }

    @Test
    fun `the balloon still says what devrig is for`() {
        // The disclosure is appended, not a replacement: the body must keep explaining the value first.
        val body = devrigInstallOfferBody("/home/user/.mcp-steroid")
        assertTrue(body, body.contains("bridges"))
        assertTrue(body, body.indexOf("bridges") < body.indexOf("Downloads"))
    }
}
