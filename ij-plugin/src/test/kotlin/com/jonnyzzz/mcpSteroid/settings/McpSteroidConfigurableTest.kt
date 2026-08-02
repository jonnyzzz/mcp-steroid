/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionStateService
import java.awt.Component
import java.awt.Container
import javax.swing.AbstractButton
import javax.swing.JLabel
import javax.swing.JTabbedPane
import javax.swing.text.JTextComponent

class McpSteroidConfigurableTest : BasePlatformTestCase() {

    fun `test applicationConfigurable EP is registered and instantiable`() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList
            .singleOrNull { it.id == McpSteroidConfigurable.CONFIGURABLE_ID }
            ?: error("No <applicationConfigurable> with id=${McpSteroidConfigurable.CONFIGURABLE_ID} is registered in plugin.xml")

        assertEquals("tools", ep.parentId)
        assertEquals(McpSteroidConfigurable.DISPLAY_NAME, ep.displayName)
        assertTrue(
            "Settings tab name must lead with the Devrig product name; got '${McpSteroidConfigurable.DISPLAY_NAME}'",
            McpSteroidConfigurable.DISPLAY_NAME.startsWith("Devrig"),
        )

        val configurable = ep.createConfigurable()
        assertNotNull("ConfigurableEP must instantiate the settings page", configurable)
        assertTrue(
            "Expected McpSteroidConfigurable, got ${configurable!!.javaClass.name}",
            configurable is McpSteroidConfigurable,
        )
        assertEquals(McpSteroidConfigurable.DISPLAY_NAME, configurable.displayName)
        configurable.disposeUIResources()
    }

    fun `test panel promotes devrig and shows copyable install one-liners`() {
        val configurable = McpSteroidConfigurable()
        try {
            val component = configurable.createComponent() ?: error("createComponent returned null")
            val texts = collectTexts(component)
            val joined = texts.joinToString("\n")

            // Two tabs, devrig first — the Kotlin UI DSL's tabbedPaneHeader shows the selected tab's
            // content only, so the titles are the one place both paths are visible at once.
            val header = findTabHeader(component)
            assertEquals(
                listOf(McpSteroidConfigurable.DEVRIG_TAB_TITLE, McpSteroidConfigurable.HTTP_TAB_TITLE),
                (0 until header.tabCount).map { header.getTitleAt(it) },
            )
            assertEquals("The recommended path must be the tab that opens", 0, header.selectedIndex)

            // The intro leads with the "AI Agents" framing.
            assertContainsText(texts, "AI Agents")

            // The feedback link points at GitHub issues only. There is no Slack workspace —
            // the old label falsely promised one.
            assertContainsText(texts, "Report issues on GitHub")
            assertFalse("No Slack workspace exists — the label must not mention Slack", joined.contains("Slack"))

            // devrig install is implemented: the panel shows the copyable one-liners for both
            // macOS/Linux (curl … | sh) and Windows (irm … | iex), plus the agent-registration hint.
            assertContainsText(texts, McpSteroidConfigurable.DEVRIG_INSTALL_SH)
            assertContainsText(texts, McpSteroidConfigurable.DEVRIG_INSTALL_PS1)
            assertTrue(
                "macOS/Linux installer must be a copyable curl|sh one-liner; found:\n$joined",
                joined.contains("install.sh") && joined.contains("| sh"),
            )
            assertTrue(
                "Windows installer must be a copyable irm|iex one-liner; found:\n$joined",
                joined.contains("install.ps1") && joined.contains("| iex"),
            )
            // Exactly one of the two state-dependent blocks, never both: the install button has nothing
            // to offer someone who already has devrig, and the registration commands mean nothing to
            // someone who does not. Asserted against the real state so this holds on any machine.
            val installed = DevrigConnectionStateService.getInstance().localState().devrigInstalled
            if (installed) {
                // One row per agent devrig can register — never just Claude.
                for (agent in AiAgentCli.entries) {
                    assertContainsText(texts, agent.displayName)
                }
                assertFalse(
                    "devrig is installed — the panel must not offer to install it again; found:\n$joined",
                    joined.contains("devrig is not installed"),
                )
            } else {
                assertContainsText(texts, "devrig is not installed")
                assertContainsText(texts, "Install devrig")
                assertFalse(
                    "devrig is missing — registering an agent is not yet the next step; found:\n$joined",
                    joined.contains("Point an agent at it"),
                )
            }

            // The panel still links to the devrig documentation.
            assertEquals("https://devrig.dev/docs/devrig/", McpSteroidConfigurable.DEVRIG_DOCS_URL)

            // Status block sits outside the tabs, so it renders whichever tab is selected.
            assertContainsText(texts, "MCP server")

            // Switching to the HTTP tab swaps the content in place — and that is also the only way to
            // reach it, since an unselected tab's panel is not in the component tree at all.
            header.selectedIndex = 1
            val httpTexts = collectTexts(component)
            val httpJoined = httpTexts.joinToString("\n")

            // The legacy HTTP examples must carry a "not recommended" warning steering users to devrig.
            assertContainsText(httpTexts, "Not recommended")

            // Legacy HTTP section must reference the registry keys so pre-devrig
            // HTTP-based setups can still find their port/host configuration.
            assertContainsText(httpTexts, "mcp.steroid.server.port")
            assertContainsText(httpTexts, "mcp.steroid.server.host")
            assertContainsText(httpTexts, "MCP server")

            // One tab at a time: the devrig content is gone, which is what makes the strip a tab strip
            // rather than two stacked groups.
            assertFalse(
                "The devrig tab's content must leave the tree when the HTTP tab is selected; found:\n$httpJoined",
                httpJoined.contains(McpSteroidConfigurable.DEVRIG_INSTALL_SH),
            )
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** The DSL tab strip: `Row.tabbedPaneHeader` renders a [JTabbedPane] with no content pages. */
    private fun findTabHeader(component: Component): JTabbedPane {
        val found = mutableListOf<JTabbedPane>()
        fun walk(c: Component) {
            if (c is JTabbedPane) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found.singleOrNull()
            ?: error("Expected exactly one tab strip on the settings page, found ${found.size}")
    }

    private fun assertContainsText(texts: List<String>, expected: String) {
        assertTrue(
            "Settings panel must contain text '$expected'; found:\n${texts.joinToString("\n")}",
            texts.any { it.contains(expected) },
        )
    }

    private fun collectTexts(component: Component, out: MutableList<String> = mutableListOf()): List<String> {
        when (component) {
            // Icon-only labels (e.g. the warning sign) have null text — skip them.
            is JTextComponent -> component.text?.let { out.add(it) }
            is JLabel -> component.text?.let { out.add(it) }
            // browserLink(...) renders as an ActionLink (a JButton subclass).
            is AbstractButton -> component.text?.let { out.add(it) }
        }
        if (component is Container) {
            for (child in component.components) {
                collectTexts(child, out)
            }
        }
        return out
    }
}
