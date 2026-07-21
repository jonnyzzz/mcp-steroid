/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.settings

import com.intellij.openapi.options.Configurable
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Component
import java.awt.Container
import javax.swing.JLabel
import javax.swing.text.JTextComponent

class McpSteroidConfigurableTest : BasePlatformTestCase() {

    fun `test applicationConfigurable EP is registered and instantiable`() {
        val ep = Configurable.APPLICATION_CONFIGURABLE.extensionList
            .singleOrNull { it.id == McpSteroidConfigurable.CONFIGURABLE_ID }
            ?: error("No <applicationConfigurable> with id=${McpSteroidConfigurable.CONFIGURABLE_ID} is registered in plugin.xml")

        assertEquals("tools", ep.parentId)
        assertEquals(McpSteroidConfigurable.DISPLAY_NAME, ep.displayName)

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
            val texts = collectTexts(configurable.createComponent())
            val joined = texts.joinToString("\n")

            // The intro leads with the "AI Agents" framing and promotes devrig as the recommended path.
            assertContainsText(texts, "AI Agents")
            assertContainsText(texts, "Devrig")

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
            assertContainsText(texts, "devrig install claude")

            // The panel still links to the devrig documentation.
            assertEquals("https://devrig.dev/docs/devrig/", McpSteroidConfigurable.DEVRIG_DOCS_URL)

            // The legacy HTTP examples must carry a "not recommended" warning steering users to devrig.
            assertContainsText(texts, "Not recommended")

            // Legacy HTTP section must reference the registry keys so pre-devrig
            // HTTP-based setups can still find their port/host configuration.
            assertContainsText(texts, "mcp.steroid.server.port")
            assertContainsText(texts, "mcp.steroid.server.host")

            // Status block renders in both server states; the label is always present.
            assertContainsText(texts, "MCP server")
        } finally {
            configurable.disposeUIResources()
        }
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
        }
        if (component is Container) {
            for (child in component.components) {
                collectTexts(child, out)
            }
        }
        return out
    }
}
