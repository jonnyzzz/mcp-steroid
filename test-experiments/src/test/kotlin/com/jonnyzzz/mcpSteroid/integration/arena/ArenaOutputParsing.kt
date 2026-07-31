/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

private const val PROJECT_NOT_FOUND_PREFIX = "Project not found: \""

/**
 * Marker the mandatory first execute-code recipe prints its open-project base path after
 * (`Project: <name>, base: <path>`). The producer is [ArenaTestRunner.buildPrompt]'s first-call recipe
 * and the consumer is [AgentTranscript.firstExecutionTargetsProject]; both sides read this constant so
 * the printed format and the parser cannot silently drift.
 */
const val PROJECT_BASE_PATH_MARKER = "base:"

/** One normalized steroid_execute_code result, independent of the agent CLI that emitted it. */
data class ExecuteCodeResult(
    val isError: Boolean,
    val text: String,
)

/** One steroid_execute_code call and its result, when the transcript carried one. */
data class ExecuteCodeCall(
    val callId: String,
    val result: ExecuteCodeResult?,
)

/** Result content retained from one Claude or Codex raw tool event. */
data class AgentToolResult(
    val isError: Boolean?,
    val content: JsonElement?,
) {
    val text: String
        get() = textOf(content)
}

/**
 * One Claude or Codex tool call normalized across their NDJSON schemas.
 *
 * MCP-qualified Claude names such as `mcp__mcp-steroid__steroid_open_project` are reduced to the
 * bare tool name. Native names such as `Bash`, `command_execution`, and names that merely contain
 * double underscores are preserved. [arguments] and [result] retain their JSON structure so E2E
 * assertions can prove what an agent actually submitted and received without inspecting decoded prose.
 */
data class AgentToolCall(
    val callId: String,
    val toolName: String,
    val arguments: JsonObject,
    val result: AgentToolResult?,
)

/** Structural MCP facts extracted from an agent's raw NDJSON transcript. */
data class AgentTranscript(
    val executeCodeCalls: List<ExecuteCodeCall>,
) {
    val usedMcpSteroid: Boolean
        get() = executeCodeCalls.isNotEmpty()

    val successfulMcpExecution: Boolean
        get() = executeCodeCalls.any { it.result?.isError == false }

    /**
     * The first mandatory execute_code must resolve immediately (#251). Later project reloads may
     * invalidate the key; that is accepted only when the agent re-lists projects and produces a later
     * successful result.
     */
    val projectResolutionStatus: ProjectResolutionStatus
        get() {
            if (executeCodeCalls.firstOrNull()?.result?.isProjectResolutionFailure() == true) {
                return ProjectResolutionStatus.INITIAL_FAILURE
            }

            val results = executeCodeCalls.mapNotNull { it.result }
            val lastFailureIndex = results.indexOfLast { it.isProjectResolutionFailure() }
            if (lastFailureIndex < 0) return ProjectResolutionStatus.CLEAN

            return if (results.drop(lastFailureIndex + 1).any { !it.isError }) {
                ProjectResolutionStatus.RECOVERED
            } else {
                ProjectResolutionStatus.UNRECOVERED_FAILURE
            }
        }
}

enum class ProjectResolutionStatus {
    CLEAN,
    RECOVERED,
    INITIAL_FAILURE,
    UNRECOVERED_FAILURE,
}

private data class ResultCandidate(
    val sequence: Int,
    val callId: String,
    val selfToolName: String?,
    val isError: Boolean,
    val text: String,
)

private data class AgentToolCallBuilder(
    val callId: String,
    var toolName: String? = null,
    var arguments: JsonObject? = null,
    var result: AgentToolResult? = null,
)

/**
 * Decode raw Claude Code and Codex NDJSON into ordered, ID-correlated tool calls.
 *
 * Claude's structured `message.content` events and its older root `tool_use` / `tool_result` events
 * are accepted. Codex `item.started` / `item.completed` lifecycle pairs are de-duplicated by item id;
 * MCP/function/tool calls retain their submitted JSON arguments, while native shell executions are
 * represented as `command_execution` with a `command` argument. Malformed and unrelated lines are
 * ignored because real agent log files may also contain non-NDJSON diagnostic text.
 */
fun decodeAgentToolCalls(rawNdjson: String): List<AgentToolCall> {
    val callsById = LinkedHashMap<String, AgentToolCallBuilder>()

    fun recordCall(callId: String?, rawToolName: String?, arguments: JsonObject?) {
        if (callId == null || rawToolName == null) return
        val call = callsById.getOrPut(callId) { AgentToolCallBuilder(callId) }
        call.toolName = normalizeAgentToolName(rawToolName)
        if (arguments != null && (call.arguments == null || arguments.isNotEmpty())) {
            call.arguments = arguments
        }
    }

    fun recordResult(callId: String?, result: AgentToolResult) {
        if (callId == null) return
        callsById.getOrPut(callId) { AgentToolCallBuilder(callId) }.result = result
    }

    fun parseClaudeItem(item: JsonObject) {
        when (item["type"].stringOrNull()) {
            "tool_use" -> recordCall(
                callId = item["id"].stringOrNull(),
                rawToolName = item["name"].stringOrNull(),
                arguments = inputObject(item["input"]),
            )

            "tool_result" -> recordResult(
                callId = item["tool_use_id"].stringOrNull(),
                result = AgentToolResult(
                    // Claude omits is_error on successful results.
                    isError = item["is_error"].boolOrNull() ?: false,
                    content = item["content"],
                ),
            )
        }
    }

    rawNdjson.lineSequence().forEach lines@{ raw ->
        if ('{' !in raw) return@lines
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return@lines

        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.forEach { entry -> (entry as? JsonObject)?.let(::parseClaudeItem) }

        // Older Claude stream-json events put tool_use / tool_result directly at the root.
        if (obj["type"].stringOrNull() == "tool_use" || obj["type"].stringOrNull() == "tool_result") {
            parseClaudeItem(obj)
        }

        val eventType = obj["type"].stringOrNull()
        val item = obj["item"] as? JsonObject ?: return@lines
        val itemType = item["type"].stringOrNull()
        val callId = item["id"].stringOrNull()

        if (itemType == "command_execution") {
            val command = item["command"]
            val arguments = if (command == null) null else JsonObject(mapOf("command" to command))
            recordCall(callId, "command_execution", arguments)
            if (eventType == "item.completed") {
                val content = item["output"] ?: item["aggregated_output"] ?: item["result"] ?: item["error"]
                recordResult(callId, AgentToolResult(codexResultIsError(item, content), content))
            }
            return@lines
        }

        if (itemType !in setOf("mcp_tool_call", "tool_call", "function_call")) return@lines
        val rawToolName = item["name"].stringOrNull()
            ?: (item["function"] as? JsonObject)?.get("name").stringOrNull()
            ?: item["tool"].stringOrNull()
        recordCall(callId, rawToolName, codexArguments(item))

        if (eventType == "item.completed") {
            val rawResult = item["result"] ?: item["output"] ?: item["error"]
            val content = (rawResult as? JsonObject)?.get("content") ?: rawResult
            recordResult(callId, AgentToolResult(codexResultIsError(item, rawResult), content))
        }
    }

    return callsById.values.mapNotNull { call ->
        val toolName = call.toolName ?: return@mapNotNull null
        AgentToolCall(
            callId = call.callId,
            toolName = toolName,
            arguments = call.arguments ?: JsonObject(emptyMap()),
            result = call.result,
        )
    }
}

/** Extract the user-visible final response without mistaking tool output or progress prose for it. */
fun decodeAgentFinalResponse(rawNdjson: String): String? {
    var claudeResult: String? = null
    var claudeAssistantText: String? = null
    var codexAgentMessage: String? = null

    rawNdjson.lineSequence().forEach { raw ->
        if ('{' !in raw) return@forEach
        val obj = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return@forEach

        if (obj["type"].stringOrNull() == "result" && obj["subtype"].stringOrNull() == "success") {
            obj["result"].stringOrNull()?.takeIf { it.isNotBlank() }?.let { claudeResult = it }
        }

        if (obj["type"].stringOrNull() == "assistant") {
            val message = obj["message"] as? JsonObject
            val text = (message?.get("content") as? JsonArray)
                ?.mapNotNull { entry ->
                    val item = entry as? JsonObject ?: return@mapNotNull null
                    if (item["type"].stringOrNull() == "text") item["text"].stringOrNull() else null
                }
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
            text?.takeIf { it.isNotBlank() }?.let { claudeAssistantText = it }
        }

        val item = obj["item"] as? JsonObject
        if (obj["type"].stringOrNull() == "item.completed" && item?.get("type").stringOrNull() == "agent_message") {
            item?.get("text").stringOrNull()?.takeIf { it.isNotBlank() }?.let { codexAgentMessage = it }
        }
    }

    return claudeResult ?: claudeAssistantText ?: codexAgentMessage
}

/**
 * Normalize Claude Code, Codex, and Gemini raw NDJSON into execute-code calls/results.
 * Tool-result attribution is by call id, so an article body returned by steroid_fetch_resource cannot
 * masquerade as an execute-code failure merely because it quotes "Project not found".
 */
fun decodeAgentTranscript(rawNdjson: String): AgentTranscript {
    val idToToolName = LinkedHashMap<String, String>()
    val executeCodeCallIds = mutableListOf<String>()
    val resultCandidates = mutableListOf<ResultCandidate>()
    var sequence = 0

    fun recordCall(callId: String?, toolName: String?) {
        if (callId == null || toolName == null) return
        idToToolName[callId] = toolName
        if (toolName.endsWith("steroid_execute_code") && callId !in executeCodeCallIds) {
            executeCodeCallIds += callId
        }
    }

    fun recordResult(callId: String?, selfToolName: String?, isError: Boolean?, text: String) {
        if (callId == null || isError == null) return
        resultCandidates += ResultCandidate(sequence++, callId, selfToolName, isError, text)
    }

    rawNdjson.lineSequence().forEach lines@{ raw ->
        if ('{' !in raw) return@lines
        val obj = (runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject)
            ?: return@lines

        // Claude Code: message.content[*] tool_use / tool_result objects.
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)
            ?.forEach entries@{ entry ->
                val item = entry as? JsonObject ?: return@entries
                when (item["type"].stringOrNull()) {
                    "tool_use" -> recordCall(
                        callId = item["id"].stringOrNull(),
                        toolName = item["name"].stringOrNull(),
                    )

                    "tool_result" -> recordResult(
                        callId = item["tool_use_id"].stringOrNull(),
                        selfToolName = null,
                        // Claude omits is_error on successful tool results; only failures carry true.
                        isError = item["is_error"].boolOrNull() ?: false,
                        text = textOf(item["content"]),
                    )
                }
            }

        // Codex: item.started / item.completed containing an mcp_tool_call.
        (obj["item"] as? JsonObject)?.let { item ->
            if (item["type"].stringOrNull() == "mcp_tool_call") {
                val callId = item["id"].stringOrNull()
                val toolName = item["tool"].stringOrNull() ?: item["name"].stringOrNull()
                recordCall(callId, toolName)

                if (obj["type"].stringOrNull() == "item.completed") {
                    val status = item["status"].stringOrNull()?.lowercase()
                    val hasErrorObject = item["error"] != null && item["error"] !is JsonNull
                    val isError = when {
                        hasErrorObject || status == "failed" || status == "error" -> true
                        status == "completed" || status == "success" -> false
                        else -> null
                    }
                    val text = textOf((item["result"] as? JsonObject)?.get("content"))
                        .ifBlank { textOf(item["error"]) }
                    recordResult(callId, toolName, isError, text)
                }
            }
        }

        // Gemini: root objects using the real stream-json fields tool_id/status/output.
        when (obj["type"].stringOrNull()) {
            "tool_use" -> recordCall(
                callId = obj["tool_id"].stringOrNull(),
                toolName = obj["tool_name"].stringOrNull(),
            )

            "tool_result" -> {
                val status = obj["status"].stringOrNull()?.lowercase()
                val isError = when (status) {
                    "error", "failed" -> true
                    "success", "completed" -> false
                    else -> null
                }
                recordResult(
                    callId = obj["tool_id"].stringOrNull(),
                    selfToolName = obj["tool_name"].stringOrNull(),
                    isError = isError,
                    text = textOf(obj["output"]),
                )
            }
        }
    }

    val resultsByCallId = LinkedHashMap<String, ExecuteCodeResult>()
    resultCandidates
        .sortedBy { it.sequence }
        .forEach { candidate ->
            val toolName = candidate.selfToolName ?: idToToolName[candidate.callId]
            if (toolName?.endsWith("steroid_execute_code") != true) return@forEach
            resultsByCallId.putIfAbsent(candidate.callId, ExecuteCodeResult(candidate.isError, candidate.text))
        }

    return AgentTranscript(
        executeCodeCalls = executeCodeCallIds.map { callId ->
            ExecuteCodeCall(callId, resultsByCallId[callId])
        },
    )
}

/**
 * The prompt's mandatory first execute-code recipe prints `Project: ..., base: ...`. Require that first
 * successful result to name the arena deployment path so a different but valid open project cannot make
 * the MCP arm look healthy.
 */
fun AgentTranscript.firstExecutionTargetsProject(expectedProjectDir: String): Boolean {
    val firstResult = executeCodeCalls.firstOrNull()?.result ?: return false
    if (firstResult.isError) return false

    val expectedPath = expectedProjectDir.trimEnd('/')
    return firstResult.text.lineSequence().any { line ->
        line.substringAfter(PROJECT_BASE_PATH_MARKER, missingDelimiterValue = "").trim().trimEnd('/') == expectedPath
    }
}

private fun ExecuteCodeResult.isProjectResolutionFailure(): Boolean =
    isError && stripErrorPrefixes(text).startsWith(PROJECT_NOT_FOUND_PREFIX)

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

private fun inputObject(element: JsonElement?): JsonObject? = when (element) {
    is JsonObject -> element
    is JsonPrimitive -> element.contentOrNull?.let { encoded ->
        runCatching { Json.parseToJsonElement(encoded) as? JsonObject }.getOrNull()
    }

    else -> null
}

private fun codexArguments(item: JsonObject): JsonObject? {
    val function = item["function"] as? JsonObject
    return inputObject(item["input"])
        ?: inputObject(item["arguments"])
        ?: inputObject(function?.get("arguments"))
}

private fun codexResultIsError(item: JsonObject, result: JsonElement?): Boolean? {
    val status = item["status"].stringOrNull()?.lowercase()
    val error = item["error"]
    val hasError = error != null && error !is JsonNull
    val exitCode = (item["exit_code"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    return when {
        hasError || status == "failed" || status == "error" || (exitCode != null && exitCode != 0) -> true
        status == "completed" || status == "success" || exitCode == 0 || result != null -> false
        else -> null
    }
}

private fun normalizeAgentToolName(rawToolName: String): String =
    if (rawToolName.startsWith("mcp__")) rawToolName.substringAfterLast("__") else rawToolName

private fun textOf(element: JsonElement?): String = when (element) {
    is JsonPrimitive -> element.contentOrNull.orEmpty()
    is JsonArray -> element.joinToString("\n") { textOf(it) }
    is JsonObject -> sequenceOf("text", "output", "message", "content")
        .map { key -> textOf(element[key]) }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    else -> ""
}

private fun stripErrorPrefixes(text: String): String {
    var value = text.trimStart()
    while (value.length >= 6 && value.substring(0, 6).equals("error:", ignoreCase = true)) {
        value = value.substring(6).trimStart()
    }
    return value
}
