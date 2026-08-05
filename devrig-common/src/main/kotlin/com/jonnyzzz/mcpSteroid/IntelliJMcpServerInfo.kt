package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * Reference to the IDE's bundled MCP server plugin (`com.intellij.mcpServer`)
 * if it is loaded **and** the user has enabled it.
 *
 * The plugin runs an independent ktor-server-cio listener (default port
 * 64342, bound to 127.0.0.1) and exposes two transports:
 *  - [streamUrl] — MCP streamable HTTP
 *  - [sseUrl]    — MCP SSE
 *
 * See `docs/intellij-builtin-servers.md` for the full lifecycle / auth
 * notes. Surfaced on [PidMarker.intellijMcpServer] so devrig can discover
 * and route to the bundled IntelliJ MCP tools alongside `mcp-steroid`'s own
 * server.
 *
 * [headers] is reserved for HTTP headers a client MUST send (empty today —
 * the bundled server does not require auth — but kept aligned with the
 * other two info classes so consumers can treat all three uniformly).
 *
 * When the bundled MCP server is not present, the marker carries a `null`
 * instead of a partially-filled info object.
 */
@Serializable
data class IntelliJMcpServerInfo(
    /** `true` only if the plugin is loaded **and** `McpServerService.isRunning`. */
    val enabled: Boolean,
    /** TCP port where the IDE's MCP server accepts connections. */
    val port: Int,
    /** Full URL to the MCP streamable HTTP endpoint. */
    val streamUrl: String,
    /** Full URL to the MCP SSE endpoint. */
    val sseUrl: String,
    val headers: Map<String, String>,
)
