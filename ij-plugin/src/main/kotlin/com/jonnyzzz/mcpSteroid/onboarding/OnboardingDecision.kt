/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Claude Code `enabledPlugins` key that turns the devrig marketplace plugin on: `<plugin>@<marketplace>`,
 * where the marketplace part is the `name` declared in `.claude-plugin/marketplace.json`. Must match
 * devrig's own constant of the same name (npx-kt `ClaudePluginConnect.kt`) — otherwise this IDE keeps
 * offering "Enable" on a machine that is already connected. Enforced by `validateMarketplaceJson`.
 */
const val CLAUDE_DEVRIG_PLUGIN_KEY = "devrig@jonnyzzz"

/** What the IDE should offer the user on startup, given the detected connection state. */
enum class OnboardingDecision { ALREADY_CONNECTED, OFFER_ENABLE, OFFER_GET_AGENT }

/**
 * Decide the onboarding action. No agent CLI → offer to get one. Otherwise: fully wired (devrig
 * installed AND the Claude plugin enabled) → nothing to do; anything less → offer the one-click enable.
 */
fun decideOnboarding(
    devrigInstalled: Boolean,
    claudePresent: Boolean,
    claudePluginEnabled: Boolean,
): OnboardingDecision = when {
    !claudePresent -> OnboardingDecision.OFFER_GET_AGENT
    devrigInstalled && claudePluginEnabled -> OnboardingDecision.ALREADY_CONNECTED
    else -> OnboardingDecision.OFFER_ENABLE
}

/** True iff the stable devrig launcher exists under ~/.mcp-steroid/bin for this OS. */
fun devrigInstalled(userHome: Path, windows: Boolean): Boolean {
    val name = if (windows) "devrig.cmd" else "devrig"
    return Files.isRegularFile(userHome.resolve(".mcp-steroid").resolve("bin").resolve(name))
}

/** Locate the `claude` CLI: scan PATH entries, then fall back to ~/.local/bin. Null if not found. */
fun findClaudeBinary(pathEnv: String?, userHome: Path, windows: Boolean): Path? {
    val names = if (windows) listOf("claude.exe", "claude.cmd", "claude.bat") else listOf("claude")
    // Derive the PATH separator from the `windows` parameter — NOT File.pathSeparatorChar (runtime OS),
    // so the function stays OS-pure and testable for either OS on any host.
    val pathSeparator = if (windows) ';' else ':'
    val dirs = buildList {
        pathEnv?.split(pathSeparator)?.forEach { entry ->
            if (entry.isNotBlank()) add(Path.of(entry))
        }
        add(userHome.resolve(".local").resolve("bin"))
    }
    for (dir in dirs) {
        for (n in names) {
            val candidate = dir.resolve(n)
            if (Files.isRegularFile(candidate)) return candidate
        }
    }
    return null
}

private val laxJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** True iff `enabledPlugins["devrig@mcp-steroid"]` in the given settings.json text is the JSON boolean true. */
fun isClaudePluginEnabled(settingsJsonText: String?): Boolean {
    val text = settingsJsonText?.takeIf { it.isNotBlank() } ?: return false
    val root = try {
        laxJson.parseToJsonElement(text) as? JsonObject
    } catch (e: Exception) {
        // A user hand-edited settings.json can be invalid; treat as not-enabled instead of crashing onboarding.
        System.err.println("[devrig] cannot parse ~/.claude/settings.json: ${e.message}")
        return false
    } ?: return false
    val plugins = root["enabledPlugins"] as? JsonObject ?: return false
    val flag = plugins[CLAUDE_DEVRIG_PLUGIN_KEY] as? JsonPrimitive ?: return false
    return !flag.isString && flag.content == "true"
}
