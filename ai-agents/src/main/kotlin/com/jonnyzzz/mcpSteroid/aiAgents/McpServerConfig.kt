/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlin.collections.plus

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
    val feedbackUrl: String = "https://devrig.dev",
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
        appendLine("Report issues, join the Discord community: $feedbackUrl")
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
