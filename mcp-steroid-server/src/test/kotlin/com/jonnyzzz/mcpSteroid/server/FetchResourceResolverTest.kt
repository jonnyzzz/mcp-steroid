/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins that URI -> article resolution returns the article object itself (not a pre-rendered
 * payload string, not a bare URI string) so callers stay in control of when/how they render it.
 */
class FetchResourceResolverTest {

    @Test
    fun `resolveResourceArticle returns the matched article`() {
        val knownUri = canonicalResourceEntryPoints().first().uri

        val article = resolveResourceArticle(knownUri, PromptsContext.Generic)

        assertNotNull(article, "expected a matching article for $knownUri")
        assertEquals(knownUri, article!!.uri)
    }

    @Test
    fun `canonicalResourceEntryPoints returns articles with valid mcp-steroid uris`() {
        val entryPoints = canonicalResourceEntryPoints()

        assertTrue(entryPoints.isNotEmpty(), "expected at least one canonical entry point")
        val first = entryPoints.first()
        assertTrue(first.uri.startsWith("mcp-steroid://"), "unexpected uri: ${first.uri}")
    }
}
