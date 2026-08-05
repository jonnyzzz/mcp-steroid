package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * The devrig bridge endpoint — the plugin's explicit, protocol-level statement of how the `devrig` CLI
 * should connect. Deliberately SEPARATE from [McpSteroidServerInfo] (the `/mcp` MCP-client endpoint):
 * devrig speaks the bridge RPC here and MUST NOT touch the MCP endpoint.
 *
 * [rpcBaseUrl] is the FULL base URL of the bridge (e.g. `http://127.0.0.1:<port>/api/jonnyzzz/mcp-steroid/v1`);
 * devrig appends only the operation (`/tools/call/stream`, `/windows`, `/projects/stream`, …) and never
 * derives or hardcodes the host/port/prefix. [headers] carries every HTTP header devrig MUST send on each
 * request (notably `Authorization: Bearer <token>`), so devrig stays agnostic of the auth scheme and of
 * which webserver actually hosts the path.
 */
@Serializable
data class DevrigEndpointInfo(
    val rpcBaseUrl: String,
    val headers: Map<String, String>,
)
