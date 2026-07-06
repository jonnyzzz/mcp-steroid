/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * `devrig list_windows [--json]` — the CLI face of `steroid_list_windows`.
 *
 * Like `devrig project`, this returns a typed response (not a [com.jonnyzzz.mcpSteroid.mcp.ToolCallResult])
 * so it uses its own renderers rather than the shared `renderTo` envelope. The data comes from the
 * existing [ListWindowsToolHandler] bridge implementation — one source of truth with the MCP tool.
 */
fun DevrigServices.runListWindowsCommand(command: DevrigCommand.DevrigCommandListWindows): Int {
    val response = try {
        runBlocking(Dispatchers.IO) {
            StubMcpSteroidTools(this@runListWindowsCommand)
                .handler<ListWindowsToolHandler>()
                .collectListWindowsResponse()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        System.err.println("devrig list_windows failed to reach a backend: ${e.message}")
        return CliExit.UNAVAILABLE
    }

    if (command.json) {
        renderListWindowsJson(response, mcpStdout)
    } else {
        renderListWindowsText(response, mcpStdout)
    }
    return CliExit.OK
}

private val LIST_WINDOWS_JSON = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

fun renderListWindowsJson(response: ListWindowsResponse, out: PrintStream) {
    out.println(LIST_WINDOWS_JSON.encodeToString(ListWindowsResponse.serializer(), response))
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
