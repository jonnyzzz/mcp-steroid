/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

/**
 * `devrig list_projects [--json]` — the CLI face of `steroid_list_projects`, reconciled with the existing
 * `devrig project` (#191):
 *  - **human** output reuses `devrig project`'s renderer (so the two never drift);
 *  - **`--json`** uses the unified envelope ([cliEnvelopeJson]) built from the same
 *    [ListProjectsToolHandler] the MCP tool uses, exposing `project_name` (the routing key) for the
 *    other commands.
 *
 * `tools` is defaulted so tests can inject a fake routing snapshot.
 */
fun DevrigServices.runListProjectsCommand(
    command: DevrigCommand.DevrigCommandListProjects,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    if (!command.json) {
        // Human path: identical to `devrig project` (port-scan footer + routing table).
        return runProjectCommand(DevrigCommand.DevrigCommandProject(debug = command.debug, json = false))
    }

    val response = try {
        runBlocking(Dispatchers.IO) {
            tools.handler<ListProjectsToolHandler>().collectListProjectsResponse()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("devrig list_projects failed to reach a backend: ${e.message}")
        return CliExit.UNAVAILABLE
    }
    mcpStdout.println(listProjectsEnvelopeJson(response))
    return CliExit.OK
}

/** Wraps the projects response in the unified `{tool, command, isError, data}` envelope. */
fun listProjectsEnvelopeJson(response: ListProjectsResponse): String {
    val data = CLI_ENVELOPE_JSON
        .encodeToJsonElement(ListProjectsResponse.serializer(), response)
        .jsonObject
    return cliEnvelopeJson(command = "list_projects", isError = false, data = data)
}
