/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.fields.ExtendableTextField
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.onboarding.DevrigConnectionStateService
import com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer
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

    fun `test panel promotes devrig, renders statuses as value fields, and deprecates direct HTTP`() {
        val configurable = McpSteroidConfigurable()
        try {
            val component = configurable.createComponent()
            val texts = collectTexts(component)
            val joined = texts.joinToString("\n")

            // No tabs: everything is on one page, with the deprecated path collapsed at the bottom.
            assertTrue(
                "The page must not use a tabbed pane; found ${findAllTabbedPanes(component).size}",
                findAllTabbedPanes(component).isEmpty(),
            )

            // No pitch: this page is for state and actions, and its reader already installed the plugin.
            assertFalse(
                "The settings page must not open with marketing copy; found:\n$joined",
                joined.contains("not just over your files"),
            )

            // The feedback link points at GitHub issues only, and lives at the bottom — it is never the
            // next step for someone who just opened the page. There is no Slack workspace; the old label
            // falsely promised one.
            assertContainsText(texts, "Report an issue on GitHub")
            assertFalse("No Slack workspace exists — the label must not mention Slack", joined.contains("Slack"))
            assertTrue(
                "The feedback link must come after the devrig content; order was:\n$joined",
                texts.indexOfFirst { it.contains("Report an issue on GitHub") } >
                    texts.indexOfFirst { it.contains("What is devrig?") },
            )

            // The by-hand installer one-liners are gone: the button is the way to install devrig from the
            // IDE, and a copyable `curl … | sh` next to it only invited a second, unmanaged install.
            assertFalse(
                "The page must not print installer one-liners any more; found:\n$joined",
                joined.contains("install.sh") || joined.contains("install.ps1") || joined.contains("| iex"),
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

            // Both halves of "can an agent reach this IDE?" live in the same Devrig group: the server's
            // state, and devrig's right below it — each rendered as a read-only value field rather than a
            // bare label, so the answer is visually separable from the label that names it.
            assertContainsText(texts, "MCP server")
            val fieldTexts = collectValueFields(component).map { it.text }
            assertTrue(
                "The server status must be a value field, not a bare label; fields: $fieldTexts",
                fieldTexts.any { it.startsWith("Running on port") || it == "Not running" },
            )
            if (installed) {
                assertTrue(
                    "devrig's status must be a value field too; fields: $fieldTexts",
                    fieldTexts.any { it.startsWith("Installed") },
                )
            }

            // The direct-HTTP path is collapsed at the bottom and says outright that it is deprecated —
            // in the section title and in its first line.
            assertTrue(
                "The HTTP section title must mark the path deprecated; got '${McpSteroidConfigurable.HTTP_SECTION_TITLE}'",
                McpSteroidConfigurable.HTTP_SECTION_TITLE.contains("deprecated"),
            )
            assertContainsText(texts, McpSteroidConfigurable.HTTP_SECTION_TITLE)
            assertContainsText(texts, "Deprecated")

            // Legacy HTTP section must reference the registry keys so pre-devrig
            // HTTP-based setups can still find their port/host configuration.
            assertContainsText(texts, "mcp.steroid.server.port")
            assertContainsText(texts, "mcp.steroid.server.host")

            // The server URL is part of wiring an HTTP connection by hand, not part of the page's status,
            // so it moved into that section. Rendered only when the server actually bound a port.
            val serverUrl = ApplicationManager.getApplication()
                .getServiceIfCreated(SteroidsMcpServer::class.java)
                ?.takeIf { it.port > 0 }
                ?.mcpUrl
            if (serverUrl != null) {
                assertContainsText(texts, serverUrl)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }

    /** The read-only fields the page renders every value in — statuses, URLs and commands alike. */
    private fun collectValueFields(component: Component): List<ExtendableTextField> {
        val found = mutableListOf<ExtendableTextField>()
        fun walk(c: Component) {
            if (c is ExtendableTextField) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
    }

    private fun findAllTabbedPanes(component: Component): List<JTabbedPane> {
        val found = mutableListOf<JTabbedPane>()
        fun walk(c: Component) {
            if (c is JTabbedPane) found.add(c)
            if (c is Container) c.components.forEach { walk(it) }
        }
        walk(component)
        return found
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
