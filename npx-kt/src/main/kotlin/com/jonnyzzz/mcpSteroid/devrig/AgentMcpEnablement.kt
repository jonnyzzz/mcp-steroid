/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * A registration that exists but will not be used: every agent can keep an MCP server configured and
 * switched off, and **none of them says so in `mcp list`**. `claude mcp list` and even
 * `claude mcp get <name>` report a server disabled for the current project as `✔ Connected`.
 *
 * That makes the state worse than "not registered": the visible answer everywhere is "all good", and the
 * bridge silently does nothing. So devrig looks at each agent's own configuration file for it.
 *
 * | Agent | Off means | File |
 * |---|---|---|
 * | Claude Code | the name is in `projects.<path>.disabledMcpServers` — **per project** | `~/.claude.json` |
 * | Codex | `enabled = false` inside the `[mcp_servers.<name>]` table | `~/.codex/config.toml` |
 * | Gemini | the name is in `mcp.excluded`, or `mcp.allowed` exists without it | `~/.gemini/settings.json` |
 *
 * None of the three CLIs has an enable/disable verb (Codex tracks one in openai/codex#16439), so both
 * halves of this — reading and clearing — work on the file.
 */
data class DisabledRegistration(
    /** What to show a human: the file, and for Claude the projects it is switched off in. */
    val description: String,
)

/** `~/.claude.json` — the per-project Claude Code state, NOT `~/.claude/settings.json`. */
fun claudeJsonPath(userHome: Path = userHomePath()): Path = userHome.resolve(".claude.json")

/** `$CODEX_HOME/config.toml`, defaulting to `~/.codex/config.toml`. */
fun codexConfigPath(userHome: Path = userHomePath(), codexHome: String? = System.getenv("CODEX_HOME")): Path =
    codexHome?.takeIf { it.isNotBlank() }?.let { Path.of(it) }?.resolve("config.toml")
        ?: userHome.resolve(".codex").resolve("config.toml")

/** `~/.gemini/settings.json`. */
fun geminiSettingsPath(userHome: Path = userHomePath()): Path =
    userHome.resolve(".gemini").resolve("settings.json")

private fun userHomePath(): Path = Path.of(System.getProperty("user.home"))

private val laxJson = Json { ignoreUnknownKeys = true; isLenient = true }
private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }

private fun parseObject(text: String?): JsonObject? {
    val trimmed = text?.takeIf { it.isNotBlank() } ?: return null
    return try {
        laxJson.parseToJsonElement(trimmed) as? JsonObject
    } catch (e: Exception) {
        // A hand-edited config is the user's business; we report "cannot tell", never crash a check.
        System.err.println("[mcp-steroid] could not parse an agent config as JSON: ${e.message}")
        null
    }
}

private fun JsonArray.stringValues(): List<String> = mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

/**
 * Projects where a devrig-owned server name is disabled for Claude Code. Empty means "nothing switched
 * off", which is also what an unreadable or absent config yields — never a false alarm.
 */
fun claudeDisabledProjects(claudeJsonText: String?): List<String> {
    val root = parseObject(claudeJsonText) ?: return emptyList()
    val projects = root["projects"] as? JsonObject ?: return emptyList()
    return projects.entries.mapNotNull { (path, value) ->
        val disabled = (value as? JsonObject)?.get("disabledMcpServers") as? JsonArray ?: return@mapNotNull null
        path.takeIf { disabled.stringValues().any { name -> name.lowercase() in DEVRIG_SERVER_NAMES } }
    }.sorted()
}

/** Drop every devrig-owned name from every project's `disabledMcpServers`. Returns the new file text. */
fun enableClaudeMcp(claudeJsonText: String): String {
    val root = parseObject(claudeJsonText) ?: return claudeJsonText
    val projects = root["projects"] as? JsonObject ?: return claudeJsonText
    val patchedProjects = buildJsonObject {
        for ((path, value) in projects) {
            val project = value as? JsonObject
            val disabled = project?.get("disabledMcpServers") as? JsonArray
            if (project == null || disabled == null) {
                put(path, value)
                continue
            }
            val kept = disabled.stringValues().filter { it.lowercase() !in DEVRIG_SERVER_NAMES }
            put(path, replaceKey(project, "disabledMcpServers", buildJsonArray { kept.forEach { add(JsonPrimitive(it)) } }))
        }
    }
    return prettyJson.encodeToString(JsonObject.serializer(), replaceKey(root, "projects", patchedProjects))
}

/**
 * True when Gemini would skip a devrig-owned server: it is listed in `mcp.excluded`, or an `mcp.allowed`
 * allow-list exists and names none of ours.
 */
fun geminiMcpDisabled(settingsText: String?): Boolean {
    val root = parseObject(settingsText) ?: return false
    val mcp = root["mcp"] as? JsonObject ?: return false
    val excluded = (mcp["excluded"] as? JsonArray)?.stringValues().orEmpty()
    if (excluded.any { it.lowercase() in DEVRIG_SERVER_NAMES }) return true
    val allowed = (mcp["allowed"] as? JsonArray)?.stringValues() ?: return false
    // An allow-list that mentions no devrig name excludes us by omission. An empty list is a real
    // allow-nothing setting, so it counts too.
    return allowed.none { it.lowercase() in DEVRIG_SERVER_NAMES }
}

/**
 * Clear Gemini's exclusion: drop devrig names from `mcp.excluded` and, when an `mcp.allowed` allow-list
 * exists, add the canonical name to it. Returns the new file text.
 */
fun enableGeminiMcp(settingsText: String): String {
    val root = parseObject(settingsText) ?: return settingsText
    val mcp = root["mcp"] as? JsonObject ?: return settingsText
    var patched = mcp
    (mcp["excluded"] as? JsonArray)?.let { excluded ->
        val kept = excluded.stringValues().filter { it.lowercase() !in DEVRIG_SERVER_NAMES }
        patched = replaceKey(patched, "excluded", buildJsonArray { kept.forEach { add(JsonPrimitive(it)) } })
    }
    (mcp["allowed"] as? JsonArray)?.let { allowed ->
        val names = allowed.stringValues()
        if (names.none { it.lowercase() in DEVRIG_SERVER_NAMES }) {
            patched = replaceKey(patched, "allowed", buildJsonArray {
                names.forEach { add(JsonPrimitive(it)) }
                add(JsonPrimitive(CANONICAL_DEVRIG_SERVER_NAME))
            })
        }
    }
    return prettyJson.encodeToString(JsonObject.serializer(), replaceKey(root, "mcp", patched))
}

/** Rebuild [source] with [key] replaced, preserving key order (kotlinx has no copy-with for JsonObject). */
private fun replaceKey(source: JsonObject, key: String, value: JsonElement): JsonObject = buildJsonObject {
    var replaced = false
    for ((k, v) in source) {
        if (k == key) {
            put(k, value)
            replaced = true
        } else {
            put(k, v)
        }
    }
    if (!replaced) put(key, value)
}

/**
 * `enabled = false` inside a `[mcp_servers.<devrig name>]` table.
 *
 * Read with a targeted scan rather than a TOML parser: there is no TOML library on this classpath, and a
 * scan that fails to understand a file reports "not disabled", which is the harmless direction. The scan
 * only ever looks at the lines of the one table it is interested in.
 */
fun codexMcpDisabled(tomlText: String?): Boolean {
    val text = tomlText?.takeIf { it.isNotBlank() } ?: return false
    return codexServerTableLines(text).any { it.isEnabledFalse() }
}

/**
 * Flip `enabled = false` to `true` in the devrig server's table. Only that one line is rewritten —
 * nothing else in the file is reformatted, so a config we do not fully understand survives intact.
 */
fun enableCodexMcp(tomlText: String): String {
    val lines = tomlText.lines().toMutableList()
    var inOurTable = false
    for (index in lines.indices) {
        val line = lines[index]
        val table = tomlTableName(line)
        if (table != null) {
            inOurTable = table.isDevrigServerTable()
            continue
        }
        if (inOurTable && line.isEnabledFalse()) {
            lines[index] = line.replaceFirst(Regex("""(?i)\bfalse\b"""), "true")
        }
    }
    return lines.joinToString("\n")
}

/** The lines belonging to `[mcp_servers.<devrig name>]` tables, in file order. */
private fun codexServerTableLines(text: String): List<String> {
    val collected = mutableListOf<String>()
    var inOurTable = false
    for (line in text.lines()) {
        val table = tomlTableName(line)
        if (table != null) {
            inOurTable = table.isDevrigServerTable()
            continue
        }
        if (inOurTable) collected += line
    }
    return collected
}

/** `[a.b.c]` (or `[[a.b]]`) → `a.b.c`; null for any other line. */
private fun tomlTableName(line: String): String? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
    return trimmed.trim('[', ']').trim()
}

private fun String.isDevrigServerTable(): Boolean {
    if (!startsWith("mcp_servers.")) return false
    val name = removePrefix("mcp_servers.").trim().trim('"', '\'')
    return name.lowercase() in DEVRIG_SERVER_NAMES
}

private fun String.isEnabledFalse(): Boolean =
    Regex("""^\s*enabled\s*=\s*false\s*(#.*)?$""", RegexOption.IGNORE_CASE).matches(this)

/**
 * Is a devrig registration present-but-off for [agent]? Null when it is fine (or when we cannot tell).
 *
 * Reads only the agent's own configuration file — no subprocess, so it is cheap enough to run on every
 * `--check`.
 */
fun disabledRegistrationFor(agent: AiAgentCli, userHome: Path = userHomePath()): DisabledRegistration? =
    when (agent) {
        AiAgentCli.CLAUDE -> claudeDisabledProjects(readTextOrNull(claudeJsonPath(userHome))).takeIf { it.isNotEmpty() }
            ?.let {
                DisabledRegistration(
                    "disabled for ${it.size} project(s) in ${claudeJsonPath(userHome)}: " +
                        it.joinToString(", "),
                )
            }
        AiAgentCli.CODEX -> if (codexMcpDisabled(readTextOrNull(codexConfigPath(userHome)))) {
            DisabledRegistration("'enabled = false' in ${codexConfigPath(userHome)}")
        } else {
            null
        }
        AiAgentCli.GEMINI -> if (geminiMcpDisabled(readTextOrNull(geminiSettingsPath(userHome)))) {
            DisabledRegistration("excluded by the 'mcp' settings in ${geminiSettingsPath(userHome)}")
        } else {
            null
        }
    }

/**
 * Switch a disabled registration back on for [agent], in the agent's own config file. Returns true when
 * the file was rewritten. Idempotent: nothing to clear means no write.
 */
fun enableRegistrationFor(agent: AiAgentCli, userHome: Path = userHomePath()): Boolean {
    val path = when (agent) {
        AiAgentCli.CLAUDE -> claudeJsonPath(userHome)
        AiAgentCli.CODEX -> codexConfigPath(userHome)
        AiAgentCli.GEMINI -> geminiSettingsPath(userHome)
    }
    val current = readTextOrNull(path) ?: return false
    val patched = when (agent) {
        AiAgentCli.CLAUDE -> enableClaudeMcp(current)
        AiAgentCli.CODEX -> enableCodexMcp(current)
        AiAgentCli.GEMINI -> enableGeminiMcp(current)
    }
    if (patched == current) return false
    return try {
        // Stage-and-move (writeTextAtomically) so a crash mid-write can never truncate the user's
        // primary agent config — these files belong to the agent CLIs, not to us.
        writeTextAtomically(path, patched)
        true
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not update $path: $e")
        false
    }
}

private fun readTextOrNull(path: Path): String? = try {
    if (Files.isRegularFile(path)) Files.readString(path) else null
} catch (e: Exception) {
    System.err.println("[mcp-steroid] could not read $path: $e")
    null
}
