/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Marketplace name Claude registers our plugin under, in `extraKnownMarketplaces`. */
const val CLAUDE_MARKETPLACE_NAME = "mcp-steroid"

/** GitHub `owner/repo` hosting `.claude-plugin/marketplace.json` for the marketplace above. */
const val CLAUDE_MARKETPLACE_REPO = "jonnyzzz/mcp-steroid"

/** The `enabledPlugins` key that turns the devrig plugin on: `<plugin>@<marketplace>`. */
const val CLAUDE_DEVRIG_PLUGIN_KEY = "devrig@$CLAUDE_MARKETPLACE_NAME"

private val prettyJson = Json { prettyPrint = true }
private val laxJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseRootObject(currentJson: String?): JsonObject {
    val text = currentJson?.takeIf { it.isNotBlank() } ?: return JsonObject(emptyMap())
    val element: JsonElement = laxJson.parseToJsonElement(text)
    return element as? JsonObject
        ?: error("~/.claude/settings.json root is not a JSON object")
}

/** True iff `enabledPlugins["devrig@mcp-steroid"]` is present and is exactly the JSON boolean `true`. */
fun isClaudePluginEnabled(currentJson: String?): Boolean {
    val root = parseRootObject(currentJson)
    val plugins = root["enabledPlugins"] as? JsonObject ?: return false
    val flag = plugins[CLAUDE_DEVRIG_PLUGIN_KEY] as? JsonPrimitive ?: return false
    // A JSON boolean primitive is non-string with content "true"/"false"; a quoted "true" is a string.
    return !flag.isString && flag.content == "true"
}

/**
 * Return a pretty-printed settings.json that has our marketplace + enabled-plugin entries merged in.
 * Additive and idempotent: every existing top-level key, marketplace, and enabled plugin is preserved;
 * only our two keys are inserted/overwritten with their canonical value.
 */
fun enableClaudePluginInSettings(currentJson: String?): String {
    val root = parseRootObject(currentJson)

    val existingMarketplaces = root["extraKnownMarketplaces"] as? JsonObject ?: JsonObject(emptyMap())
    val existingPlugins = root["enabledPlugins"] as? JsonObject ?: JsonObject(emptyMap())

    val mergedMarketplaces = buildJsonObject {
        existingMarketplaces.forEach { (k, v) -> put(k, v) }
        putJsonObject(CLAUDE_MARKETPLACE_NAME) {
            putJsonObject("source") {
                put("source", "github")
                put("repo", CLAUDE_MARKETPLACE_REPO)
            }
        }
    }
    val mergedPlugins = buildJsonObject {
        existingPlugins.forEach { (k, v) -> put(k, v) }
        put(CLAUDE_DEVRIG_PLUGIN_KEY, true)
    }

    val mergedRoot = buildJsonObject {
        root.forEach { (k, v) ->
            if (k != "extraKnownMarketplaces" && k != "enabledPlugins") put(k, v)
        }
        put("extraKnownMarketplaces", mergedMarketplaces)
        put("enabledPlugins", mergedPlugins)
    }
    return prettyJson.encodeToString(JsonObject.serializer(), mergedRoot) + "\n"
}
