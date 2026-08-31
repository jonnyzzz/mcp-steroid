/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.DesignPhilosophyPromptArticle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the tool list in the canonical design-philosophy resource
 * (`mcp-steroid://skill/design-philosophy`, Tenet 1) to the live tool
 * registrations in [McpSteroidTools], so the two can never drift apart
 * again (issue #457: contributor guides claimed "10 today" while the
 * server exposed 8).
 *
 * When this test fails after a deliberate tool-surface change, update the
 * article AND every count-bearing doc: `docs/PHILOSOPHY.md`, root
 * `CLAUDE.md`, `ij-plugin/CLAUDE.md`, and
 * `website/content/docs/how-it-works.md`.
 */
class ToolSurfacePhilosophyContractTest {

    private val tools = object : McpSteroidTools() {
        override fun <T> handler(type: Class<T>): T = unreachableHandler()
    }

    @Test
    fun `design-philosophy article lists exactly the registered tool surface`() {
        val registered = tools.devrigToolSpecs().map { it.name }.sorted()

        val payload = DesignPhilosophyPromptArticle().readPayload(PromptsContext.Generic)
        val listed = Regex("(?m)^- `(steroid_[a-z_]+)`$")
            .findAll(payload)
            .map { it.groupValues[1] }
            .toList()
            .sorted()

        assertEquals(registered, listed) {
            "The tool list in mcp-steroid://skill/design-philosophy must match the live " +
                "registrations in McpSteroidTools. Update the article (and the count-bearing " +
                "docs named in this test's KDoc) together with any tool-surface change."
        }
    }
}
