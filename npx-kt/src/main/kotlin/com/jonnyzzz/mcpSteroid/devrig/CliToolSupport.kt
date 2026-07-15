/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.PrintStream
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    /** Bad invocation the user can fix: missing/blank required args, unknown project_name, malformed path. */
    const val USAGE: Int = 64

    /**
     * The command reached its target but the data it produced/received was unusable — e.g. the bridge
     * returned a screenshot result with no image payload, or an image whose base64 could not be decoded.
     * Distinct from [USAGE] (a fixable-input mistake) and [UNAVAILABLE] (could not reach a backend), so a
     * `--json` consumer can tell "your fault" from "no IDE" from "bad data from the IDE".
     * Value follows BSD sysexits `EX_DATAERR`.
     */
    const val DATA_ERROR: Int = 65

    /** The command could not reach a backend / the bridge failed (no IDE running, connection refused). */
    const val UNAVAILABLE: Int = 69

    /**
     * A genuine filesystem I/O failure the invocation cannot fix by changing an argument — a readable
     * `--code-file` path that fails mid-read, or an `--out` target that exists as a path string but
     * cannot be written (a directory, a permission denial). Kept distinct from [USAGE] so a malformed
     * *path string* (fixable) is not conflated with a real write/read failure. Value follows BSD
     * sysexits `EX_IOERR`.
     */
    const val IO_ERROR: Int = 74
}

/**
 * Strips Java stack-frame noise (`\tat …` lines and the `... N more` continuations) out of a server
 * error message so the agent sees the human-readable message, not a leaked JVM trace. Keeps every
 * non-frame line (including `Caused by:` headers, which carry the actual cause message).
 *
 * Scope: apply this ONLY to a tool's error output where a trace is never the agent's own code (e.g.
 * `input`, whose failures are IDE-side parse errors). Do NOT apply it to `execute_code`, which returns
 * `stackTraceToString()` of the agent's OWN script on purpose — that trace is the whole point.
 */
fun sanitizeServerError(text: String): String =
    text.lineSequence()
        .filterNot { line ->
            val t = line.trimStart()
            t.startsWith("at ") || t.startsWith("... ") && t.endsWith(" more")
        }
        .joinToString("\n")
        .trim()

/** A [McpProgressReporter] that streams progress to stderr so stdout stays clean for data. */
fun stderrProgressReporter(err: PrintStream = System.err): McpProgressReporter =
    object : McpProgressReporter {
        override fun report(message: String) {
            err.println(message)
        }
    }

/**
 * Renders CLI output for a command as either a `--json` envelope or human-readable console text — the
 * single fork point for that choice. Each implementation owns its own branch body in full (Tenet: no
 * `if (json)` scattered through shared render code); [presentationFor] is the only place that maps the
 * `--json` flag onto a concrete implementation.
 *
 * Contract (locked by tests, matches the rest of devrig):
 *  - [Console]: non-error text content → **stdout** (so `| jq`, `| less` work); error content
 *    (`isError == true`) → **stderr**, stdout stays empty.
 *  - [Json]: a single stable envelope on stdout regardless of success/failure.
 *
 * [Console.imageDir] is a provider (not a value) so a future console image-to-file renderer (Task 6) can
 * resolve a fresh temp directory per render without changing this interface.
 */
sealed interface Presentation {
    /** Renders a [ToolCallResult] for [command] and returns the process exit code. */
    fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream = System.err): Int

    /** Renders a CLI-level failure (usage/parse, routing, bridge error) for [command]; returns [exit] verbatim. */
    fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream = System.err): Int

    /** Renders a successful `take_screenshot` whose image was additionally saved to [savedOut]. */
    fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, out: PrintStream): Int

    /** `--json`: a single stable envelope on stdout, built from [toEnvelopeJson] / [cliEnvelopeJson]. */
    class Json : Presentation {
        override fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int {
            out.println(result.toEnvelopeJson(command))
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        override fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int {
            val data = buildJsonObject {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message)
                    })
                }
            }
            out.println(cliEnvelopeJson(command, isError = true, data = data))
            return exit
        }

        override fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, out: PrintStream): Int {
            val data = buildJsonObject {
                for ((key, value) in result.contentDataJson()) put(key, value)
                put("savedOut", savedOut)
            }
            out.println(cliEnvelopeJson("take_screenshot", isError = false, data = data))
            return CliExit.OK
        }
    }

    /**
     * Human-readable console text. [imageDir] is unused this task — it exists so Task 6 can plug in a
     * temp-dir provider for writing console image content to a file instead of a byte-count placeholder.
     */
    class Console(private val imageDir: () -> Path) : Presentation {
        override fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int {
            val sink = if (result.isError) err else out
            for (item in result.content) {
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
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        override fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int {
            err.println(message)
            return exit
        }

        override fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, out: PrintStream): Int {
            val withNote = ToolCallResult(content = result.content + ContentItem.Text("Saved --out: $savedOut"))
            return render(withNote, command = "take_screenshot", out = out)
        }
    }
}

/** Maps the `--json` flag onto a concrete [Presentation]; the only place the boolean is branched on. */
fun presentationFor(json: Boolean, imageDir: () -> Path): Presentation =
    if (json) Presentation.Json() else Presentation.Console(imageDir)

/** Fallback [Presentation.Console] image-dir provider for callers with no [DevrigServices] in scope (tests). */
private val defaultImageDir: () -> Path = { Path.of(System.getProperty("user.home")) }

/**
 * Renders a [ToolCallResult] for a CLI command and returns the process exit code. Thin shim over
 * [presentationFor] kept for call sites/tests that don't have a [DevrigServices] receiver in scope; new
 * production call sites should build a [Presentation] once per command instead (see [ToolBackedCommands]).
 *
 * @param command the CLI subcommand name, echoed into the JSON envelope for context.
 */
fun ToolCallResult.renderTo(
    command: String,
    json: Boolean,
    out: PrintStream,
    err: PrintStream = System.err,
): Int = presentationFor(json, defaultImageDir).render(this, command, out, err)

/**
 * The unified JSON envelope for all `devrig` CLI commands, shared across tool-backed subcommands.
 *
 * `data` shape is command-specific: `{content:[...]}` for tool-result commands, `{projects:[...]}`
 * for list_projects, `{windows,backgroundTasks}` for list_windows.
 */
val CLI_ENVELOPE_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Renders a CLI-level failure (usage/parse, routing, or bridge error) consistently, so a `--json`
 * consumer can parse EVERY failure the same way it parses success:
 *  - `--json` → emits the unified `{tool, command, isError:true, data:{content:[{type,text}]}}` envelope
 *    on [out] (stdout), matching [ToolCallResult.toEnvelopeJson]'s shape.
 *  - otherwise → prints [message] to [err] (stderr), keeping stdout clean.
 *
 * Returns [exit] verbatim so callers keep their meaningful [CliExit] codes (USAGE / UNAVAILABLE / …);
 * the exit code is independent of the `--json` toggle. Thin shim over [presentationFor] kept for call
 * sites/tests without a [DevrigServices] receiver in scope.
 */
fun renderCliError(
    command: String,
    message: String,
    json: Boolean,
    exit: Int,
    out: PrintStream,
    err: PrintStream = System.err,
): Int = presentationFor(json, defaultImageDir).renderError(command, message, exit, out, err)

/**
 * Renders a successful `take_screenshot` with a structured `savedOut` path in the JSON envelope.
 * `savedOut` lives in the devrig-owned CLI envelope, not the frozen wire DTO [ToolCallResult] (Tenet 5).
 * Thin shim over [presentationFor] kept for call sites/tests without a [DevrigServices] receiver in scope.
 */
fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, json: Boolean, out: PrintStream): Int =
    presentationFor(json, defaultImageDir).renderScreenshotSaved(result, savedOut, out)

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

/** Envelope for a [ToolCallResult]: `data:{content:[...]}`, native [ContentItem] serialization. */
fun ToolCallResult.toEnvelopeJson(command: String): String =
    cliEnvelopeJson(command, isError, contentDataJson())

/**
 * Builds the `data:{content:[...]}` object for a [ToolCallResult] — the same payload
 * [toEnvelopeJson] wraps, but exposed so a command can merge command-specific keys alongside it.
 *
 * `content` is [ContentItem]'s own `@Serializable` shape, encoded via [McpJson] (the same
 * `classDiscriminator = "type"` config the wire protocol uses) — not a hand-built second copy.
 * Image items therefore carry `data` (base64) as-is, and resource items serialize to their native
 * `{type:"resource","resource":{...}}` shape (C6, C7).
 */
fun ToolCallResult.contentDataJson(): JsonObject = buildJsonObject {
    put("content", McpJson.encodeToJsonElement(ListSerializer(ContentItem.serializer()), content))
}

/** Used only by the human-readable console renderer ([renderTo]); the `--json` envelope carries `data` as-is. */
private fun ContentItem.Image.decodedByteCount(): Int =
    try {
        Base64.getDecoder().decode(data).size
    } catch (e: IllegalArgumentException) {
        // Not valid base64 — report the raw length rather than failing the whole render.
        System.err.println("devrig: image payload was not valid base64 (${e.message}); reporting raw length")
        data.length
    }
