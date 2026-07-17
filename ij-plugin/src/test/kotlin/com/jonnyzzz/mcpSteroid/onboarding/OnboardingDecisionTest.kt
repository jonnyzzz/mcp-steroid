/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class OnboardingDecisionTest {

    @Test
    fun `decision truth table`() {
        // No agent CLI -> always offer to get one, regardless of the rest.
        assertEquals(OnboardingDecision.OFFER_GET_AGENT, decideOnboarding(devrigInstalled = true, claudePresent = false, claudePluginEnabled = true))
        assertEquals(OnboardingDecision.OFFER_GET_AGENT, decideOnboarding(devrigInstalled = false, claudePresent = false, claudePluginEnabled = false))
        // Agent present, fully wired -> nothing to do.
        assertEquals(OnboardingDecision.ALREADY_CONNECTED, decideOnboarding(devrigInstalled = true, claudePresent = true, claudePluginEnabled = true))
        // Agent present but plugin not enabled -> offer enable.
        assertEquals(OnboardingDecision.OFFER_ENABLE, decideOnboarding(devrigInstalled = true, claudePresent = true, claudePluginEnabled = false))
        // Agent present, plugin key set but devrig missing -> still offer enable (install devrig).
        assertEquals(OnboardingDecision.OFFER_ENABLE, decideOnboarding(devrigInstalled = false, claudePresent = true, claudePluginEnabled = true))
    }

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

    @Test
    fun `findClaudeBinary scans PATH then the local-bin fallback`() {
        val home = Files.createTempDirectory("home")
        assertNull(findClaudeBinary(pathEnv = null, userHome = home, windows = false))

        // On PATH.
        val pdir = Files.createTempDirectory("p")
        val claude = Files.createFile(pdir.resolve("claude"))
        assertEquals(claude, findClaudeBinary(pathEnv = pdir.toString(), userHome = home, windows = false))

        // Fallback ~/.local/bin/claude when not on PATH.
        val localBin = Files.createDirectories(home.resolve(".local").resolve("bin"))
        val fallback = Files.createFile(localBin.resolve("claude"))
        assertEquals(fallback, findClaudeBinary(pathEnv = "", userHome = home, windows = false))
    }

    @Test
    fun `isClaudePluginEnabled reads the enabledPlugins boolean strictly`() {
        assertFalse(isClaudePluginEnabled(null))
        assertFalse(isClaudePluginEnabled("{}"))
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"other@mp":true}}"""))
        assertTrue(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@mcp-steroid":true}}"""))
        // A quoted string "true" is NOT the JSON boolean true.
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@mcp-steroid":"true"}}"""))
        // Malformed JSON -> treated as not enabled, no crash.
        assertFalse(isClaudePluginEnabled("{ not json"))
    }
}
