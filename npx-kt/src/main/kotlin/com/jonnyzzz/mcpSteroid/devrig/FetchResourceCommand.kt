/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.callToolViaSpec
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigPromptsContextHandler
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.server.FetchResourceToolHandler
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.server.canonicalResourceEntryPoints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `devrig prompt <uri>` / `devrig fetch_resource --uri=...` — the CLI face of `steroid_fetch_resource`
 * (issue #284). Runtime behavior for the schema-driven [DevrigCommand.RunTool]: the canonical grammar is
 * `--uri` (and the `prompt` alias's positional `<uri>`), both typed into [DevrigCommand.RunTool.arguments].
 *
 * Bundled `mcp-steroid://` articles ship inside the devrig binary, so this resolves **without a
 * running IDE** using [PromptsContext.Generic] when no `project_name` is present. Passing `project_name`
 * upgrades to that project's IDE-specific context via the existing routing + [DevrigPromptsContextHandler].
 * Calls are dispatched through [FetchResourceToolHandler], the same ToolSpec used by `devrig mcp`. The
 * alias command name ([DevrigCommand.RunTool.commandName]: "prompt" or "fetch_resource") is echoed into the
 * envelope, and a miss appends the canonical entry-point hints.
 */
fun DevrigServices.runFetchResourceBehavior(command: DevrigCommand.RunTool): Int {
    val presentation = presentationFor(command.json, homePaths::screenshotTmpDir)
    val uri = command.arguments.stringOrNull("uri")
    if (uri.isNullOrBlank()) {
        // Defensive: the parser already rejects a blank URI; keep a clean error if reached directly.
        return presentation.renderError(
            command.commandName, "missing <uri>. Example:\n  ${fetchResourceUsageExample()}",
            CliExit.USAGE, mcpStdout,
        )
    }

    val projectName = command.arguments.stringOrNull("project_name")?.takeUnless { it.isBlank() }
    val contextHandler: PromptsContextHandler = if (projectName == null) {
        FixedPromptsContextHandler(PromptsContext.Generic)
    } else {
        DevrigPromptsContextHandler(projectRouting)
    }
    val arguments = buildJsonObject {
        command.arguments.forEach { (key, value) -> put(key, value) }
        put("project_name", projectName ?: GENERIC_PROJECT_NAME)
    }
    val result = try {
        runBlocking(Dispatchers.IO) {
            callToolViaSpec(
                FetchResourceToolHandler { contextHandler },
                arguments,
                stderrProgressReporter(),
            )
        }
    } catch (e: IllegalArgumentException) {
        return presentation.renderError(
            command.commandName,
            "unknown --project_name '$projectName' (get it from `devrig list_projects`)",
            CliExit.USAGE,
            mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return presentation.renderError(
            command.commandName,
            "devrig ${command.commandName} failed: ${e.message}",
            CliExit.UNAVAILABLE,
            mcpStdout,
        )
    }
    val rendered = if (result.isError) result.withResourceEntryPointHints() else result
    return presentation.render(rendered, command = command.commandName, out = mcpStdout)
}

private class FixedPromptsContextHandler(private val context: PromptsContext) : PromptsContextHandler {
    override suspend fun buildPromptsContext(projectName: String): PromptsContext = context
}

private fun ToolCallResult.withResourceEntryPointHints(): ToolCallResult {
    val hints = buildString {
        append("Canonical entry points:\n")
        for (entry in canonicalResourceEntryPoints()) {
            append("  devrig prompt ").append(entry.uri).append('\n')
        }
    }
    return copy(content = content + ContentItem.Text(hints))
}

/** A runnable example for the `prompt` usage error — built from generated article URIs, never a literal. */
fun fetchResourceUsageExample(): String = "devrig prompt ${canonicalResourceEntryPointOrPlaceholder()}"

/** First canonical entry-point URI, or a bracket placeholder if the article index is somehow empty. */
fun canonicalResourceEntryPointOrPlaceholder(): String =
    canonicalResourceEntryPoints().firstOrNull()?.uri ?: "<resource-uri>"

private const val GENERIC_PROJECT_NAME = "devrig-cli-generic"
