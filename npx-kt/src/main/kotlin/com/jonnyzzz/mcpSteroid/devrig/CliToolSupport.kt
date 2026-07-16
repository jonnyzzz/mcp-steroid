/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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

/** Stable process exit codes shared by tool-backed commands. */
object CliExit {
    /** Success. */
    const val OK: Int = 0

    /** The backend returned a [ToolCallResult] with `isError=true`. */
    const val TOOL_ERROR: Int = 1

    /** Bad invocation the user can fix: missing/blank required args, unknown project_name, malformed path. */
    const val USAGE: Int = 64

    /** The backend returned unusable data, such as an invalid image payload. */
    const val DATA_ERROR: Int = 65

    /** The command could not reach a backend / the bridge failed (no IDE running, connection refused). */
    const val UNAVAILABLE: Int = 69

    /** A filesystem read/write failure. */
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

/** Renders tool results as either a JSON envelope or human-readable console output. */
sealed interface Presentation {
    /** Renders a [ToolCallResult] for [command] and returns the process exit code. */
    fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream = System.err): Int

    /** Renders a CLI-level failure (usage/parse, routing, bridge error) for [command]; returns [exit] verbatim. */
    fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream = System.err): Int

    /** Renders a successful `take_screenshot` whose image was additionally saved to [savedOut]. */
    fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, out: PrintStream): Int

    /** `--json`: one stable envelope on stdout. */
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

    /** Human-readable output; image payloads are materialized under [imageDir]. */
    class Console(private val imageDir: () -> Path) : Presentation {
        override fun render(result: ToolCallResult, command: String, out: PrintStream, err: PrintStream): Int {
            val sink = if (result.isError) err else out
            for ((index, item) in result.content.withIndex()) {
                when (item) {
                    is ContentItem.Text -> sink.println(item.text)
                    is ContentItem.Image -> renderImage(item, index, sink)
                    is ContentItem.Resource -> {
                        val res = item.resource
                        sink.println("[resource: ${res.uri}${res.mimeType?.let { " ($it)" } ?: ""}]")
                        res.text?.let { sink.println(it) }
                    }
                }
            }
            return if (result.isError) CliExit.TOOL_ERROR else CliExit.OK
        }

        private fun renderImage(item: ContentItem.Image, index: Int, sink: PrintStream) {
            val decoded = try {
                Base64.getDecoder().decode(item.data)
            } catch (e: IllegalArgumentException) {
                System.err.println("devrig: image payload was not valid base64 (${e.message})")
                null
            }
            if (decoded == null) {
                sink.println("[image: ${item.mimeType}, undecodable]")
                return
            }
            val ext = item.mimeType.substringAfterLast('/', "png")
            val file = Files.createTempFile(imageDir(), "image-$index-", ".$ext")
            Files.write(file, decoded)
            sink.println("Saved image: ${file.toAbsolutePath()}")
        }

        override fun renderError(command: String, message: String, exit: Int, out: PrintStream, err: PrintStream): Int {
            err.println(message)
            return exit
        }

        override fun renderScreenshotSaved(result: ToolCallResult, savedOut: String, out: PrintStream): Int {
            val nonImage = result.content.filterNot { it is ContentItem.Image }
            val withNote = ToolCallResult(content = nonImage + ContentItem.Text("Saved --out: $savedOut"))
            return render(withNote, command = "take_screenshot", out = out)
        }
    }
}

/** Maps the `--json` flag onto a concrete [Presentation]; the only place the boolean is branched on. */
fun presentationFor(json: Boolean, imageDir: () -> Path): Presentation =
    if (json) Presentation.Json() else Presentation.Console(imageDir)

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

/** Envelope for a [ToolCallResult]: `data:{content:[...]}`. */
fun ToolCallResult.toEnvelopeJson(command: String): String =
    cliEnvelopeJson(command, isError, contentDataJson())

/** Extracts native serialized `content` for command-specific envelopes. */
fun ToolCallResult.contentDataJson(): JsonObject = buildJsonObject {
    val native = McpJson.encodeToJsonElement(ToolCallResult.serializer(), this@contentDataJson).jsonObject
    put("content", native.getValue("content"))
}
