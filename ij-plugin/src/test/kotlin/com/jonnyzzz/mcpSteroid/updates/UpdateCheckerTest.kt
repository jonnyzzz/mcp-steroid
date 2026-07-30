/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.updates

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.getBuildVersion
import com.jonnyzzz.mcpSteroid.getPluginVersion
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion

/**
 * Tests for UpdateChecker version comparison logic.
 */
class UpdateCheckerTest : BasePlatformTestCase() {

    /**
     * Test that base version extraction works correctly.
     */
    fun testExtractBaseVersion() {
        // Simple version
        assertEquals("0.86.0", extractBaseVersion("0.86.0"))

        // SNAPSHOT version
        assertEquals("0.86.0", extractBaseVersion("0.86.0-SNAPSHOT"))

        // Full SNAPSHOT with timestamp and git hash suffix
        assertEquals("0.86.0", extractBaseVersion("0.86.0-SNAPSHOT-20260212-193000-a1b2c3d"))

        // Version with other suffix
        assertEquals("1.2.3", extractBaseVersion("1.2.3-beta1"))
    }

    /**
     * The notification gate: shown iff the promoted version is strictly newer than the
     * current build. Exercises the production gate [UpdateChecker.checkForUpdates] calls.
     */
    fun testUpdateNotificationGate() {
        fun updateAvailable(promotedBase: String, current: String) = DevrigVersion.isUpdateAvailable(
            current = DevrigVersion.parse(current),
            promoted = DevrigVersion.parse(promotedBase),
        )

        // release build <base>-<hash>
        assertTrue(updateAvailable("0.87.0", "0.86.0-a1b2c3d"))
        assertFalse(updateAvailable("0.86.0", "0.86.0-a1b2c3d"))
        assertFalse(updateAvailable("0.85.0", "0.86.0-a1b2c3d"))

        // CI build <base>.<counter>-(gh|jb)-<hash>
        assertTrue(updateAvailable("0.87.0", "0.86.0.441-jb-a1b2c3d"))
        assertFalse(updateAvailable("0.86.0", "0.86.0.441-jb-a1b2c3d"))
    }

    /**
     * A snapshot (local dev) build compares newer than anything promoted — never nagged.
     */
    fun testSnapshotBuildIsNeverNotified() {
        val current = DevrigVersion.parse("0.86.0.19999-SNAPSHOT-a1b2c3d")
        assertTrue(current.isSnapshotBuild)
        assertFalse(DevrigVersion.isUpdateAvailable(current = current, promoted = DevrigVersion.parse("0.87.0")))
        assertFalse(DevrigVersion.isUpdateAvailable(current = current, promoted = DevrigVersion.parse("999.0")))
    }

    /**
     * The generated metadata exposes the typed build version with the snapshot flag baked in.
     */
    fun testGeneratedBuildVersionMetadata() {
        val build = getBuildVersion()
        assertEquals(getPluginVersion(), build.value)
        assertEquals(build.value.contains("SNAPSHOT"), build.isSnapshotBuild)
    }

    /**
     * Test user agent format.
     */
    fun testUserAgentFormat() {
        val userAgent = buildUserAgent("0.86.0-SNAPSHOT", "IU-253.12345")
        assertEquals("MCP-Steroid/0.86.0-SNAPSHOT (IntelliJ/IU-253.12345)", userAgent)
    }

    // Helper methods mirroring UpdateChecker logic for testing

    private fun extractBaseVersion(fullVersion: String): String {
        val snapshotIndex = fullVersion.indexOf("-SNAPSHOT")
        if (snapshotIndex > 0) {
            return fullVersion.substring(0, snapshotIndex)
        }
        val dashIndex = fullVersion.indexOf('-')
        if (dashIndex > 0) {
            return fullVersion.substring(0, dashIndex)
        }
        return fullVersion
    }

    private fun buildUserAgent(pluginVersion: String, ijBuild: String): String {
        return "MCP-Steroid/$pluginVersion (IntelliJ/$ijBuild)"
    }
}
