package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable

/**
 * Reference to IntelliJ Platform's built-in web server
 * (`BuiltInServerManager`). This is independent of MCP Steroid's own
 * Ktor server and of the optional bundled MCP Server plugin.
 *
 * [headers] carries the HTTP headers a client MUST send to reach the
 * server — typically `x-ijt: <token>`. The marker also emits an
 * [aboutUrl] with the token already embedded as `?_ijt=` so clients can
 * pick whichever form their stack prefers.
 *
 * All fields are explicit — when the server is not running, the marker
 * carries a `null` instead of a partially-filled info object.
 */
@Serializable
data class IntelliJWebServerInfo(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val baseUrl: String,
    val aboutUrl: String,
    val headers: Map<String, String>,
)
