/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import java.io.PrintStream

/**
 * Human-readable renderer for `devrig list_windows` (the CLI face of `steroid_list_windows`). The
 * `--json` path emits the unified `{tool, command, isError, data}` envelope from the tool's structured
 * response; this table is its human counterpart, kept as a dedicated renderer so the two never drift.
 */
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
