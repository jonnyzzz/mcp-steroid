/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The popup is a status-bar popup, not documentation. These tests pin three things: the action behind the
 * button for each state, the length budget — an earlier version explained what devrig is in essay form,
 * which made it unreadable — and the cost disclosure: the install button starts a ~611 MB download, and
 * a one-click surface must say so (with the destination) before the click.
 */
class DevrigWidgetPopupContentTest {
    /**
     * Everything the popup says, excluding the title and the button, must stay under this.
     * Sized for two short lines: the one-sentence pitch plus the mandatory cost-disclosure line
     * ("Downloads ~611 MB into <home>.") — not for essays.
     */
    private val messageBudget = 115

    /** A fixed home so the budget check does not depend on the machine running the test. */
    private val testHome = "/home/user/.mcp-steroid"

    private fun state(
        installed: Boolean = true,
        version: String? = "0.101",
        latest: String? = "0.101",
    ) = DevrigConnectionState(
        devrigInstalled = installed,
        installedVersion = version,
        latestBaseVersion = latest,
    )

    private fun content(state: DevrigConnectionState) = devrigWidgetPopupContent(state, devrigHome = testHome)

    private val allStates = listOf(
        state(installed = false, version = null, latest = null),
        state(version = "0.100", latest = "0.101"),
        state(),
        state(latest = null),
    )

    @Test
    fun `a missing devrig offers the install`() {
        val content = content(state(installed = false, version = null, latest = null))
        assertEquals(DevrigWidgetAction.INSTALL, content.action)
        assertTrue(content.title, content.title.contains("not installed"))
        assertTrue(content.actionLabel, content.actionLabel.contains("Install", ignoreCase = true))
    }

    @Test
    fun `the install offer discloses the download size and destination before the click`() {
        // The button starts a ~611 MB download. The settings page disclosed that cost and where it lands;
        // this popup used to start the identical download with zero disclosure — one short line fixes it.
        val content = content(state(installed = false, version = null, latest = null))
        assertTrue(content.message, content.lines.contains("Downloads ~611 MB into $testHome."))
    }

    @Test
    fun `a stale devrig offers the update and names both versions`() {
        val content = content(state(version = "0.100", latest = "0.101"))
        assertEquals(DevrigWidgetAction.UPDATE, content.action)
        assertTrue(content.message, content.message.contains("0.100"))
        assertTrue(content.message, content.message.contains("0.101"))
        assertTrue(content.actionLabel, content.actionLabel.contains("Update", ignoreCase = true))
    }

    @Test
    fun `a ready devrig is informational and opens the settings`() {
        val content = content(state())
        assertEquals(DevrigWidgetAction.OPEN_SETTINGS, content.action)
        assertTrue(content.title, content.title.contains("ready"))
        assertTrue(content.message, content.message.contains("0.101"))
    }

    @Test
    fun `the popup promises nothing about agents being wired up`() {
        // Installing devrig does not register any agent, so no state may claim an agent can drive the IDE.
        for (s in allStates) {
            val text = content(s).let { it.title + " " + it.message }
            assertTrue("must not name a single agent for $s: $text", !text.contains("Claude"))
        }
    }

    @Test
    fun `the popup stays terse - no essays, no next-step instructions`() {
        for (s in allStates) {
            val content = content(s)
            assertTrue(
                "message too long (${content.message.length} > $messageBudget) for $s: ${content.message}",
                content.message.length <= messageBudget,
            )
            // What devrig is and what to do next belong in the docs behind "Learn more". (The download
            // size is the deliberate exception — see the install-offer disclosure test above.)
            assertTrue("must not mention the bundled JDK for $s", !content.message.contains("JDK"))
            // Paths render as the real home, never home-relative — on Windows a tilde is a placeholder
            // the OS will not expand. `~611 MB` is fine; the ban is on `~`-anchored paths.
            assertTrue(
                "must not render a home-relative path for $s",
                !content.message.contains("~/") && !content.message.contains("~\\"),
            )
        }
    }

    @Test
    fun `every state yields a labelled button and lines that render as separate paragraphs`() {
        for (s in allStates) {
            val content = content(s)
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
