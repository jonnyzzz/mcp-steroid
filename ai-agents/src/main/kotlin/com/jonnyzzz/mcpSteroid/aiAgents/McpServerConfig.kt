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

/** The directory devrig owns under the user home — the one name every devrig path hangs off. */
const val DEVRIG_HOME_DIR_NAME = ".mcp-steroid"

/**
 * The stable launcher's file name for this OS: a `.cmd` shim on Windows (so cmd.exe and PowerShell resolve
 * it via PATHEXT), a plain `devrig` script on POSIX.
 */
fun devrigLauncherFileName(windows: Boolean): String = if (windows) "devrig.cmd" else "devrig"

/**
 * devrig's home rendered for humans — see the display policy on [devrigLauncherDisplayPath].
 */
fun devrigHomeDisplayPath(userHome: String, windows: Boolean): String =
    displayPath(userHome, windows, DEVRIG_HOME_DIR_NAME)

/**
 * The stable launcher path rendered for humans.
 *
 * Display policy for every user-visible devrig path: the **real absolute home** (never `~`), joined with
 * the OS-native separator — `C:\Users\me\.mcp-steroid\bin\devrig.cmd` on Windows,
 * `/home/me/.mcp-steroid/bin/devrig` on POSIX. `~` is a lie on Windows (neither cmd.exe nor an
 * `mcp.json`-reading client expands it), and a copy button must put exactly what is displayed on the
 * clipboard — so the display IS the clipboard content, on every OS.
 */
fun devrigLauncherDisplayPath(userHome: String, windows: Boolean): String =
    displayPath(userHome, windows, DEVRIG_HOME_DIR_NAME, "bin", devrigLauncherFileName(windows))

/**
 * The one-line command that runs devrig as a stdio MCP server — what a user types into an MCP client that
 * asks for a command line instead of reading an `mcpServers` file. The launcher path follows the
 * [devrigLauncherDisplayPath] policy (real absolute home, OS-native separators) and is quoted when it
 * contains a space, because every shell and client splits an unquoted `C:\Users\First Last\…` in two.
 */
fun devrigMcpCommandLine(userHome: String, windows: Boolean): String {
    val launcher = devrigLauncherDisplayPath(userHome, windows)
    val quoted = if (' ' in launcher) "\"$launcher\"" else launcher
    return "$quoted mcp"
}

private fun displayPath(userHome: String, windows: Boolean, vararg segments: String): String {
    val separator = if (windows) "\\" else "/"
    // A Windows home can arrive with forward slashes (a config file, a test) — render it Windows-naturally.
    val home = (if (windows) userHome.replace('/', '\\') else userHome).trimEnd('/', '\\')
    return (listOf(home) + segments).joinToString(separator)
}

/**
 * OS-correct stdio invocation of the stable devrig launcher at [launcherPath], with [args].
 *
 * Lives here, in the module both halves already depend on, because two places need the exact same answer:
 * devrig itself when it registers an agent (`DevrigUserLauncher.invocation`), and the IDE plugin when it
 * shows the manual configuration for a client devrig cannot register (Cursor, Windsurf, anything reading an
 * `mcpServers` file). If the two ever built this string separately, the copyable snippet would quietly stop
 * matching what the button writes.
 *
 * Windows runs the `.cmd` through `cmd.exe /d /c` — a `.cmd` is not directly executable as a process image,
 * and `/d` skips any AutoRun script. The launcher path is quoted because `cmd.exe` parses everything after
 * `/c` as ONE command line, so an unquoted `C:\Users\First Last\…` splits and the server never starts.
 * POSIX execs the script directly.
 */
fun devrigStdioMcpCommand(launcherPath: String, windows: Boolean, args: List<String> = listOf("mcp")): StdioMcpCommand =
    if (windows) {
        StdioMcpCommand(
            command = "cmd.exe",
            args = listOf("/d", "/c", (listOf("\"$launcherPath\"") + args).joinToString(" ")),
        )
    } else {
        StdioMcpCommand(command = launcherPath, args = args)
    }

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
