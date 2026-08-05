/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlin.collections.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val DEFAULT_SERVER_NAME = "mcp-steroid"

/**
 * Structured model of the MCP server connection info.
 * Single source of truth used by both the settings UI and the .md file writer.
 *
 * [commands] is an ordered map of display name → CLI command string,
 * allowing new agents to be added without changing call sites.
 */
data class McpConnectionInfo(
    val serverUrl: String,
    val commands: Map<String, String>,
    val jsonConfig: String,
    val feedbackUrl: String = "https://github.com/jonnyzzz/mcp-steroid/issues",
) {
    fun toMarkdown(): String = buildString {
        appendLine("# MCP Steroid Server")
        appendLine()
        appendLine("- **URL**: $serverUrl")
        appendLine()
        appendLine("=== Quick Start ===")
        appendLine()
        for ((name, command) in commands) {
            appendLine("$name CLI:")
            appendLine("  $command")
            appendLine()
        }
        appendLine("Cursor and other's JSON config:")
        appendLine()
        appendLine("This is what `mcpServers` JSON may look like:")
        jsonConfig.lines().forEach { append("  "); appendLine(it) }
        appendLine()
        appendLine("## Feedback")
        appendLine()
        appendLine("Report issues: $feedbackUrl")
        appendLine()
    }

    companion object {
        fun build(serverUrl: String) = McpConnectionInfo(
            serverUrl = serverUrl,
            commands = linkedMapOf(
                "Claude" to claudeMcpAddCommand(serverUrl),
                "Codex" to codexMcpAddCommand(serverUrl),
                "Gemini" to geminiMcpAddCommand(serverUrl),
            ),
            jsonConfig = genericMcpServersJson(serverUrl),
        )
    }
}

data class StdioMcpCommand(
    val command: String,
    val args: List<String> = emptyList(),
)

private val stdioMcpServersJsonFormat = Json { prettyPrint = true }

/**
 * The stdio `mcpServers` JSON snippet for MCP clients configured by hand (an mcp.json-style file):
 * [command] launches the MCP server over stdio — for devrig, the stable `~/.mcp-steroid/bin` launcher
 * with the `mcp` subcommand (see `DevrigUserLauncher.invocation`). Built with kotlinx.serialization,
 * never hand-concatenated: the launcher path is dynamic and must be JSON-escaped (a Windows path
 * contains backslashes, and the Windows `cmd.exe` invocation embeds quotes). Contrast with
 * [genericMcpServersJson] — the HTTP variant the in-IDE settings page shows for the in-IDE server.
 */
fun stdioMcpServersJson(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): String =
    stdioMcpServersJsonFormat.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            putJsonObject("mcpServers") {
                putJsonObject(serverName) {
                    put("command", command.command)
                    putJsonArray("args") { command.args.forEach { add(it) } }
                }
            }
        },
    )

fun genericMcpServersJson(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME) = buildString {
    appendLine("{")
    appendLine("  \"mcpServers\": {")
    appendLine("    \"$serverName\": {")
    appendLine("      \"type\": \"http\",")
    appendLine("      \"url\": \"$serverUrl\"")
    appendLine("    }")
    appendLine("  }")
    appendLine("}")
}

fun geminiMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--type", "http", serverUrl, "--scope", "user", "--trust")

fun codexMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--url", serverUrl)

fun claudeMcpAddArgs(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", "--transport", "http", "--scope", "user", serverName, serverUrl)

fun geminiMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", "--type", "stdio", "--scope", "user", "--trust", serverName, command.command) + command.args

fun codexMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", serverName, "--", command.command) + command.args

fun claudeMcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "add", "--scope", "user", serverName, "--", command.command) + command.args

// `mcp remove` arg builders — used by `devrig install` to clear any prior registration before
// re-adding (an idempotent upsert), so re-running install always converges on the current launcher
// path and subcommand. Removal targets the same user scope the add commands write to.
fun geminiMcpRemoveArgs(serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "remove", "--scope", "user", serverName)

fun codexMcpRemoveArgs(serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "remove", serverName)

fun claudeMcpRemoveArgs(serverName: String = DEFAULT_SERVER_NAME): List<String> =
    listOf("mcp", "remove", "--scope", "user", serverName)

// `mcp list` arg builders — used by `devrig install` to review the agent's currently registered MCP
// servers so it can consolidate every devrig/mcp-steroid entry into one. codex emits JSON; claude and
// gemini emit a line-oriented `name: <command> - <status>` listing.
fun geminiMcpListArgs(): List<String> = listOf("mcp", "list")

fun codexMcpListArgs(): List<String> = listOf("mcp", "list", "--json")

fun claudeMcpListArgs(): List<String> = listOf("mcp", "list")

private fun renderCommand(binary: String, args: List<String>): String =
    (listOf(binary) + args).joinToString(" ")

fun geminiMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("gemini", geminiMcpAddArgs(serverUrl, serverName))
}

fun codexMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("codex", codexMcpAddArgs(serverUrl, serverName))
}

fun claudeMcpAddCommand(serverUrl: String, serverName: String = DEFAULT_SERVER_NAME): String {
    return renderCommand("claude", claudeMcpAddArgs(serverUrl, serverName))
}
