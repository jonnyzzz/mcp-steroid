/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

/**
 * `devrig list_windows [--json]` — the CLI face of `steroid_list_windows`.
 *
 * Returns a typed response (not a [com.jonnyzzz.mcpSteroid.mcp.ToolCallResult]) so it has its own human
 * renderer, but `--json` uses the SAME unified envelope as every other command ([cliEnvelopeJson]). The
 * data comes from the existing [ListWindowsToolHandler] bridge implementation — one source of truth with
 * the MCP tool. `tools` is defaulted so tests can inject a fake snapshot.
 */
fun DevrigServices.runListWindowsCommand(
    command: DevrigCommand.DevrigCommandListWindows,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    val presentation = presentationFor(command.json) { homePaths.home }
    val response = try {
        runBlocking(Dispatchers.IO) {
            tools.handler<ListWindowsToolHandler>().collectListWindowsResponse()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return presentation.renderError(
            "list_windows", "devrig list_windows failed to reach a backend: ${e.message}",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }

    // list_windows/list_projects don't route their SUCCESS path through Presentation.render: the payload
    // is a typed ListWindowsResponse (list_windows_envelope_json), not a ToolCallResult, so each keeps its
    // own success renderer here; only the shared error envelope goes through `presentation`.
    if (command.json) {
        mcpStdout.println(listWindowsEnvelopeJson(response))
    } else {
        renderListWindowsText(response, mcpStdout)
    }
    return CliExit.OK
}

/** Wraps the windows response in the unified `{tool, command, isError, data}` envelope. */
fun listWindowsEnvelopeJson(response: ListWindowsResponse): String {
    val data = CLI_ENVELOPE_JSON
        .encodeToJsonElement(ListWindowsResponse.serializer(), response)
        .jsonObject
    return cliEnvelopeJson(command = "list_windows", isError = false, data = data)
}

fun renderListWindowsText(response: ListWindowsResponse, out: PrintStream) {
    if (response.windows.isEmpty() && response.backgroundTasks.isEmpty()) {
        out.println("No IDE windows detected.")
        return
    }
    out.println("Windows (${response.windows.size}):")
    for ((index, w) in response.windows.withIndex()) {
        val flags = buildList {
            if (w.modalDialogShowing) add("modal")
            if (w.indexingInProgress == true) add("indexing")
            if (w.projectInitialized == true) add("initialized")
            if (w.isActive) add("active")
        }.joinToString(", ").ifEmpty { "—" }
        out.println("  [${index + 1}] window_id=${w.windowId}  project_name=${w.projectName ?: "—"}")
        out.println("        title:   ${w.title ?: "—"}")
        out.println("        path:    ${w.projectPath ?: "—"}")
        out.println("        backend: ${w.backendName ?: "—"}")
        out.println("        state:   $flags")
    }
    if (response.backgroundTasks.isNotEmpty()) {
        out.println()
        out.println("Background tasks (${response.backgroundTasks.size}):")
        for (task in response.backgroundTasks) {
            val pct = task.fraction?.let { " ${(it * 100).toInt()}%" } ?: if (task.isIndeterminate) " (indeterminate)" else ""
            out.println("  - ${task.title}$pct — ${task.text} (project_name=${task.projectName ?: "—"})")
        }
    }
}
