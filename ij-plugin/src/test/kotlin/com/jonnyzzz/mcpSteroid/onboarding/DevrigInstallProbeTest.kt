/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DevrigInstallProbeTest {

    @Test
    fun `devrigInstalled checks the per-OS launcher file`() {
        val home = Files.createTempDirectory("home")
        assertFalse(devrigInstalled(home, windows = false))
        val bin = Files.createDirectories(home.resolve(".mcp-steroid").resolve("bin"))
        Files.createFile(bin.resolve("devrig"))
        assertTrue(devrigInstalled(home, windows = false))
        // Windows looks for devrig.cmd, not devrig.
        assertFalse(devrigInstalled(home, windows = true))
        Files.createFile(bin.resolve("devrig.cmd"))
        assertTrue(devrigInstalled(home, windows = true))
    }
}
