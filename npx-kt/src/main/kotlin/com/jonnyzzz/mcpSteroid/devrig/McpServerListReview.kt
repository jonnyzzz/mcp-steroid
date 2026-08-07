/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One MCP server as reported by an agent CLI's `mcp list`. [commandLine] is the launch command joined
 * for substring inspection (empty for non-stdio/HTTP servers we can't describe).
 */
data class McpServerRef(val name: String, val commandLine: String)

/** Server names devrig owns. `mcp-steroid` is canonical; `devrig` is a legacy/alternative spelling. */
val DEVRIG_SERVER_NAMES: Set<String> = setOf("mcp-steroid", "devrig")

/** True when the server's registered name is one devrig owns. */
fun McpServerRef.matchesDevrigName(): Boolean = name.lowercase() in DEVRIG_SERVER_NAMES

/** True when the server's registered launch command runs the `devrig` binary (regardless of its name). */
fun McpServerRef.matchesDevrigCommand(): Boolean = commandLine.contains("devrig")

/**
 * True when this registration belongs to devrig. Both signals are checked: the server's **name** and its
 * actual **configuration** (the launch command). Checking the command as well catches a devrig server
 * registered under a non-standard name, and checking the name catches an `mcp-steroid`/`devrig` entry
 * whose command is stale or otherwise no longer recognisable.
 */
fun McpServerRef.isDevrigOwned(): Boolean = matchesDevrigName() || matchesDevrigCommand()

/** Human-readable reason this entry was detected, naming which signal(s) matched — for install narration. */
fun McpServerRef.devrigMatchReason(): String = when {
    matchesDevrigName() && matchesDevrigCommand() -> "name + config"
    matchesDevrigName() -> "name"
    matchesDevrigCommand() -> "config"
    else -> "no match"
}

/**
 * Parse the output of `<agent> mcp list` into the registered servers. codex emits a JSON array
 * (`--json`); claude and gemini emit a line-oriented `name: <command> - <status>` listing. Parsing is
 * best-effort: a format we can't read yields an empty list (the caller falls back to reconciling the
 * known devrig names), and a malformed codex JSON payload is logged rather than thrown.
 */
fun parseMcpServerList(agent: AiAgentCli, output: String): List<McpServerRef> = when (agent) {
    AiAgentCli.CODEX -> parseCodexJsonServerList(output)
    AiAgentCli.CLAUDE, AiAgentCli.GEMINI -> parseLineServerList(output)
}

private fun parseCodexJsonServerList(output: String): List<McpServerRef> {
    val trimmed = output.trim()
    if (!trimmed.startsWith("[")) return emptyList()
    val array = try {
        Json.parseToJsonElement(trimmed).jsonArray
    } catch (e: Exception) {
        System.err.println("devrig install: could not parse codex MCP list JSON: ${e.message}")
        return emptyList()
    }
    return array.mapNotNull { element ->
        val obj = element.jsonObject
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val transport = obj["transport"]?.jsonObject
        val command = transport?.get("command")?.jsonPrimitive?.contentOrNull.orEmpty()
        val args = transport?.get("args")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        McpServerRef(name, (listOf(command) + args).joinToString(" ").trim())
    }
}

private fun parseLineServerList(output: String): List<McpServerRef> =
    output.lineSequence()
        .map { it.trim() }
        // Each server is `name: <command> - <status>`. Skip the "Checking MCP server health…" banner,
        // blank lines, and anything without a `name:` prefix.
        .filter { it.isNotEmpty() && it.contains(": ") && !it.startsWith("Checking") }
        .mapNotNull { line ->
            val name = line.substringBefore(": ").trim()
            // A real server name is a single token; a stray sentence with ": " in it is not.
            if (name.isEmpty() || name.any { it.isWhitespace() }) return@mapNotNull null
            var rest = line.substringAfter(": ")
            // Drop the trailing " - ✓ Connected" / " - ✗ Failed to connect" health suffix.
            val statusSep = rest.lastIndexOf(" - ")
            if (statusSep >= 0) rest = rest.substring(0, statusSep)
            McpServerRef(name, rest.trim())
        }
        .toList()
