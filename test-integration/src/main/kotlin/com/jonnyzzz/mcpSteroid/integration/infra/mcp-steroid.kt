/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** Build system type for project setup. Must be specified explicitly per test. */
enum class BuildSystem {
    MAVEN,
    GRADLE,
    NONE,
}

/**
 * How `steroid_execute_code` should treat IDE modality around the script — the client-side mirror of
 * the server's `modal` wire protocol values. The test infra is an MCP client, so it owns its own copy
 * of the protocol value instead of depending on the server module.
 *
 * - [SMART_NON_MODAL]: close leftover modals, require non-modal IDE, commit+save+VFS, wait for smart
 *   mode, monitor for modals during the run (default — for PSI / code-management flows).
 * - [NON_MODAL]: require non-modal at start only; no sweep / sync / smart-wait / during-run monitor.
 * - [UNLEASHED]: no checks at all; runs against whatever IDE state exists, modals included (for
 *   intentional modal workflows and trivial hardcoded actions).
 */
enum class ModalMode(val wire: String) {
    SMART_NON_MODAL("smart_non_modal"),
    NON_MODAL("non_modal"),
    UNLEASHED("unleashed"),
    ;

    companion object {
        val DEFAULT = SMART_NON_MODAL
    }
}

data class McpProjectInfo(
    val name: String,
    val path: String,
)

data class McpWindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val modalDialogShowing: Boolean,
    val indexingInProgress: Boolean?,
    val projectInitialized: Boolean?,
)

internal fun ProcessResult.resolveJavaHomeLookup(jdkVersion: String): String {
    val javaHome = stdout.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("/") }
    if (javaHome != null) return javaHome

    require(exitCode == 0) {
        "[COMPILE] JDK $jdkVersion not found under /usr/lib/jvm; stdout=${stdout.take(500)} stderr=${stderr.take(500)}"
    }
    error("[COMPILE] JDK $jdkVersion lookup returned no path; stdout=${stdout.take(500)} stderr=${stderr.take(500)}")
}

class McpSteroidDriver(
    val driver: ContainerDriver,
    val ijDriver: IntelliJDriver,
) {
    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    private val json = Json { prettyPrint = true }

    val guestMcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"
    val hostMcpUrl get() = "http://localhost:${driver.mapGuestPortToHostPort(MCP_STEROID_PORT)}/mcp"

    fun waitForMcpReady() {
        waitFor(300_000, "Wait for MCP Steroid ready") {
            val result = driver.startProcessInContainer {
                this
                    .args("curl", "-s", "-f", guestMcpUrl, "-H", "Accept: application/json")
                    .timeoutSeconds(5)
                    .quietly()
                    .description("curl health check $guestMcpUrl")
            }.awaitForProcessFinish()
            result.exitCode == 0 && runCatching { resolveProjectName() }.isSuccess
        }

        mcpInitialize()
        resolveProjectName()

        println("[IDE-AGENT] MCP Steroid is ready in the container at $guestMcpUrl")
        println("[IDE-AGENT] MCP Steroid is ready in the host at $hostMcpUrl")
    }


    /**
     * List all open projects in the IDE via steroid_list_projects tool.
     */
    fun mcpListProjects(): List<McpProjectInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_projects")
                putJsonObject("arguments") { }
            }
        }.toString()

        val run = executeMcpRequest(sessionId, request)
        val data = json.parseToJsonElement(run)

        val text = data.jsonObject["result"]
            ?.jsonObject?.get("content")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
            ?: error("steroid_list_projects returned no content: $run")

        val response = json.parseToJsonElement(text)
        return response.jsonObject["projects"]
            ?.jsonArray
            ?.map {
                McpProjectInfo(
                    name = it.jsonObject["name"]!!.jsonPrimitive.content,
                    path = it.jsonObject["path"]!!.jsonPrimitive.content,
                )
            }
            ?: error("steroid_list_projects returned no projects: $text")
    }

    /**
     * Find the project name for the guest project directory.
     */
    fun resolveProjectName(): String {
        val guestProjectDir = ijDriver.getGuestProjectDir()
        return resolveProjectName(guestProjectDir) ?: error("Project is not open: $guestProjectDir")
    }

    /**
     * List all open IDE windows with project/indexing/modal status.
     */
    fun mcpListWindows(timeoutSeconds: Long = 120): List<McpWindowInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_windows")
                putJsonObject("arguments") { }
            }
        }.toString()

        val run = executeMcpRequest(sessionId, request, timeoutSeconds = timeoutSeconds)
        val data = json.parseToJsonElement(run)

        val text = data.jsonObject["result"]
            ?.jsonObject?.get("content")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
            ?: error("steroid_list_windows returned no content: $run")

        val response = json.parseToJsonElement(text)
        return response.jsonObject["windows"]
            ?.jsonArray
            ?.map {
                val window = it.jsonObject
                McpWindowInfo(
                    projectName = window["projectName"]?.jsonPrimitive?.contentOrNull,
                    projectPath = window["projectPath"]?.jsonPrimitive?.contentOrNull,
                    modalDialogShowing = window["modalDialogShowing"]?.jsonPrimitive?.booleanOrNull ?: false,
                    indexingInProgress = window["indexingInProgress"]?.jsonPrimitive?.booleanOrNull,
                    projectInitialized = window["projectInitialized"]?.jsonPrimitive?.booleanOrNull,
                )
            }
            ?: error("steroid_list_windows returned no windows payload: $text")
    }

    /**
     * Open a project directory in IntelliJ IDEA via steroid_open_project.
     * Call this during the pre-warm phase (before the measured agent run).
     */
    fun mcpOpenProject(projectPath: String, trustProject: Boolean? = true) {
        val sessionId = mcpInitialize()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_open_project")
                putJsonObject("arguments") {
                    put("task_id", "prewarm-open-project")
                    put("project_path", projectPath)
                    put("reason", "Pre-warm: open arena project before measured agent run")
                    if (trustProject != null) {
                        put("trust_project", trustProject)
                    }
                }
            }
        }.toString()
        val response = executeMcpRequest(sessionId, request, timeoutSeconds = 60)
        val responseJson = json.parseToJsonElement(response).jsonObject
        val isError = responseJson["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val errorText = responseJson["result"]?.jsonObject?.get("content")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "unknown error"
            error("steroid_open_project failed: $errorText")
        }
    }

    private fun resolveProjectName(projectPath: String): String? {
        return mcpListProjects().firstOrNull { it.path == projectPath }?.name
    }

    /**
     * Open README.md (or fallback source file) in the editor and show the Maven/Gradle tool window.
     *
     * Helps AI agents orient themselves from the IDE view immediately after project import.
     * All operations are best-effort — failures are logged but do not propagate.
     */
    fun mcpOpenFileAndBuildToolWindow(openFileOnStart: String? = null) {
        val projectName = resolveProjectName()

        // Escape the openFileOnStart path for embedding in Kotlin string template
        val filePathLiteral = if (openFileOnStart != null) {
            "\"$openFileOnStart\""
        } else {
            "null"
        }

        val code = """
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.withContext

// 1. Open a file for agent orientation.
// Use refreshAndFindFileByPath so VFS content is loaded from disk —
// git clone happened outside IntelliJ's file watcher, so findFileByPath
// may return a VirtualFile whose content cache is empty (black editor).
// Skip files > 10 KB — large README.md files (e.g. JHipster) cause the
// Markdown preview renderer to hang the IDE during startup.
val basePath = project.basePath ?: ""
val openFileRelPath: String? = $filePathLiteral
val maxFileSize = 10_000L

val fileToOpen = if (openFileRelPath != null) {
    val targetPath = "${'$'}basePath/${'$'}openFileRelPath"
    LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath)
} else {
    // Fallback chain: README.md (if small), then first small source file
    val baseDir = java.io.File(basePath)
    val readme = java.io.File(basePath, "README.md")
    if (readme.exists() && readme.length() <= maxFileSize) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(readme.absolutePath)
    } else {
        val sourceFile = baseDir.walkTopDown()
            .filter { it.isFile && it.length() <= maxFileSize }
            .filter { it.extension in listOf("java", "kt", "ts", "js") }
            .firstOrNull()
        if (sourceFile != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceFile.absolutePath)
        } else {
            null
        }
    }
}

if (fileToOpen != null) {
    withContext(Dispatchers.EDT) {
        FileEditorManager.getInstance(project).openFile(fileToOpen, true)
        println("[UX-SETUP] Opened file: ${'$'}{fileToOpen.path}")
    }
} else {
    println("[UX-SETUP] No file found to open (configured=${'$'}openFileRelPath)")
}

// 2. Show the Commit tool window (local changes) — more useful for agents than
// the build tool window, and avoids the Markdown preview hang issue.
withContext(Dispatchers.EDT) {
    try {
        ToolWindowManager.getInstance(project).getToolWindow("Commit")?.show()
        println("[UX-SETUP] Commit tool window shown")
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show Commit tool window: ${'$'}{e.message}")
    }
}

// 3. Show Maven or Gradle tool window depending on what build file exists
val pomFile = java.io.File(basePath, "pom.xml")
val gradleFile = java.io.File(basePath, "build.gradle")
val gradleKtsFile = java.io.File(basePath, "build.gradle.kts")

withContext(Dispatchers.EDT) {
    try {
        when {
            pomFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Maven")?.show()
                println("[UX-SETUP] Maven tool window shown")
            }
            gradleFile.exists() || gradleKtsFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Gradle")?.show()
                println("[UX-SETUP] Gradle tool window shown")
            }
            else -> println("[UX-SETUP] No pom.xml or build.gradle found — skipping build tool window")
        }
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show build tool window: ${'$'}{e.message}")
    }
}

// 3. Expand project tree root node (best-effort)
try {
    withContext(Dispatchers.EDT) {
        ProjectView.getInstance(project).currentProjectViewPane?.tree?.expandRow(0)
        println("[UX-SETUP] Project tree root expanded")
    }
} catch (e: Exception) {
    println("[UX-SETUP] Could not expand project tree: ${'$'}{e.message}")
}

"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Open project file and build tool window for agent orientation",
                timeout = 30,
            )
        } catch (e: Exception) {
            println("[UX-SETUP] Warning: UX setup failed: ${e.message}")
        }
    }

    /**
     * Execute Kotlin code via steroid_execute_code tool.
     *
     * This makes a direct HTTP call to the MCP server, bypassing AI agents.
     * Useful for integration tests that need reliable, deterministic behavior.
     *
     * @param code Kotlin code to execute (suspend function body)
     * @param taskId Task identifier (default: "integration-test")
     * @param reason Human-readable reason for execution
     * @param timeout Timeout in seconds (default: 600)
     * @param projectName Project name (defaults to the project at guestProjectDir)
     * @return MCP tool result as JSON string
     */
    fun mcpExecuteCode(
        code: String,
        taskId: String = "integration-test",
        reason: String = "Integration test execution",
        timeout: Int = 600,
        projectName: String = resolveProjectName(),
        /**
         * How exec_code treats IDE modality around the script. Mindfully defaulted to [ModalMode.DEFAULT]
         * and always sent explicitly on the wire, so every driver-issued exec_code makes a deliberate
         * modality choice rather than relying on the server's implicit default.
         */
        modal: ModalMode = ModalMode.DEFAULT,
    ): ProcessResult {
        // First, initialize MCP session
        val sessionId = mcpInitialize()

        // Build the tool call request using kotlinx.serialization
        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("code", code)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("timeout", timeout)
                    put("modal", modal.wire)
                }
            }
            put("method", "tools/call")
        }.toString()

        // Execute the tool call (curl timeout must exceed the server-side execution timeout)
        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        val data = json.parseToJsonElement(run)

        val messages = buildString {
            data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    println("[MCP LOG]: $text ")
                    appendLine(text)
                }
            }
        }

        val isError = data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: true

        return ProcessResultValue(
            exitCode = if (isError) 1 else 0,
            stdout = messages,
            stderr = "",
        )
    }

    /**
     * Fetch a `mcp-steroid://` resource by URI via the `steroid_fetch_resource` tool.
     *
     * Like [mcpExecuteCode], this makes a direct MCP call (no AI agent), so a test can
     * deterministically assert that an article resolves for the running IDE. The handler gates
     * each article on `IdeFilter.matches(productCode)`, where `productCode` comes from the
     * running IDE's `ApplicationInfo` — so fetching in a non-IDEA IDE is an end-to-end check
     * that the article is genuinely un-gated for that product.
     *
     * @return [ProcessResult] with exitCode 0 + the article payload on success; exitCode 1 +
     *         `ERROR: Resource not found: <uri>` when the article filter rejects the running IDE.
     */
    fun mcpFetchResource(
        uri: String,
        projectName: String = resolveProjectName(),
        timeout: Int = 120,
    ): ProcessResult {
        val sessionId = mcpInitialize()

        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_fetch_resource")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("uri", uri)
                }
            }
            put("method", "tools/call")
        }.toString()

        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        val data = json.parseToJsonElement(run)

        val messages = buildString {
            data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    appendLine(text)
                }
            }
        }

        val isError = data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: true

        return ProcessResultValue(
            exitCode = if (isError) 1 else 0,
            stdout = messages,
            stderr = "",
        )
    }

    private val mcpSessionIdHolder = AtomicReference<String?>(null)
    private fun mcpInitialize(): String {
        mcpSessionIdHolder.get()?.let {
            return it
        }

        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "integration-test")
                    put("version", "1.0")
                }
            }
        }.toString()

        val (responseBody, responseHeaders) = executeMcpRequestRaw(
            sessionId = null,
            requestBody = initRequest,
        )
        json.parseToJsonElement(responseBody)

        val sessionId = responseHeaders[SESSION_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: error("MCP initialize response missing $SESSION_HEADER header")

        mcpSessionIdHolder.set(sessionId)
        return sessionId
    }

    /**
     * Execute an MCP request via curl in the container.
     */
    private fun executeMcpRequest(
        sessionId: String,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): String {
        val responseBody = executeMcpRequestRaw(
            sessionId = sessionId,
            requestBody = requestBody,
            timeoutSeconds = timeoutSeconds,
        ).first
        return json.encodeToString(json.parseToJsonElement(responseBody.trim()))
    }

    private fun executeMcpRequestRaw(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): Pair<String, Map<String, String>> {
        //TODO: call it directly from the host with an HTTP client

        // Create curl command
        val curlCommand = buildList {
            add("curl")
            add("-s")  // Silent
            add("-D")  // Dump response headers to stdout
            add("-")
            add("-X")
            add("POST")
            add(guestMcpUrl)
            add("-H")
            add("Content-Type: application/json")
            add("-H")
            add("Accept: application/json")

            // Add MCP session header when available.
            if (sessionId != null) {
                add("-H")
                add("$SESSION_HEADER: $sessionId")
            }

            add("-d")
            add(requestBody)
        }

        val result = driver.startProcessInContainer {
            this
                .args(curlCommand)
                .timeoutSeconds(timeoutSeconds)
                .description("curl MCP request")
        }.assertExitCode(0) { "MCP request failed: $stdout" }

        val raw = result.stdout.replace("\r\n", "\n")
        val splitIndex = raw.indexOf("\n\n")
        require(splitIndex >= 0) { "Invalid HTTP response from MCP server: missing headers/body separator" }

        val headerLines = raw.substring(0, splitIndex)
            .lineSequence()
            .drop(1) // Skip HTTP status line.
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(":") }
            .toList()

        val headers = buildMap {
            for (line in headerLines) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                put(name, value)
            }
        }

        val body = raw.substring(splitIndex + 2)
        return body to headers
    }
}
