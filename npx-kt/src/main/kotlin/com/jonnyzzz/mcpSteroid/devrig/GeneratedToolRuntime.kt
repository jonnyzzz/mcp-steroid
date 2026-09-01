/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRouteNotFoundException
import com.jonnyzzz.mcpSteroid.devrig.server.StubMcpSteroidTools
import com.jonnyzzz.mcpSteroid.devrig.server.callToolViaSpec
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.isEffectivelyBlank
import com.jonnyzzz.mcpSteroid.mcp.trimLeadingBoms
import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The runtime half of the schema-driven CLI: what happens after Clikt has parsed a `devrig <tool>`
 * invocation into an inert [GeneratedToolInvocation]. Parsing and running are separate lifecycle phases —
 * the parse phase touches no handler, service or backend — and this file is the second phase for EVERY
 * generated command. There is one dispatch path and one error-mapping pipeline; nothing here branches on
 * the tool's name, because a per-tool arm is the duplication issue #284 exists to remove.
 */

/**
 * A CLI input the runtime could not turn into a tool argument — an unreadable file source, an empty
 * standard input. [exit] is the frozen [CliExit] code the failure reports, carried on the exception so the
 * one pipeline below renders it without a second decision point.
 */
class CliInputException(message: String, val exit: Int) : RuntimeException(message)

/**
 * Typed data produced by one schema-generated Clikt command and consumed by the shared tool runtime.
 * This is deliberately not a second command hierarchy: Clikt owns routing and this value only carries
 * the already-parsed tool call across the parse/runtime boundary.
 */
data class GeneratedToolInvocation(
    val toolName: String,
    val commandName: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val fileSources: Map<String, String> = emptyMap(),
    val extraOptions: Map<String, Boolean> = emptyMap(),
    val out: Path? = null,
    val debug: Boolean = false,
    val json: Boolean = false,
)

/**
 * Runs one generated tool command: resolve the live spec, fill in the inputs the parse phase deliberately
 * left as paths, call the tool, render once.
 *
 * The frozen exit-code table is applied in exactly one place — the `try/catch` below — so every generated
 * command reports the same failure the same way:
 *
 * | failure | exit |
 * |---|---|
 * | an unreadable or absent file source | [CliExit.IO_ERROR] 74 |
 * | a malformed path, an empty standard input, an argument the CLI or the TOOL rejects, an unknown `project_name` | [CliExit.USAGE] 64 |
 * | unusable data from the backend (an undecodable payload, a malformed response) | [CliExit.DATA_ERROR] 65 |
 * | an [IOException] reaching the tool — no IDE running, a refused connection, a timeout | [CliExit.UNAVAILABLE] 69 |
 * | a declared `wait` extra option's poll timed out before the project reported ready | [CliExit.UNAVAILABLE] 69 |
 * | the tool answered with `isError=true` | [CliExit.TOOL_ERROR] 1 |
 *
 * Any OTHER throwable — an internal devrig or handler fault such as an NPE or a broken invariant — is
 * deliberately NOT in that table and is not caught here: it is not an unreachable IDE, and mapping it to
 * UNAVAILABLE once both blamed the caller's IDE for a devrig bug and discarded the stack trace. Such a
 * throwable propagates to [runCliWithLastResortHandling] in `Main.kt`, which prints the trace (even under
 * `--json`, where stdout stays a clean envelope) and returns the last-resort code.
 *
 * `--out` is absent from that table on purpose: a `--out` failure happens while RENDERING a result the
 * tool already returned, so it is classified by [renderWithOut] instead — see the comment above the final
 * `return`.
 *
 * A parse-time usage failure never arrives here: [parseDevrigCommand] turns it into an informational
 * invocation that reports exit 64 before this runtime is selected. Clikt must not reach the dispatch
 * layer, so this pipeline neither can nor should catch a `UsageError`.
 *
 * [CancellationException] is rethrown rather than rendered: a cancelled devrig is shutting down, not
 * failing, and swallowing it would stop structured concurrency from unwinding the surrounding scope.
 *
 * [tools] is a parameter so a test can inject handler doubles and drive the real spec `call()` path without
 * a live IDE; production always passes the [StubMcpSteroidTools] wiring the `devrig mcp` stdio proxy uses.
 * Human mode streams progress to stderr. `--json` suppresses that live stream because agent shell tools
 * commonly merge stderr into their command result; the result envelope remains the single parseable value.
 */
fun DevrigServices.runGeneratedToolCommand(
    command: GeneratedToolInvocation,
    tools: McpSteroidTools = StubMcpSteroidTools(this),
): Int {
    val spec = liveToolSpec(command.toolName, tools)
    val presentation = presentationFor(command.json, spec.cli.outputStyle, homePaths::tmpDir)
    val preparedOut = try {
        // A usage failure must win before --out preflight creates a parent directory or probe file.
        // This also keeps unsupported orchestration flags ahead of the backend call below.
        command.requireNoUnhandledExtraOption()
        preflightOutTarget(command.out)
    } catch (e: IllegalArgumentException) {
        return presentation.renderError(
            command.commandName,
            "devrig ${command.commandName}: ${e.message}",
            CliExit.USAGE,
            mcpStdout,
        )
    } catch (e: IOException) {
        return presentation.renderError(
            command.commandName,
            "failed to prepare --out at ${command.out}: ${e.message}",
            CliExit.IO_ERROR,
            mcpStdout,
        )
    }
    val result = try {
        val arguments = command.argumentsWithFileSources(spec, mcpStdin)
        val progress = if (command.json) NoOpProgressReporter else stderrProgressReporter(spec.name)
        runBlocking(Dispatchers.IO) {
            val toolResult = callToolViaSpec(spec, arguments, progress)
            val completedResult = if (!toolResult.isError && command.extraOptions[WAIT_EXTRA_OPTION_NAME] == true) {
                awaitWaitOption(command, tools)
            } else toolResult
            completedResult
        }
    } catch (e: CliInputException) {
        return presentation.renderError(command.commandName, e.message.orEmpty(), e.exit, mcpStdout)
    } catch (e: ProjectNotReadyException) {
        // Thrown by awaitWaitOption above when a declared `wait` extra option's poll deadline passed
        // before `steroid_list_projects` reported the project route — the same "no usable IDE" story an
        // IOException tells below, just discovered after open_project's own call already succeeded.
        return presentation.renderError(command.commandName, e.message.orEmpty(), CliExit.UNAVAILABLE, mcpStdout)
    } catch (e: ProjectRouteNotFoundException) {
        // Reworded rather than appended to: the exception's own message tells an MCP client to call
        // `steroid_list_projects`, which a CLI user cannot do, and printing both instructions for the one
        // action is how this read before it was dogfooded.
        return presentation.renderError(
            command.commandName,
            "project_name '${e.projectName}' is not open — run `devrig list_projects` to see the valid " +
                "project_name keys",
            CliExit.USAGE, mcpStdout,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: SerializationException) {
        // Caught ahead of IllegalArgumentException, which it extends: a malformed backend payload is the
        // backend's fault, and reporting it as the caller's usage mistake would send them fixing a flag.
        return presentation.renderError(
            command.commandName,
            "devrig ${command.commandName} could not read the backend's response: ${e.message}",
            CliExit.DATA_ERROR, mcpStdout,
        )
    } catch (e: ToolCallErrorException) {
        // A tool-side rejection of the caller's arguments: `McpSchema`'s enum parser for an unknown value,
        // or its `required()` parser for a value the command line could not enforce itself. It extends
        // RuntimeException and NOT IllegalArgumentException, so it needs its own arm — without one it fell
        // into the catch-all below and a perfectly reachable IDE was reported as unreachable, at exit 69.
        // `McpToolRegistry.callTool` catches the same type for the same reason; the CLI bypasses the
        // registry precisely so typed exceptions can be classified, so it must classify this one too.
        //
        // The message carries the schema's own wording and asserts nothing beyond it — this arm knows the
        // tool refused the arguments and knows no reason why, so pointing at the command's own help is the
        // most it may say.
        return presentation.renderError(
            command.commandName,
            "devrig ${command.commandName}: ${e.message} — run `devrig ${command.commandName} --help` " +
                "for the flags this command accepts",
            CliExit.USAGE, mcpStdout,
        )
    } catch (e: IllegalArgumentException) {
        return presentation.renderError(
            command.commandName, "devrig ${command.commandName}: ${e.message}", CliExit.USAGE, mcpStdout,
        )
    } catch (e: IOException) {
        // The one failure the frozen table maps to UNAVAILABLE: no reachable backend. A refused bridge
        // connection, a dropped socket and a read timeout all surface as an IOException and all mean the
        // same thing to the caller — no IDE answered. This arm deliberately catches IOException and NOT
        // Exception: a genuine fault inside devrig or a handler (an NPE, a broken invariant) is not an
        // unreachable IDE. Mapping it to 69 here used to blame the caller's IDE for a devrig bug and hide
        // the trace; it now propagates to `runCliWithLastResortHandling`, which prints the trace and
        // returns the last-resort code (see the KDoc above).
        return presentation.renderError(
            command.commandName,
            "devrig ${command.commandName} did not complete: no IDE backend is reachable " +
                "(${e.javaClass.simpleName}: ${e.message}) — check `devrig list_projects`.",
            CliExit.UNAVAILABLE, mcpStdout,
        )
    }
    // Rendering deliberately sits OUTSIDE the pipeline above, which is a decision and not an oversight.
    // The table classifies failures of reaching and calling the TOOL; a failure to render carries none of
    // those causes, and feeding it in would label it with one it cannot have (a rendering
    // IllegalArgumentException is not the caller's usage mistake). The render layer already owns its own
    // failures and maps them itself: an undecodable image or a failed `--out` write become DATA_ERROR /
    // IO_ERROR inside [renderWithOut] and [Presentation], which is where the `--out` contract lives.
    // Console rendering also writes incrementally, item by item, so a failure part-way has already emitted
    // output — re-rendering it through the table would append a second, contradictory report to the first.
    // What is left is exotic (an IOError from path resolution, say); it reaches Main.kt's last-resort
    // handler, which is the right destination for an internal fault because it prints the stack trace an
    // exit code cannot carry. stdout stays clean either way: under `--json` the envelope string is built
    // before anything is printed.
    return renderWithOut(presentation, result, command.commandName, preparedOut, mcpStdout)
}

/**
 * The one live [CliToolSpec] named [toolName], from the canonical `devrigToolSpecs()` list that also feeds
 * stdio registration, CLI registration, and generated help. Live means handler-bound: the spec the parse
 * phase used is handler-free by construction (see [devrigCliTools]), so falling back to it here would parse
 * the arguments and then fail on the first handler lookup. A missing or duplicated match is an invariant
 * violation — a generated command routed to a tool the runtime list does not uniquely provide — never a
 * fallback and never a cast to a concrete tool class.
 */
fun liveToolSpec(toolName: String, tools: McpSteroidTools): CliToolSpec {
    val matches = tools.devrigToolSpecs().filter { it.name == toolName }
    return matches.singleOrNull() ?: error(
        "the generated command for '$toolName' has no runtime tool spec: devrigToolSpecs() provides " +
            "${matches.size} specs with that name, expected exactly one"
    )
}

/** Identity of the generic `--wait` extra option — [com.jonnyzzz.mcpSteroid.mcp.CliExtraOption.name], not
 * a CLI spelling. Declared today only by `open_project` (`OpenProjectTool.kt`), but [awaitWaitOption] is
 * keyed off this NAME alone, never a per-tool `when`, so any future tool that declares another
 * `CliExtraOption("wait", ...)` over a `project_path` argument gets the same poll for free. */
private const val WAIT_EXTRA_OPTION_NAME: String = "wait"

/** How long a declared `wait` extra option polls `steroid_list_projects` before giving up. */
private const val WAIT_TIMEOUT_MS: Long = 300_000

/** How long a declared `wait` extra option sleeps between polls of `steroid_list_projects`. */
private const val WAIT_INTERVAL_MS: Long = 1_000

/**
 * Fails when the invocation SET a [com.jonnyzzz.mcpSteroid.mcp.CliExtraOption] no runtime acts on.
 *
 * An extra option is by definition orchestration the CLI performs around the call — `open_project --wait`
 * polling the IDE after the tool returns — so it reaches no tool and there is nothing to forward it to.
 * [WAIT_EXTRA_OPTION_NAME] is the only name any runtime behavior is keyed off today; every other name that
 * arrives set to `true` in [GeneratedToolInvocation.extraOptions] is, by construction, a flag Clikt accepted
 * and this runtime silently ignored — the exact "accept then ignore" outcome the design this file follows
 * exists to rule out. The check reads [GeneratedToolInvocation.extraOptions] alone, never a per-tool `when`
 * or a lookup into the tool's own [com.jonnyzzz.mcpSteroid.mcp.CliCommandSpec.extraOptions], so a future
 * tool that declares a second extra option needs no edit here: either the runtime grows a name-keyed
 * handler for it (like [awaitWaitOption]) before it ships, or this guard rejects it the first time it is
 * set — there is no silent third option.
 */
private fun GeneratedToolInvocation.requireNoUnhandledExtraOption() {
    val unhandled = extraOptions.filterKeys { it != WAIT_EXTRA_OPTION_NAME }.filterValues { it }
    // No "devrig <command>:" prefix here — the pipeline's IllegalArgumentException arm adds it, and saying
    // it twice is what the printed message actually looked like before this comment existed.
    require(unhandled.isEmpty()) {
        "${unhandled.keys.joinToString(", ")} is accepted by the command line but no runtime acts on it " +
            "yet — drop it and the command runs"
    }
}

/**
 * Thrown by [awaitWaitOption] when its poll deadline passes before `steroid_list_projects` reports the
 * project route. Caught in [runGeneratedToolCommand]'s own pipeline, alongside every other failure that
 * pipeline classifies, and mapped to [CliExit.UNAVAILABLE] there — a project that never finishes opening
 * is the same "no usable IDE" story as an unreachable backend, just discovered after the call.
 */
private class ProjectNotReadyException(projectPath: String, timeoutMs: Long) : RuntimeException(
    "project '$projectPath' was not routed by list_projects within ${timeoutMs / 1000}s"
)

/** Structured success returned by `open_project --wait`, including the routing values needed next. */
@Serializable
private data class AwaitedProjectResult(
    @SerialName("project_name") val projectName: String,
    @SerialName("backend_name") val backendName: String?,
    val path: String,
)

/**
 * The orchestration a declared `wait` extra option means: after [command]'s own tool call has already
 * returned, poll [tools]' `steroid_list_projects` until [command]'s `project_path` appears as an addressable
 * route, or throw [ProjectNotReadyException] once [WAIT_TIMEOUT_MS] passes. Keyed off
 * [WAIT_EXTRA_OPTION_NAME] alone by the one caller in [runGeneratedToolCommand] — this function itself
 * assumes nothing about WHICH tool called it beyond "it declared a `project_path` argument", which is
 * `wait`'s whole contract: it orchestrates opening a project, so there is always a project path to poll
 * for.
 *
 * [rawProjectPath] is normalized with [Path.toRealPath] before polling: `open_project` resolves its own
 * `project_path` input the same way (`OpenProjectTool.kt`) before opening it, and `steroid_list_projects`
 * reports that resolved path, not the caller's original string — a relative path or an unresolved symlink
 * would otherwise never match. Resolution can only fail here if the directory vanished between
 * `open_project` validating it and this call, which is exotic enough to fall out through the ordinary
 * [IOException] arm below rather than needing its own.
 */
private suspend fun awaitWaitOption(
    command: GeneratedToolInvocation,
    tools: McpSteroidTools,
): ToolCallResult {
    val rawProjectPath = command.arguments["project_path"]?.jsonPrimitive?.contentOrNull
        ?: error(
            "'$WAIT_EXTRA_OPTION_NAME' extra option requires a 'project_path' argument, which " +
                "'${command.toolName}' does not declare"
        )
    val projectPath = withContext(Dispatchers.IO) { Path.of(rawProjectPath).toRealPath().toString() }
    val backendName = command.arguments["backend_name"]?.jsonPrimitive?.contentOrNull
        ?.trim()?.takeIf { it.isNotEmpty() }
    val listProjectsSpec = liveToolSpec("steroid_list_projects", tools)
    // Created ONCE, outside the poll lambda: `stderrProgressReporter` prints "Tool call started: devrig
    // list_projects" on its FIRST `report()` call, and a wait that polls for minutes must not repeat that
    // line on every iteration.
    val listProjectsProgress = if (command.json) NoOpProgressReporter else stderrProgressReporter(listProjectsSpec.name)
    val route = awaitProjectReady(
        pollListProjects = {
            callToolViaSpec(listProjectsSpec, JsonObject(emptyMap()), listProjectsProgress).listProjectsResponse()
        },
        projectPath = projectPath,
        backendName = backendName,
        timeoutMs = WAIT_TIMEOUT_MS,
        intervalMs = WAIT_INTERVAL_MS,
        now = System::currentTimeMillis,
        sleep = ::delay,
    )
        ?: throw ProjectNotReadyException(projectPath, WAIT_TIMEOUT_MS)
    val result = AwaitedProjectResult(route.projectName, route.backendName, route.path)
    return ToolCallResult(content = listOf(ContentItem.Text(McpJson.encodeToString(result))))
}

/**
 * A `steroid_list_projects` call result, decoded to the shared response model. A malformed or absent text
 * payload is the backend's fault, not the caller's, so it is reported with [error] — an
 * invariant violation propagating to `runCliWithLastResortHandling`, not a [CliExit] this pipeline knows
 * how to name; a genuinely undecodable JSON body instead throws [SerializationException] here, which the
 * pipeline's own arm already maps to [CliExit.DATA_ERROR].
 */
private fun ToolCallResult.listProjectsResponse(): ListProjectsResponse {
    val text = content.filterIsInstance<ContentItem.Text>().firstOrNull()?.text
        ?: error("steroid_list_projects returned no text content to parse")
    return McpJson.decodeFromString(ListProjectsResponse.serializer(), text)
}

/**
 * This command's [GeneratedToolInvocation.arguments] with every deferred file source resolved: each parameter
 * named in [GeneratedToolInvocation.fileSources] gains the content of the recorded path — or of standard input
 * when the path is `-`. The parse phase records the path and nothing more, because opening a file and
 * reading standard input are runtime effects a command line parser must not have; this is where that debt is
 * paid, once, for whatever tool declared a [com.jonnyzzz.mcpSteroid.mcp.CliFileSource].
 *
 * The object is REBUILT in [spec]'s own parameter order rather than appended to. A substituted parameter is
 * absent from the parsed arguments by definition (the two spellings are mutually exclusive), so appending it
 * would move it to the end and silently reorder the tool call — `execute_code`'s `code` would follow `reason`
 * instead of `project_name`.
 *
 * Iterating the declaration is COMPLETE, not best-effort: every key in either map is a declared parameter's
 * own name, because `SchemaCliBinding` writes nothing else into them, and `ToolSchema.asCliParams()` returns
 * every declared parameter — `cliHidden` ones included. The `check` below is what keeps that reasoning
 * honest, since the failure it guards against would otherwise be a silently dropped argument.
 */
fun GeneratedToolInvocation.argumentsWithFileSources(spec: CliToolSpec, stdin: InputStream): JsonObject {
    if (fileSources.isEmpty()) return arguments
    val resolved = fileSources.mapValues { (name, path) ->
        JsonPrimitive(readCliFileSource(name, path, stdin, announceStdin = !json))
    }
    val ordered = LinkedHashMap<String, JsonElement>()
    for (param in spec.schema.asCliParams()) {
        (resolved[param.name] ?: arguments[param.name])?.let { ordered[param.name] = it }
    }
    val undeclared = (arguments.keys + resolved.keys) - ordered.keys
    check(undeclared.isEmpty()) {
        "${spec.name}: $undeclared reached the tool call without being declared by the schema, so rebuilding " +
            "the arguments in declaration order would have dropped them"
    }
    return JsonObject(ordered)
}

/** The path a file source uses to mean "read standard input", the usual CLI convention. */
private const val CLI_STDIN_PATH: String = "-"

/**
 * The most a file source will read from EITHER a file or standard input, in bytes, before
 * [decodeCliSourceBytes] rejects it — 10 MB. Named so both readers enforce the identical limit and so a
 * rejection message can quote the same number this constant defines, rather than a number hand-copied into
 * a string.
 */
const val CLI_FILE_SOURCE_MAX_BYTES: Long = 10L * 1024 * 1024

/**
 * The value behind one file source: standard input when [path] is [CLI_STDIN_PATH], else the content of the
 * file at [path]. [paramName] is the schema parameter being filled, named in every failure so the caller
 * knows which flag to fix when a tool declares more than one source.
 *
 * A malformed path string is the caller's typo ([CliExit.USAGE]); an absent, non-regular or unreadable file
 * is a filesystem failure ([CliExit.IO_ERROR]) — the distinction a caller acts on is "fix the command line"
 * versus "fix the disk", not whether `Files` threw. Malformed UTF-8 ([CliExit.IO_ERROR]) and exceeding
 * [CLI_FILE_SOURCE_MAX_BYTES] ([CliExit.DATA_ERROR]) are the same two content faults [readCliStdin] rejects,
 * via the same [decodeCliSourceBytes], so the two sources behave identically instead of a file throwing on
 * malformed bytes while stdin silently substituted them.
 */
private fun readCliFileSource(
    paramName: String,
    path: String,
    stdin: InputStream,
    announceStdin: Boolean,
): String {
    if (path == CLI_STDIN_PATH) return readCliStdin(paramName, stdin, announceStdin)
    val file = try {
        Path.of(path)
    } catch (e: InvalidPathException) {
        throw CliInputException(
            "'$paramName' was given the path '$path', which is not a valid path: ${e.reason}", CliExit.USAGE,
        )
    }
    if (!Files.isRegularFile(file)) {
        throw CliInputException(
            "'$paramName' was to be read from '$file', which is not an existing regular file", CliExit.IO_ERROR,
        )
    }
    val bytes = try {
        Files.newInputStream(file).use { it.readNBytes((CLI_FILE_SOURCE_MAX_BYTES + 1).toInt()) }
    } catch (e: IOException) {
        throw CliInputException("'$paramName' could not be read from '$file': ${e.message}", CliExit.IO_ERROR)
    }
    // Same contract as the stdin branch below: an empty value must fail here with a message that names
    // the cause, instead of being forwarded for the tool to answer with its own confusing complaint.
    val text = decodeCliSourceBytes(paramName, bytes)
    // #460: empty, whitespace-only, or BOM-only content is one fault class — the same blank payload
    // the parse layer refuses inline must not slip through the file spelling.
    if (text.isEffectivelyBlank()) throw CliInputException(
        "'$paramName' was to be read from '$file', which is blank; put the value in the file or pass it directly",
        CliExit.USAGE,
    )
    return text
}

/**
 * Standard input, read to the end, as the value of [paramName].
 *
 * Two things keep a non-interactive caller — the primary one, an agent invoking devrig — able to tell what
 * happened, without guessing at a timeout that would cut off a legitimately slow producer:
 *  - the read is ANNOUNCED on stderr first, so a devrig that then sits waiting is visibly waiting on stdin
 *    rather than on the IDE. Printed before the read precisely because the read may not return;
 *  - reaching end of input immediately — nothing was piped — is a [CliExit.USAGE] failure naming both the
 *    parameter and the two ways to supply it, instead of handing the tool an empty value and letting it
 *    answer with its own confusing complaint about empty input.
 *
 * The read stops at [CLI_FILE_SOURCE_MAX_BYTES] + 1 bytes rather than draining an unbounded pipe, and the
 * result is decoded by [decodeCliSourceBytes] — the same strict decoder [readCliFileSource] uses — so
 * malformed UTF-8 is rejected here too, instead of `String.decodeToString()`'s silent U+FFFD substitution.
 */
private fun readCliStdin(paramName: String, stdin: InputStream, announce: Boolean): String {
    if (announce) {
    System.err.println(
        "devrig: reading '$paramName' from standard input ('$CLI_STDIN_PATH' given); " +
            "pipe the value in, or close standard input (Ctrl-D) to finish"
    )
    }
    val bytes = try {
        stdin.readNBytes((CLI_FILE_SOURCE_MAX_BYTES + 1).toInt())
    } catch (e: IOException) {
        throw CliInputException(
            "'$paramName' could not be read from standard input: ${e.message}", CliExit.IO_ERROR,
        )
    }
    if (bytes.isEmpty()) throw CliInputException(
        "'$paramName' was to be read from standard input ('$CLI_STDIN_PATH' given) but nothing was piped in; " +
            "pipe the value or pass a file path instead",
        CliExit.USAGE,
    )
    val text = decodeCliSourceBytes(paramName, bytes)
    // #460: same rule as the file branch above — whitespace- or BOM-only stdin is a blank payload.
    if (text.isEffectivelyBlank()) throw CliInputException(
        "'$paramName' was to be read from standard input ('$CLI_STDIN_PATH' given) but the piped input " +
            "is blank; pipe the value or pass a file path instead",
        CliExit.USAGE,
    )
    return text
}

/**
 * [bytes] as strict UTF-8 text — the shared content-validation [readCliFileSource] and [readCliStdin] both
 * apply, so a file source and standard input reject the same two content faults the same way:
 *  - more than [CLI_FILE_SOURCE_MAX_BYTES] arrived — [CliExit.DATA_ERROR], because nothing failed to be
 *    read: the caller handed devrig more data than it accepts (both readers stop at the cap plus one byte,
 *    so exceeding it is detected without ever buffering an unbounded source in full);
 *  - the bytes are not valid UTF-8 — rejected via [CodingErrorAction.REPORT] rather than substituted with
 *    U+FFFD, so a caller sees the actual encoding mistake instead of silently corrupted content reaching
 *    the tool. Reported as [CliExit.IO_ERROR], keeping the contract the file branch has answered since
 *    `Files.readString` did the decoding: a [CharacterCodingException] IS an [IOException], and the two
 *    sources must agree — a `devrig` caller that pipes the same bytes it would have passed as a file must
 *    not get a different code depending on which spelling it chose.
 */
private fun decodeCliSourceBytes(paramName: String, bytes: ByteArray): String {
    if (bytes.size.toLong() > CLI_FILE_SOURCE_MAX_BYTES) {
        throw CliInputException(
            "'$paramName' exceeds the ${CLI_FILE_SOURCE_MAX_BYTES / (1024 * 1024)} MB limit", CliExit.DATA_ERROR,
        )
    }
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw CliInputException("'$paramName' is not valid UTF-8 text: ${e.message}", CliExit.IO_ERROR)
    }
    // A UTF-8 BOM is an encoding artifact (Notepad, PowerShell redirects), not content: strip every
    // leading one (files re-saved through two BOM-adding tools stack them) so a BOM-only file registers
    // as blank (#460 — U+FEFF is NOT whitespace, so isBlank alone misses it) and a BOM-prefixed script
    // reaches the compiler clean.
    return text.trimLeadingBoms()
}
