/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.DevrigPromptsContextHandler
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.server.canonicalResourceEntryPoints
import com.jonnyzzz.mcpSteroid.server.resolveResourcePayload

/**
 * `devrig prompt <uri>` / `devrig fetch_resource --uri=...` — the CLI face of `steroid_fetch_resource`.
 *
 * Bundled `mcp-steroid://` articles ship inside the devrig binary, so this resolves **without a
 * running IDE** using [PromptsContext.Generic]. Passing `--project_name` upgrades to that project's
 * IDE-specific context via the existing routing + [DevrigPromptsContextHandler] (which needs the IDE
 * to be discovered). URI → payload resolution is the shared [resolveResourcePayload] — the same code
 * the MCP tool uses, so both surfaces render identically.
 */
fun DevrigServices.runFetchResourceCommand(command: DevrigCommand.DevrigCommandFetchResource): Int {
    val presentation = presentationFor(command.json) { homePaths.home }
    val uri = command.uri
    if (uri.isNullOrBlank()) {
        // Defensive: the parser already rejects a blank URI; keep a clean error if reached directly.
        return presentation.renderError(
            command.commandName, "missing <uri>. Example:\n  ${fetchResourceUsageExample()}",
            CliExit.USAGE, mcpStdout,
        )
    }

    val context = try {
        resolvePromptsContext(command.projectName)
    } catch (e: PromptsContextResolutionException) {
        return presentation.renderError(
            command.commandName, e.message ?: "failed to resolve project context",
            CliExit.USAGE, mcpStdout,
        )
    }

    val payload = resolveResourcePayload(uri, context)
    val result = if (payload != null) {
        ToolCallResult(content = listOf(ContentItem.Text(text = payload)))
    } else {
        ToolCallResult.errorResult(resourceNotFoundMessage(uri))
    }
    // Echo the alias the user actually typed ("prompt" vs "fetch_resource") into the `--json` envelope.
    return presentation.render(result, command = command.commandName, out = mcpStdout)
}

/**
 * Resolves the [PromptsContext] for a resource fetch. No project → [PromptsContext.Generic] (bundled
 * docs, no IDE needed). With a project → that project's IDE build → context, reusing the same routing
 * the other CLI/MCP commands use.
 */
private fun DevrigServices.resolvePromptsContext(projectName: String?): PromptsContext {
    if (projectName.isNullOrBlank()) return PromptsContext.Generic
    val route = try {
        projectRouting.requireProject(projectName)
    } catch (e: IllegalArgumentException) {
        throw PromptsContextResolutionException(
            "unknown --project_name '$projectName' (get it from `devrig list_projects`)"
        )
    }
    return DevrigPromptsContextHandler.promptsContextFromBuild(route.route.ide.build)
}

private class PromptsContextResolutionException(message: String) : RuntimeException(message)

private fun resourceNotFoundMessage(uri: String): String = buildString {
    append("Resource not found: ").append(uri).append('\n')
    append("Canonical entry points:\n")
    for (entry in canonicalResourceEntryPoints()) {
        append("  devrig prompt ").append(entry).append('\n')
    }
}

/** A runnable example for the `prompt` usage error — built from generated article URIs, never a literal. */
fun fetchResourceUsageExample(): String = "devrig prompt ${canonicalResourceEntryPointOrPlaceholder()}"

/** First canonical entry-point URI, or a bracket placeholder if the article index is somehow empty. */
fun canonicalResourceEntryPointOrPlaceholder(): String =
    canonicalResourceEntryPoints().firstOrNull() ?: "<resource-uri>"
