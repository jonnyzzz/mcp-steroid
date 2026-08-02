/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.MCP_PROTOCOL_VERSION
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.McpStdioServer
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.ToolsCapability
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.devrig.DevrigVersionMetadata
import kotlinx.serialization.json.JsonObject

/**
 * Boot a real MCP stdio server inside the devrig CLI process.
 *
 * Wiring:
 *  - [McpServerCore] declares server identity, advertised capabilities, and the
 *    [DEVRIG_MCP_SERVER_INSTRUCTIONS] capability statement the `initialize` result carries.
 *  - [StubMcpSteroidTools.devrigToolSpecs] is the canonical devrig tool list; every
 *    spec from it is registered onto the core. The handlers themselves are not yet
 *    implemented in devrig — see [StubMcpSteroidTools] — so calling a tool returns
 *    an error, but `tools/list` / `prompts/list` / `resources/list` describe the
 *    full surface.
 *  - [McpStdioServer] runs the transport on [DevrigServices.mcpStdin] /
 *    [DevrigServices.mcpStdout] (NDJSON or framed, auto-detected from the first
 *    inbound frame).
 *
 * [DevrigServices] owns the original `System.in` / `System.out` references —
 * not the JVM globals after main has already routed `System.out` to stderr.
 * Suspends until [DevrigServices.mcpStdin] reaches EOF.
 */
suspend fun runStubStdioMcpServer(
    services: DevrigServices,
    /** Invoked with the live [McpServerCore] once built, before the transport loop starts. */
    onServerReady: (McpServerCore) -> Unit = {},
) {
    val server = McpServerCore(
        serverInfo = ServerInfo(
            name = "devrig",
            version = DevrigVersionMetadata.getDevrigVersion(),
        ),
        // The capability statement a deferring harness sees WITHOUT loading a tool schema
        // (#417) — see DEVRIG_MCP_SERVER_INSTRUCTIONS for why this channel and not a tool
        // description.
        instructions = DEVRIG_MCP_SERVER_INSTRUCTIONS,
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = true),
            // Advertise `logging` so clients accept our `notifications/message`
            // "update available" notices delivered over the stdio transport.
            logging = JsonObject(emptyMap()),
        ),
        // Fire a once-per-session analytics beacon recording client (agent) + server versions.
        onSessionInitialized = { session, serverInfo, clientProtocolVersion ->
            services.beacon.capture(
                event = "session_initialized",
                properties = mapOf(
                    "client_name" to (session.clientInfo?.name ?: "unknown"),
                    "client_version" to (session.clientInfo?.version ?: "unknown"),
                    "client_protocol_version" to clientProtocolVersion,
                    "server_name" to serverInfo.name,
                    "server_version" to serverInfo.version,
                    "mcp_protocol_version" to MCP_PROTOCOL_VERSION,
                )
            )
        },
    )

    onServerReady(server)

    val tools = StubMcpSteroidTools(services)
    // devrigToolSpecs() is the canonical devrig tool list — the common tools plus devrig's own
    // steroid_open_project, which advertises the required `backend_name` routing param
    // (includeBackendName = true) so calls route to one of the discovered IDE backends. The generated
    // CLI consumes the same list, so registering from it keeps the two surfaces drift-free.
    tools.devrigToolSpecs().forEach { server.toolRegistry.registerTool(it) }

    McpStdioServer(server, input = services.mcpStdin, output = services.mcpStdout).run()
}
