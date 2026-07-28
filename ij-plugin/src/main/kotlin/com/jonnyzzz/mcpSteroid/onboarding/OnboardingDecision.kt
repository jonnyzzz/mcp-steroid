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
enum class OnboardingDecision { ALREADY_CONNECTED, OFFER_ENABLE, OFFER_UPDATE, OFFER_GET_AGENT }

/**
 * Decide the onboarding action. No agent CLI → offer to get one. Otherwise: fully wired (devrig
 * installed AND the Claude plugin enabled) → nothing to do, unless the installed devrig is stale, in
 * which case we offer the update; anything less → offer the one-click enable.
 *
 * The IDE plugin is the migration path onto devrig, so "installed" is not the goal — a *current* devrig
 * is. An outdated devrig is treated as unfinished migration and offered just as insistently.
 */
fun decideOnboarding(
    devrigInstalled: Boolean,
    claudePresent: Boolean,
    claudePluginEnabled: Boolean,
    devrigOutdated: Boolean = false,
): OnboardingDecision = when {
    !claudePresent -> OnboardingDecision.OFFER_GET_AGENT
    devrigInstalled && claudePluginEnabled ->
        if (devrigOutdated) OnboardingDecision.OFFER_UPDATE else OnboardingDecision.ALREADY_CONNECTED
    else -> OnboardingDecision.OFFER_ENABLE
}

/** True iff the stable devrig launcher exists under ~/.mcp-steroid/bin for this OS. */
fun devrigInstalled(userHome: Path, windows: Boolean): Boolean {
    val name = if (windows) "devrig.cmd" else "devrig"
    return Files.isRegularFile(userHome.resolve(".mcp-steroid").resolve("bin").resolve(name))
}

/** `exec "<path>"` (POSIX) / `call "<path>"` (Windows `.cmd`) — the install-tree launcher the wrapper runs. */
private val LAUNCHER_TARGET = Regex("""(?:^|\s)(?:exec|call)\s+"([^"]+)"""", RegexOption.MULTILINE)

/**
 * The devrig version currently installed, read from the text of the stable `~/.mcp-steroid/bin/devrig`
 * wrapper — no process spawn. The wrapper (written by devrig's own `BinLauncher.renderPosixLauncher` /
 * `renderWindowsCmd`) hands off to the content-addressed install tree, whose distribution directory
 * carries the version: `…/binaries/devrig-<key>-<version>-<sha12>/devrig-<version>/bin/devrig`.
 *
 * Returns null when the wrapper is absent or its shape is not recognised — callers must treat that as
 * "version unknown", NOT as outdated, so a launcher we cannot parse never produces a false update nag.
 */
fun installedDevrigVersion(launcherText: String?): String? {
    val text = launcherText?.takeIf { it.isNotBlank() } ?: return null
    val target = LAUNCHER_TARGET.find(text)?.groupValues?.get(1) ?: return null
    // …/<distDir>/bin/devrig(.bat) — drop the trailing "bin/<launcher>" to land on the distribution dir.
    val distDir = target.replace('\\', '/').trimEnd('/').split('/').dropLast(2).lastOrNull() ?: return null
    if (!distDir.startsWith("devrig-")) return null
    return distDir.removePrefix("devrig-").takeIf { it.isNotBlank() }
}

/**
 * True iff [installedVersion] is not on the [latestBaseVersion] line (`version-base` from
 * `version.json`). Uses the same `startsWith` semantics as the plugin's own update check
 * ([com.jonnyzzz.mcpSteroid.updates.UpdateChecker]), so a snapshot of the current release
 * (`0.101-SNAPSHOT-abc` vs `0.101`) counts as current, not stale.
 *
 * Unknown inputs are never "outdated" — we only nag when we actually know the user is behind.
 */
fun isDevrigOutdated(installedVersion: String?, latestBaseVersion: String?): Boolean {
    if (installedVersion.isNullOrBlank() || latestBaseVersion.isNullOrBlank()) return false
    return !installedVersion.startsWith(latestBaseVersion)
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
