/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class DevrigSetupTest {
    @Test
    fun `the update directory is devrig's own, so both halves see the same markers`() {
        val home = Path.of("/home/u")
        assertEquals(Path.of("/home/u/.mcp-steroid/update"), devrigUpdateDir(home))
    }

    @Test
    fun `devrig bin path is per-OS`() {
        val home = Path.of("/home/u")
        assertEquals(Path.of("/home/u/.mcp-steroid/bin/devrig"), devrigBinPath(home, windows = false))
        assertEquals(Path.of("/home/u/.mcp-steroid/bin/devrig.cmd"), devrigBinPath(home, windows = true))
    }
}
