/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.storage

import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecutionBackendProvenance
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText

@Serializable
data class ExecutionId(val executionId: String)

@Serializable
data class TextMessage(val text: String)

@Serializable
data class ImageMessage(
    val fileName: String,
    val mimeType: String,
)

@Serializable
data class ToolCallMetadata(
    val toolName: String,
    val timestamp: String,
    val projectName: String,
    val taskId: String? = null,
    val arguments: JsonObject,
)

/**
 * Project identity captured at execution time. Replaces the direct
 * `Project` dependency the IntelliJ-side wrapper held — keeps this
 * module IDE-free so :npx-kt and any future host can reuse it.
 */
data class ExecutionProjectInfo(
    val name: String,
    val basePath: String?,
)

/** Backend identity captured at execution time. */
data class ExecutionBackendInfo(
    val kind: Char,
    val name: String,
) {
    init {
        require(kind in 'a'..'z' || kind in 'A'..'Z' || kind in '0'..'9') {
            "Backend kind must be one ASCII letter or digit: $kind"
        }
        require(name.isNotBlank()) { "Backend name must not be blank" }
    }
}

/**
 * File-based storage for execution history.
 * APPEND-ONLY: Files are never deleted, only added.
 *
 * Directory structure:
 * {baseDir}/                           - Base folder (host-supplied; on the IDE
 *                                        side it defaults to ~/.mcp-steroid/runs)
 *   {execution-id}/
 *     backend_name.txt                 - Full backend_name for this execution
 *     project.txt                      - Project name (line 1) and path (line 2)
 *     tool.json                        - Tool name + arguments metadata
 *     script.kts                       - Original code submitted by LLM
 *     params.json                      - Execution parameters
 *     output.jsonl                     - Output messages (append-only)
 *
 * The providers are resolved lazily on each call so hosts can react
 * to runtime configuration changes (e.g. an IDE Registry key swap) without
 * recreating the storage instance.
 */
open class ExecutionStorage(
    private val baseDirProvider: () -> Path,
    private val projectInfoProvider: () -> ExecutionProjectInfo,
    private val backendInfoProvider: () -> ExecutionBackendInfo,
    private val clock: Clock = Clock.systemUTC(),
) {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    val oneLineJson = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val baseDir: Path
        get() = baseDirProvider()

    companion object {
        private const val EXECUTION_SLOT_PRIME = 997L
        private const val MAX_EXECUTION_ID_LENGTH = 240
        private const val MAX_PROJECT_SLUG_LENGTH = 80
        private const val MAX_TASK_SLUG_LENGTH = 120
        private val SAFE_EXECUTION_ID = Regex("[a-zA-Z0-9_-]+")
        private val INVALID_SLUG_CHARACTERS = Regex("[^a-zA-Z0-9_-]+")
        private val UTC_SECONDS_FORMATTER = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss")
            .withZone(ZoneOffset.UTC)
    }

    private val ExecutionId.dir: Path
        get() {
            require(SAFE_EXECUTION_ID.matches(executionId)) { "Invalid execution ID: $executionId" }
            val dir = baseDir.resolve(executionId)
            Files.createDirectories(dir)
            return dir
        }

    fun resolveExecutionDir(executionId: ExecutionId): Path {
        return executionId.dir
    }

    suspend fun appendExecutionEvent(executionId: ExecutionId, text: String) {
        appendExecutionEvent(executionId, TextMessage(text))
    }

    suspend inline fun <reified T> appendExecutionEvent(executionId: ExecutionId, message: T) {
        appendExecutionEventJson(executionId, oneLineJson.encodeToString(message))
    }

    suspend fun writeCodeErrorEvent(executionId: ExecutionId, text: String) {
        writeCodeExecutionData(executionId, "error.txt", text)
    }

    suspend fun appendExecutionEventJson(executionId: ExecutionId, json: String) {
        withContext(Dispatchers.IO) {
            val file = executionId.dir.resolve("output.jsonl")
            require(json.lines().size == 1)
            require(json.startsWith("{") && json.endsWith("}"))

            Files.writeString(
                file,
                json + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    suspend inline fun <reified T> writeCodeExecutionData(executionId: ExecutionId, name: String, data: T) {
        writeCodeExecutionData(executionId, name, json.encodeToString(data))
    }

    suspend fun writeCodeExecutionData(executionId: ExecutionId, name: String, data: String): Path {
        val path = executionId.dir.resolve(name)
        withContext(Dispatchers.IO) {
            path.writeText(data)
        }
        return path
    }

    suspend fun writeBinaryExecutionData(executionId: ExecutionId, name: String, data: ByteArray): Path {
        val path = executionId.dir.resolve(name)
        withContext(Dispatchers.IO) {
            Files.write(path, data)
        }
        return path
    }

    fun resolveExecutionPath(executionId: ExecutionId, name: String): Path {
        require(!name.contains("..") && !name.contains("/") && !name.contains("\\")) {
            "Invalid execution file name: $name"
        }
        return executionId.dir.resolve(name)
    }

    fun findExecutionId(executionId: String) : ExecutionId? {
        if (!SAFE_EXECUTION_ID.matches(executionId)) return null

        // tool.json is the universal sentinel: every writeToolMetadata() call writes it,
        // covering writeNewExecution / writeExecutionFeedback / writeToolCall.
        val path = baseDir.resolve(executionId).resolve("tool.json")
        if (!path.isRegularFile()) return null

        return ExecutionId(executionId)
    }

    suspend fun writeExecutionFeedback(taskId: String, element: FeedbackParams) : ExecutionId {
        val backendInfo = backendInfo(element.executionBackend)
        val executionId = newExecutionId(taskId, backendInfo)
        writeToolMetadata(executionId, "steroid_execute_feedback", json.encodeToJsonElement(element).jsonObject, taskId)
        writeCodeExecutionData(executionId, "feedback.json", element)
        writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        writeExecutionIdentity(executionId, backendInfo)
        return executionId
    }

    private fun newExecutionId(taskId: String, backendInfo: ExecutionBackendInfo): ExecutionId {
        val projectSlug = slug(projectInfoProvider().name, "project", MAX_PROJECT_SLUG_LENGTH)
        val taskSlug = slug(taskId, "task", MAX_TASK_SLUG_LENGTH)
        var attempt = 0L

        Files.createDirectories(baseDir)
        while (true) {
            val now = clock.instant()
            val timestamp = UTC_SECONDS_FORMATTER.format(now)
            val millisecondSlot = Math.floorMod(
                Math.floorMod(now.toEpochMilli(), EXECUTION_SLOT_PRIME) + attempt,
                EXECUTION_SLOT_PRIME,
            )
            val slot = millisecondSlot.toString().padStart(3, '0')
            val id = "eid_$timestamp-$slot-$projectSlug-${backendInfo.kind}-$taskSlug"
            check(id.length <= MAX_EXECUTION_ID_LENGTH) { "Execution ID exceeds $MAX_EXECUTION_ID_LENGTH characters" }
            try {
                Files.createDirectory(baseDir.resolve(id))
                return ExecutionId(id)
            } catch (e: FileAlreadyExistsException) {
                attempt++
            }
        }
    }

    private fun slug(value: String, fallback: String, maxLength: Int): String {
        val normalized = INVALID_SLUG_CHARACTERS.replace(value, "_")
            .trim('_')
            .ifBlank { fallback }
        if (normalized.length <= maxLength) return normalized

        val hashSuffix = "_" + value.hashCode().toUInt().toString(36)
        return normalized
            .take(maxLength - hashSuffix.length)
            .trimEnd('_') + hashSuffix
    }

    private fun backendInfo(provenance: ExecutionBackendProvenance?): ExecutionBackendInfo =
        provenance?.let { ExecutionBackendInfo(kind = it.kind, name = it.name) } ?: backendInfoProvider()

    suspend fun writeNewExecution(exec: ExecCodeParams) : ExecutionId {
        val backendInfo = backendInfo(exec.executionBackend)
        val executionId = newExecutionId(exec.taskId, backendInfo)
        writeToolMetadata(executionId, "steroid_execute_code", json.encodeToJsonElement(exec).jsonObject, exec.taskId)
        writeCodeExecutionData(executionId, "reason.txt", exec.reason)
        writeCodeExecutionData(executionId, "script.kts", exec.code)
        writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        writeExecutionIdentity(executionId, backendInfo)

        return executionId
    }

    suspend fun writeToolCall(
        toolName: String,
        arguments: JsonObject,
        taskId: String? = null,
        executionBackend: ExecutionBackendProvenance? = null,
    ): ExecutionId {
        val backendInfo = backendInfo(executionBackend)
        val executionId = newExecutionId(taskId ?: "tool-$toolName", backendInfo)
        writeToolMetadata(executionId, toolName, arguments, taskId)
        writeCodeExecutionData(executionId, "params.json", arguments)
        writeCodeExecutionData(executionId, "execution-id.txt", executionId.executionId)
        writeExecutionIdentity(executionId, backendInfo)
        return executionId
    }

    private suspend fun writeToolMetadata(
        executionId: ExecutionId,
        toolName: String,
        arguments: JsonObject?,
        taskId: String? = null,
    ) {
        val metadata = ToolCallMetadata(
            toolName = toolName,
            timestamp = clock.instant().toString(),
            projectName = projectInfoProvider().name,
            taskId = taskId,
            arguments = arguments ?: buildJsonObject { }
        )
        writeCodeExecutionData(executionId, "tool.json", metadata)
    }

    private suspend fun writeProjectInfo(executionId: ExecutionId) {
        val info = projectInfoProvider()
        val content = buildString {
            appendLine(info.name)
            info.basePath?.let { appendLine(it) }
        }
        writeCodeExecutionData(executionId, "project.txt", content)
    }

    private suspend fun writeExecutionIdentity(executionId: ExecutionId, backendInfo: ExecutionBackendInfo) {
        writeProjectInfo(executionId)
        writeCodeExecutionData(executionId, "backend_name.txt", backendInfo.name + "\n")
    }

    suspend fun writeWrappedScript(executionId: ExecutionId, code: String) {
        writeCodeExecutionData(executionId, "script-wrapped.kts", code)
    }

    suspend fun createCompilerOutputDir(executionId: ExecutionId): Path {
        return withContext(Dispatchers.IO) {
            val dir = executionId.dir.resolve("compiled")
            Files.createDirectories(dir)
            dir
        }
    }
}
