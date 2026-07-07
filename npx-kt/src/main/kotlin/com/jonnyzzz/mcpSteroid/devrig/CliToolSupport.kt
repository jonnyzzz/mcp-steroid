/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.PrintStream
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Shared support for `devrig` subcommands that are thin frontends over an existing MCP tool.
 *
 * This is deliberately a small helper set, NOT a "CLI-from-MCP-schema" generator: it factors out
 * only the parts every tool-backed command repeats — rendering a [ToolCallResult] to stdout/stderr,
 * a stable `--json` envelope, meaningful exit codes, and progress plumbing. The tool behavior itself
 * always lives behind the existing bridge handlers (single source of truth); the CLI never
 * reimplements it.
 */

/** Meaningful, stable process exit codes shared by all tool-backed commands. */
object CliExit {
    /** Success. */
    const val OK: Int = 0

    /**
     * The tool call reached its target but the tool itself reported `isError` (e.g. a script threw,
     * a resource was missing). Distinct from a usage/infra failure so scripts can tell them apart.
     */
    const val TOOL_ERROR: Int = 1

    /** Bad invocation the user can fix: missing/blank required args, unknown project_name, bad file. */
    const val USAGE: Int = 64

    /** The command could not reach a backend / the bridge failed (no IDE running, connection refused). */
    const val UNAVAILABLE: Int = 69
}

/** A [McpProgressReporter] that streams progress to stderr so stdout stays clean for data. */
fun stderrProgressReporter(err: PrintStream = System.err): McpProgressReporter =
    object : McpProgressReporter {
        override fun report(message: String) {
            err.println(message)
        }
    }

/**
 * Renders a [ToolCallResult] for a CLI command and returns the process exit code.
 *
 * Contract (locked by tests, matches the rest of devrig):
 *  - Non-error text content → **stdout** (so `| jq`, `| less` work).
 *  - Error content (`isError == true`) → **stderr**; stdout stays empty.
 *  - `--json` emits a single stable envelope on stdout regardless of success/failure.
 *
 * @param command the CLI subcommand name, echoed into the JSON envelope for context.
 */
fun ToolCallResult.renderTo(
    command: String,
    json: Boolean,
    out: PrintStream,
    err: PrintStream = System.err,
): Int {
    if (json) {
        out.println(toEnvelopeJson(command))
        return if (isError) CliExit.TOOL_ERROR else CliExit.OK
    }

    val sink = if (isError) err else out
    for (item in content) {
        when (item) {
            is ContentItem.Text -> sink.println(item.text)
            is ContentItem.Image -> sink.println("[image: ${item.mimeType}, ${item.decodedByteCount()} bytes]")
            is ContentItem.Resource -> {
                val res = item.resource
                sink.println("[resource: ${res.uri}${res.mimeType?.let { " ($it)" } ?: ""}]")
                res.text?.let { sink.println(it) }
            }
        }
    }
    return if (isError) CliExit.TOOL_ERROR else CliExit.OK
}

/**
 * The single, unified `--json` envelope shared by EVERY `devrig` MCP-as-CLI command:
 *
 * ```json
 * { "tool": {"name":"devrig","version":"..."}, "command": "<name>", "isError": <bool>, "data": { ... } }
 * ```
 *
 * `data` holds the command-specific payload: `{content:[...]}` for tool-result commands (fetch_resource,
 * execute_code, …), `{projects:[...]}` for list_projects, `{windows,backgroundTasks}` for list_windows.
 * One outer shape keeps agents and tests from special-casing per command. The [Json] instance is shared
 * so encoding stays consistent.
 */
val CLI_ENVELOPE_JSON: Json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false }

/** Wraps a command-specific [data] object in the unified envelope and renders it to a string. */
fun cliEnvelopeJson(command: String, isError: Boolean, data: JsonObject): String {
    val payload = buildJsonObject {
        put("tool", buildJsonObject {
            put("name", "devrig")
            put("version", DevrigVersionMetadata.getDevrigVersion())
        })
        put("command", command)
        put("isError", isError)
        put("data", data)
    }
    return CLI_ENVELOPE_JSON.encodeToString(JsonObject.serializer(), payload)
}

/**
 * Envelope for a tool-backed command's [ToolCallResult]: `data:{content:[...]}`. Image blobs are
 * summarized (mimeType + byte count) rather than inlined so stdout stays usable.
 */
fun ToolCallResult.toEnvelopeJson(command: String): String {
    val data = buildJsonObject {
        putJsonArray("content") {
            for (item in content) {
                add(buildJsonObject {
                    when (item) {
                        is ContentItem.Text -> {
                            put("type", "text")
                            put("text", item.text)
                        }
                        is ContentItem.Image -> {
                            put("type", "image")
                            put("mimeType", item.mimeType)
                            put("bytes", item.decodedByteCount())
                        }
                        is ContentItem.Resource -> {
                            put("type", "resource")
                            put("uri", item.resource.uri)
                            item.resource.mimeType?.let { put("mimeType", it) }
                            item.resource.text?.let { put("text", it) }
                        }
                    }
                })
            }
        }
    }
    return cliEnvelopeJson(command, isError, data)
}

private fun ContentItem.Image.decodedByteCount(): Int =
    try {
        Base64.getDecoder().decode(data).size
    } catch (e: IllegalArgumentException) {
        // Not valid base64 — report the raw length rather than failing the whole render.
        System.err.println("devrig: image payload was not valid base64 (${e.message}); reporting raw length")
        data.length
    }
