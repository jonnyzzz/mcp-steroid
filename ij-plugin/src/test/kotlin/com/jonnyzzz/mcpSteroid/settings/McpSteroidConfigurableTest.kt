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

    fun `test panel promotes devrig and points to its documentation only`() {
        val configurable = McpSteroidConfigurable()
        try {
            val texts = collectTexts(configurable.createComponent())
            val joined = texts.joinToString("\n")

            // The intro leads with the "AI Agents" framing and promotes devrig as the recommended path.
            assertContainsText(texts, "AI Agents")
            assertContainsText(texts, "Devrig")

            // devrig install is not shipped yet: the panel must NOT advertise any install command,
            // only point to the documentation.
            assertEquals("https://mcp-steroid.jonnyzzz.com/docs/devrig/", McpSteroidConfigurable.DEVRIG_DOCS_URL)
            assertFalse(
                "Panel must not advertise the unimplemented devrig install; found:\n$joined",
                joined.contains("install.sh") || joined.contains("devrig install "),
            )

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
            is JTextComponent -> out.add(component.text)
            is JLabel -> out.add(component.text)
        }
        if (component is Container) {
            for (child in component.components) {
                collectTexts(child, out)
            }
        }
        return out
    }
}
