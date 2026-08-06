/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the published-installer contract: the URLs both halves of the product fetch, and the one-liner
 * every user-facing surface (website install CTA, README, the IDE settings page) shows VERBATIM.
 */
class InstallerHostTest {

    @Test
    fun `the installer URLs are the published ones`() {
        assertEquals("https://devrig.dev/install.sh", devrigInstallerUrl(isWin = false))
        assertEquals("https://devrig.dev/install.ps1", devrigInstallerUrl(isWin = true))
    }

    /**
     * The exact strings `website/layouts/partials/install-cta.html` publishes and the installer
     * templates carry in their headers. A drift here means the IDE settings page shows a command the
     * docs never promoted — change the website and the templates together with this pin, or not at all.
     */
    @Test
    fun `the install one-liner matches the website, verbatim, per OS`() {
        assertEquals("curl -fsSL https://devrig.dev/install.sh | sh", devrigInstallOneLiner(isWin = false))
        assertEquals("irm https://devrig.dev/install.ps1 | iex", devrigInstallOneLiner(isWin = true))
    }
}
