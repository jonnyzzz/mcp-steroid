/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The popup is the one screen a user actually reads before agreeing to a ~611 MB download, so its wording
 * and the action behind its button are pinned here rather than left to Swing code.
 */
class DevrigWidgetPopupContentTest {
    private fun state(
        installed: Boolean = true,
        version: String? = "0.101",
        latest: String? = "0.101",
        claude: Boolean = true,
        pluginEnabled: Boolean = true,
    ) = DevrigConnectionState(
        devrigInstalled = installed,
        installedVersion = version,
        latestBaseVersion = latest,
        claudePresent = claude,
        claudePluginEnabled = pluginEnabled,
    )

    @Test
    fun `not connected explains what devrig is and names the download size`() {
        val content = devrigWidgetPopupContent(state(installed = false, version = null, latest = null))
        assertEquals(DevrigWidgetAction.INSTALL, content.action)
        assertTrue(content.title, content.title.contains("not connected"))
        // The size is the one fact a user needs before pressing the button.
        assertTrue(content.message, content.message.contains("611 MB"))
        assertTrue(content.message, content.message.contains("Claude Code"))
        assertTrue(content.actionLabel, content.actionLabel.contains("Download", ignoreCase = true))
    }

    @Test
    fun `a stale devrig offers the update and shows both versions`() {
        val content = devrigWidgetPopupContent(state(version = "0.100", latest = "0.101"))
        assertEquals(DevrigWidgetAction.UPDATE, content.action)
        assertTrue(content.message, content.message.contains("0.100"))
        assertTrue(content.message, content.message.contains("0.101"))
        assertTrue(content.actionLabel, content.actionLabel.contains("Update", ignoreCase = true))
    }

    @Test
    fun `connected state is informational and opens the settings`() {
        val content = devrigWidgetPopupContent(state())
        assertEquals(DevrigWidgetAction.OPEN_SETTINGS, content.action)
        assertTrue(content.title, content.title.contains("connected"))
        assertTrue(content.message, content.message.contains("0.101"))
        // Nothing to download here, so the size must not be advertised.
        assertTrue(content.message, !content.message.contains("611 MB"))
    }

    @Test
    fun `no agent sends the user to the docs`() {
        val content = devrigWidgetPopupContent(state(claude = false))
        assertEquals(DevrigWidgetAction.LEARN_HOW, content.action)
        assertTrue(content.message, content.message.contains("Claude Code CLI"))
    }

    @Test
    fun `clicking before the first refresh still offers the install instead of a dead popup`() {
        // current() is null until the background refresh finishes; the click must not become a no-op.
        val content = devrigWidgetPopupContent(null)
        assertEquals(DevrigWidgetAction.INSTALL, content.action)
        assertTrue(content.actionLabel.isNotBlank())
        assertTrue(content.message.isNotBlank())
    }

    @Test
    fun `every state yields a labelled button and a non-empty explanation`() {
        val states = listOf(
            null,
            state(installed = false, version = null, latest = null),
            state(version = "0.100", latest = "0.101"),
            state(),
            state(claude = false),
            state(pluginEnabled = false),
        )
        for (s in states) {
            val content = devrigWidgetPopupContent(s)
            assertTrue("title empty for $s", content.title.isNotBlank())
            assertTrue("message empty for $s", content.message.isNotBlank())
            assertTrue("action label empty for $s", content.actionLabel.isNotBlank())
        }
    }
}
