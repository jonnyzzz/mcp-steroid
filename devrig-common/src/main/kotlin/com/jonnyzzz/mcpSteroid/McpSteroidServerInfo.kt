package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * MCP Steroid's own Ktor-hosted MCP server — the `/mcp` endpoint, for MCP
 * clients only. devrig MUST NOT touch it; it uses [DevrigEndpointInfo] instead.
 * Sibling of [IntelliJMcpServerInfo] (the IDE-bundled MCP plugin) and
 * [IntelliJWebServerInfo] (IntelliJ's built-in web server).
 *
 * [mcpUrl] is the full URL — that alone is enough to address the server, so no
 * separate port field is carried. [headers] carries the HTTP headers a client
 * MUST send on every request to this server — notably `Authorization: Bearer
 * <token>`. Storing them as a typed map keeps the server-info classes shaped
 * consistently and frees consumers from understanding the auth scheme.
 *
 * [pluginPath] is the absolute path to the mcp-steroid plugin install folder on
 * the IDE host. Null when the field was omitted by an older plugin version.
 * A future step uses it to update the plugin before (re)starting a managed backend.
 */
@Serializable
data class McpSteroidServerInfo(
    val mcpUrl: String,
    val headers: Map<String, String>,
    val pluginPath: String? = null,
)
