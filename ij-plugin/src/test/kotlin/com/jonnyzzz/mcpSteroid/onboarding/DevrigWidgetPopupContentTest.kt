/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The popup is a status-bar popup, not documentation. These tests pin two things: the action behind the
 * button for each state, and the length budget — an earlier version explained what devrig is and how many
 * megabytes it downloads, which made it unreadable.
 */
class DevrigWidgetPopupContentTest {
    /** Everything the popup says, excluding the title and the button, must stay under this. */
    private val messageBudget = 70

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

    private val allStates = listOf(
        null,
        state(installed = false, version = null, latest = null),
        state(version = "0.100", latest = "0.101"),
        state(),
        state(claude = false),
        state(pluginEnabled = false),
    )

    @Test
    fun `not connected offers the install`() {
        val content = devrigWidgetPopupContent(state(installed = false, version = null, latest = null))
        assertEquals(DevrigWidgetAction.INSTALL, content.action)
        assertTrue(content.title, content.title.contains("not connected"))
        assertTrue(content.actionLabel, content.actionLabel.contains("Download", ignoreCase = true))
    }

    @Test
    fun `a stale devrig offers the update and names both versions`() {
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
    }

    @Test
    fun `no agent sends the user to the docs`() {
        val content = devrigWidgetPopupContent(state(claude = false))
        assertEquals(DevrigWidgetAction.LEARN_HOW, content.action)
        assertTrue(content.message, content.message.contains("Claude Code"))
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
    fun `the popup stays terse - no essays, no install sizes, no next-step instructions`() {
        for (s in allStates) {
            val content = devrigWidgetPopupContent(s)
            assertTrue(
                "message too long (${content.message.length} > $messageBudget) for $s: ${content.message}",
                content.message.length <= messageBudget,
            )
            // What devrig is, how big it is, and what to do next belong in the docs behind "Learn more".
            assertTrue("must not advertise the download size for $s", !content.message.contains("MB"))
            assertTrue("must not mention the bundled JDK for $s", !content.message.contains("JDK"))
        }
    }

    @Test
    fun `every state yields a labelled button and lines that render as separate paragraphs`() {
        for (s in allStates) {
            val content = devrigWidgetPopupContent(s)
            assertTrue("title empty for $s", content.title.isNotBlank())
            assertTrue("action label empty for $s", content.actionLabel.isNotBlank())
            assertTrue("no lines for $s", content.lines.isNotEmpty())
            for (line in content.lines) {
                assertTrue("blank line for $s", line.isNotBlank())
                // Each line is rendered as its own paragraph, so it must not smuggle in markup itself.
                assertTrue("line must not contain HTML: $line", !line.contains("<"))
            }
        }
    }
}
