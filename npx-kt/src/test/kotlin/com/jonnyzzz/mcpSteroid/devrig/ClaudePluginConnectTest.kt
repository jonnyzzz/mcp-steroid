/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudePluginConnectTest {
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `enable on null produces the marketplace and enabled plugin`() {
        val out = parse(enableClaudePluginInSettings(null))
        val marketplaces = out["extraKnownMarketplaces"]!!.jsonObject
        val source = marketplaces[CLAUDE_MARKETPLACE_NAME]!!.jsonObject["source"]!!.jsonObject
        assertEquals("github", source["source"]!!.jsonPrimitive.content)
        assertEquals(CLAUDE_MARKETPLACE_REPO, source["repo"]!!.jsonPrimitive.content)
        assertTrue(out["enabledPlugins"]!!.jsonObject[CLAUDE_DEVRIG_PLUGIN_KEY]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `enable preserves unrelated keys and other marketplaces`() {
        val input = """
            {
              "model": "opus",
              "extraKnownMarketplaces": { "other": { "source": { "source": "github", "repo": "a/b" } } },
              "enabledPlugins": { "foo@other": true }
            }
        """.trimIndent()
        val out = parse(enableClaudePluginInSettings(input))
        assertEquals("opus", out["model"]!!.jsonPrimitive.content)
        val mk = out["extraKnownMarketplaces"]!!.jsonObject
        assertTrue(mk.containsKey("other"), "existing marketplace preserved")
        assertTrue(mk.containsKey(CLAUDE_MARKETPLACE_NAME), "ours added")
        val plugins = out["enabledPlugins"]!!.jsonObject
        assertTrue(plugins.containsKey("foo@other"), "existing plugin preserved")
        assertTrue(plugins.containsKey(CLAUDE_DEVRIG_PLUGIN_KEY), "ours added")
    }

    @Test
    fun `enable is idempotent`() {
        val once = enableClaudePluginInSettings(null)
        val twice = enableClaudePluginInSettings(once)
        assertEquals(once, twice)
    }

    @Test
    fun `detection reflects state`() {
        assertFalse(isClaudePluginEnabled(null))
        assertFalse(isClaudePluginEnabled("{}"))
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"foo@other":true}}"""))
        assertTrue(isClaudePluginEnabled(enableClaudePluginInSettings(null)))
        assertFalse(isClaudePluginEnabled("""{"enabledPlugins":{"devrig@mcp-steroid":false}}"""))
    }
}
