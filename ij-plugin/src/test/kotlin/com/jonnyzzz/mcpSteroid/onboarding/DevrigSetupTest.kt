/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
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

    /**
     * What the settings page offers to paste into Cursor (or any other client devrig has no CLI for) must be
     * the registration devrig itself writes — same launcher, same `mcp` subcommand — or the manual path
     * silently stops matching the automatic one.
     */
    @Test
    fun `the stdio snippet points at the stable launcher and matches what devrig registers`() {
        val home = Path.of("/home/u")

        val posix = devrigStdioMcpConfigJson(home, windows = false)
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("/home/u/.mcp-steroid/bin/devrig", listOf("mcp"))),
            posix,
        )

        // The Windows case pins the cmd.exe wrapping and the quoting, not the path separator: this test
        // also runs on POSIX, where Path.resolve joins with '/'. Separators are the bin-path test's job.
        val winHome = Path.of("C:\\Users\\u")
        val launcher = devrigBinPath(winHome, windows = true).toString()
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("cmd.exe", listOf("/d", "/c", "\"$launcher\" mcp"))),
            devrigStdioMcpConfigJson(winHome, windows = true),
        )
    }
}
