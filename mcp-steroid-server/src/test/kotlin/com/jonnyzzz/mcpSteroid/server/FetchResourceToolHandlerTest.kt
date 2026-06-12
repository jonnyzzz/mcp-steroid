/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.ArticleBase
import com.jonnyzzz.mcpSteroid.prompts.ArticlePart
import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import com.jonnyzzz.mcpSteroid.prompts.PromptBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.SeeAlsoItem
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FetchResourceToolHandler.call] resource lookup, in particular the
 * three-way error UX (GitHub issue #81): a known URI whose article is filtered out for the
 * current IDE must produce an "exists but is not available in <product>" error — not the
 * generic "Resource not found" that is reserved for genuinely unknown URIs.
 */
class FetchResourceToolHandlerTest {

    private fun textPrompt(text: String): PromptBase = object : PromptBase() {
        override fun readPrompt() = text
        override val mimeType = "text/markdown"
    }

    private fun article(articleUri: String, filter: IdeFilter): ArticleBase = object : ArticleBase() {
        override val uri = articleUri
        override val title = textPrompt("Test Article Title")
        override val ownFilter = filter
        override val description = textPrompt("Test article description")
        override val parts = emptyList<ArticlePart>()
        override val seeAlsoItems = emptyList<SeeAlsoItem>()
    }

    private val ideaOnlyUri = "mcp-steroid://test/idea-only"
    private val everywhereUri = "mcp-steroid://test/everywhere"

    private val testArticles = listOf(
        article(ideaOnlyUri, IdeFilter.Ide(setOf("IU"))),
        article(everywhereUri, IdeFilter.All),
    )

    private fun fetch(uri: String, context: PromptsContext): ToolCallResult {
        val spec = FetchResourceToolHandler(
            handler = {
                object : PromptsContextHandler {
                    override suspend fun buildPromptsContext(projectName: String) = context
                }
            },
            articles = { testArticles.asSequence() },
        )
        val callContext = ToolCallContext(
            params = ToolCallParams(
                name = spec.name,
                arguments = buildJsonObject {
                    put("uri", uri)
                    put("project_name", "demo-project")
                },
            ),
            session = McpSession(),
            mcpProgressReporter = object : McpProgressReporter { override fun report(message: String) = Unit },
        )
        return runBlocking { spec.call(callContext) }
    }

    private fun ToolCallResult.text(): String =
        content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }

    @Test
    fun `known URI with matching context returns the payload`() {
        val result = fetch(ideaOnlyUri, PromptsContext("IU", 253))
        assertFalse(result.isError, "expected success: ${result.text()}")
        assertTrue(result.text().contains("Test Article Title"), "payload is rendered: ${result.text()}")
    }

    @Test
    fun `known URI filtered for the current IDE names the product and the alternatives`() {
        val result = fetch(ideaOnlyUri, PromptsContext("PY", 253))
        assertTrue(result.isError)
        val text = result.text()
        assertTrue(text.contains("exists but is not available in PY"), "names the current product: $text")
        assertTrue(text.contains("available for: IU"), "names the supporting products: $text")
        assertTrue(text.contains(SkillPromptArticle().uri), "points to the skill index: $text")
        assertFalse(text.contains("Resource not found"), "must not look like an unknown URI: $text")
    }

    @Test
    fun `unknown URI keeps the plain not-found error`() {
        val result = fetch("mcp-steroid://test/does-not-exist", PromptsContext("IU", 253))
        assertTrue(result.isError)
        val text = result.text()
        assertTrue(text.contains("Resource not found: mcp-steroid://test/does-not-exist"), "plain not-found: $text")
        assertFalse(text.contains("exists but is not available"), "no availability hint for unknown URIs: $text")
    }

    @Test
    fun `unfiltered article is served to any product`() {
        val result = fetch(everywhereUri, PromptsContext("RD", 253))
        assertFalse(result.isError, "expected success: ${result.text()}")
    }

    @Test
    fun `availableProductCodes probes structurally mentioned codes`() {
        val filtered = listOf(article(ideaOnlyUri, IdeFilter.Ide(setOf("IU", "RD"))))
        assertEquals(
            listOf("IU", "RD"),
            availableProductCodes(filtered, PromptsContext("PY", 253)),
        )
    }

    @Test
    fun `availableProductCodes is empty for pure NOT filters`() {
        // ELSE-branch filters mention only the negated code; probing it fails, so the
        // handler falls back to the generic "another IDE product or version" wording.
        val filtered = listOf(article(ideaOnlyUri, IdeFilter.Ide(setOf("RD")).not()))
        assertEquals(
            emptyList<String>(),
            availableProductCodes(filtered, PromptsContext("RD", 253)),
        )
    }
}
