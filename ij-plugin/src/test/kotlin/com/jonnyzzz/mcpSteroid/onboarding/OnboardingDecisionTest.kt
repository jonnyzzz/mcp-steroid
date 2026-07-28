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
        // Remaining combinations of the 8 (devrigInstalled x claudePresent x claudePluginEnabled) truth table.
        assertEquals(OnboardingDecision.OFFER_GET_AGENT, decideOnboarding(devrigInstalled = false, claudePresent = false, claudePluginEnabled = true))
        assertEquals(OnboardingDecision.OFFER_ENABLE, decideOnboarding(devrigInstalled = false, claudePresent = true, claudePluginEnabled = false))
        assertEquals(OnboardingDecision.OFFER_GET_AGENT, decideOnboarding(devrigInstalled = true, claudePresent = false, claudePluginEnabled = false))
    }

    @Test
    fun `a stale devrig on a wired machine is offered as an update, not treated as done`() {
        // The plugin's job is migration onto a CURRENT devrig, so "installed" alone is not success.
        assertEquals(
            OnboardingDecision.OFFER_UPDATE,
            decideOnboarding(devrigInstalled = true, claudePresent = true, claudePluginEnabled = true, devrigOutdated = true),
        )
        // Outdated is irrelevant while something more fundamental is missing — that is still OFFER_ENABLE…
        assertEquals(
            OnboardingDecision.OFFER_ENABLE,
            decideOnboarding(devrigInstalled = false, claudePresent = true, claudePluginEnabled = true, devrigOutdated = true),
        )
        assertEquals(
            OnboardingDecision.OFFER_ENABLE,
            decideOnboarding(devrigInstalled = true, claudePresent = true, claudePluginEnabled = false, devrigOutdated = true),
        )
        // …and no agent still wins over everything.
        assertEquals(
            OnboardingDecision.OFFER_GET_AGENT,
            decideOnboarding(devrigInstalled = true, claudePresent = false, claudePluginEnabled = true, devrigOutdated = true),
        )
    }

    @Test
    fun `installedDevrigVersion reads the version out of the launcher script`() {
        // Exactly what devrig's BinLauncher.renderPosixLauncher writes.
        val posix = """
            #!/bin/sh
            # devrig launcher
            DEVRIG_JAVA_HOME="/Users/u/.mcp-steroid/binaries/jdk-macos-arm64-0.101-aaaaaaaaaaaa/jdk"; export DEVRIG_JAVA_HOME
            exec "/Users/u/.mcp-steroid/binaries/devrig-macos-arm64-0.101-bbbbbbbbbbbb/devrig-0.101/bin/devrig" "${'$'}@"
        """.trimIndent()
        assertEquals("0.101", installedDevrigVersion(posix))

        // A snapshot build (what a local :npx-kt:installDist produces) keeps its full version string.
        val snapshot = """exec "/home/u/.mcp-steroid/binaries/devrig-linux-x64-0.100-cccccccccccc/devrig-0.100.19999-SNAPSHOT-c6568a61/bin/devrig" "${'$'}@""""
        assertEquals("0.100.19999-SNAPSHOT-c6568a61", installedDevrigVersion(snapshot))

        // Windows `.cmd` hands off with `call` and backslashes.
        val windows = """
            @echo off
            set "DEVRIG_JAVA_HOME=C:\Users\u\.mcp-steroid\binaries\jdk-windows-x64-0.101-aaaaaaaaaaaa\jdk"
            call "C:\Users\u\.mcp-steroid\binaries\devrig-windows-x64-0.101-bbbbbbbbbbbb\devrig-0.101\bin\devrig.bat" %*
        """.trimIndent()
        assertEquals("0.101", installedDevrigVersion(windows))
    }

    @Test
    fun `installedDevrigVersion returns null instead of guessing`() {
        assertNull(installedDevrigVersion(null))
        assertNull(installedDevrigVersion(""))
        assertNull(installedDevrigVersion("   "))
        // No exec/call handoff at all.
        assertNull(installedDevrigVersion("#!/bin/sh\necho hello\n"))
        // Handoff present, but the tree is not the versioned layout we know.
        assertNull(installedDevrigVersion("""exec "/usr/local/bin/devrig" "${'$'}@""""))
        assertNull(installedDevrigVersion("""exec "/opt/tools/custom/bin/devrig" "${'$'}@""""))
    }

    @Test
    fun `isDevrigOutdated only fires when we actually know the user is behind`() {
        assertTrue(isDevrigOutdated("0.100", "0.101"))
        assertFalse(isDevrigOutdated("0.101", "0.101"))
        // A snapshot of the current release counts as current (same semantics as the plugin's own check).
        assertFalse(isDevrigOutdated("0.101-SNAPSHOT-abc1234", "0.101"))
        assertTrue(isDevrigOutdated("0.100.19999-SNAPSHOT-c6568a61", "0.101"))
        // Unknown inputs must never produce a nag.
        assertFalse(isDevrigOutdated(null, "0.101"))
        assertFalse(isDevrigOutdated("0.100", null))
        assertFalse(isDevrigOutdated("0.100", ""))
        assertFalse(isDevrigOutdated(null, null))
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
    fun `findClaudeBinary splits a semicolon-separated PATH on windows`() {
        val home = Files.createTempDirectory("home-win")
        val dir1 = Files.createTempDirectory("p1-win")
        val dir2 = Files.createTempDirectory("p2-win")
        val claudeExe = Files.createFile(dir2.resolve("claude.exe"))

        // If ':' were used as the separator (runtime-OS default), this would be parsed as a single
        // malformed entry and claude.exe would never be found.
        assertEquals(
            claudeExe,
            findClaudeBinary(pathEnv = "${dir1};${dir2}", userHome = home, windows = true),
        )
    }

    @Test
    fun `isClaudePluginEnabled reads the enabledPlugins boolean strictly`() {
        assertFalse(isClaudePluginEnabled(null))
        assertFalse(isClaudePluginEnabled("{}"))
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"other@mp":true}}"""))
        // The key is `devrig@<marketplace name from .claude-plugin/marketplace.json>` — pinned as a
        // literal on purpose so renaming the marketplace without updating the constant fails here.
        assertTrue(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@jonnyzzz":true}}"""))
        // The stale pre-fix key must NOT count as enabled.
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@mcp-steroid":true}}"""))
        // A quoted string "true" is NOT the JSON boolean true.
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@jonnyzzz":"true"}}"""))
        // Malformed JSON -> treated as not enabled, no crash.
        assertFalse(isClaudePluginEnabled("{ not json"))
    }
}
