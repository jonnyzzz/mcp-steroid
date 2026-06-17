/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The user-facing launcher invocation is the single source of truth shared by agent registration and
 * the docs. Each OS branch is its own declared test (no parameterization) so a failure names the exact
 * platform path it exercised.
 */
class DevrigUserLauncherTest {
    private val home = HomePaths(Path.of("/home/u/.mcp-steroid"))

    @Test
    fun `path on posix is bin slash devrig`() {
        assertEquals(Path.of("/home/u/.mcp-steroid/bin/devrig"), DevrigUserLauncher.path(home, windows = false))
    }

    @Test
    fun `path on windows is bin slash devrig cmd`() {
        // Separator-agnostic: assert on path components, not a "/"-suffix string (a Windows test host
        // would render "\\bin\\devrig.cmd").
        val p = DevrigUserLauncher.path(home, windows = true)
        assertEquals("devrig.cmd", p.fileName.toString())
        assertEquals("bin", p.parent.fileName.toString())
    }

    @Test
    fun `posix invocation execs the wrapper directly with no JAVA_HOME`() {
        val cmd = DevrigUserLauncher.invocation(home, listOf("mcp"), windows = false)
        assertEquals("/home/u/.mcp-steroid/bin/devrig", cmd.command)
        assertEquals(listOf("mcp"), cmd.args)
    }

    @Test
    fun `windows invocation runs the quoted cmd shim via cmd exe with no JAVA_HOME`() {
        val cmd = DevrigUserLauncher.invocation(home, listOf("mcp"), windows = true)
        assertEquals("cmd.exe", cmd.command)
        assertEquals(listOf("/d", "/c"), cmd.args.subList(0, 2))
        // /c's argument is a single command line with the launcher path QUOTED (space-safe) — see the
        // Windows path-with-spaces guard — and carries no JAVA_HOME (the wrapper sets DEVRIG_JAVA_HOME).
        val line = cmd.args[2]
        assertTrue(line.startsWith("\""), line)
        assertTrue(line.contains("devrig.cmd\""), line)
        assertTrue(line.endsWith(" mcp"), line)
        assertFalse(line.contains("JAVA_HOME"), line)
    }

    @Test
    fun `windows invocation keeps a spaced home path quoted as one token`() {
        val spaced = HomePaths(Path.of("/c/Users/First Last/.mcp-steroid"))
        val cmd = DevrigUserLauncher.invocation(spaced, listOf("mcp"), windows = true)
        // The whole quoted path (including the space) must sit inside ONE pair of quotes so cmd.exe does
        // not split "First Last" — the regression the bare-argv form risked.
        assertTrue(cmd.args[2].contains("\"/c/Users/First Last/.mcp-steroid/bin/devrig.cmd\""), cmd.args[2])
    }

    @Test
    fun `posix invocation passes through multi-arg backend commands`() {
        val cmd = DevrigUserLauncher.invocation(home, listOf("backend", "--json"), windows = false)
        assertEquals("/home/u/.mcp-steroid/bin/devrig", cmd.command)
        assertEquals(listOf("backend", "--json"), cmd.args)
    }
}
