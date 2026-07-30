/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerializationException
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

/**
 * Marker the server's `waitForSmartMode` emits when the IDE is still indexing — the signal to keep
 * polling (call again). Mirrors the message in `McpScriptContextImpl.waitForSmartMode`; the coupling is
 * the documented tool-result contract.
 */
internal const val INDEXING_IN_PROGRESS_MARKER = "INDEXING IN PROGRESS"

/** Overall budget for polling through "still indexing" — large projects can take a long time. */
private const val INDEXING_POLL_BUDGET_MS = 60 * 60 * 1000L

/** Pure: does this tool-result text say the IDE is still indexing (so we should call again)? */
internal fun isIndexingInProgress(text: String): Boolean = text.contains(INDEXING_IN_PROGRESS_MARKER)

/** The message the plugin logs (SteroidsMcpServer) when its MCP web server cannot start. */
internal const val MCP_SERVER_STARTUP_FAILURE_MARKER = "Failed to start MCP server"

/**
 * Returns the first IDE-log line that reports the MCP web server failed to start, or null. The plugin
 * logs [MCP_SERVER_STARTUP_FAILURE_MARKER] when it cannot bind its server (busy ports, a startup
 * exception, etc.). We key on that symptom — "the web server did not come up" — not on any specific root
 * cause, so the check is independent of why it failed.
 */
internal fun findMcpServerStartupFailure(logLines: List<String>): String? =
    logLines.firstOrNull { it.contains(MCP_SERVER_STARTUP_FAILURE_MARKER) }

/**
 * Thrown when an MCP request's curl was killed (exit -1) because the IDE was too busy — saturated by a
 * big-project import/indexing — to answer in time. It means "still busy, call again": a plain [Exception]
 * (NOT a [WaitAbortedError]) so [waitFor] swallows-and-retries it, and the hand-rolled project-open
 * poll loops that `catch (Exception)` retry it too. The silent twin of the clean `INDEXING IN PROGRESS`
 * marker ([isIndexingInProgress]). A *script-level* error (compile/runtime) instead comes back as a clean
 * isError result with a real exit code, never this killed-process -1, so it is not transient and surfaces
 * immediately. The request layer ([McpSteroidDriver] curl handling) throws this type directly, so the
 * retry policy is implicit for every MCP call. Its fatal twin is [McpRequestFailedError]. See
 * jonnyzzz/mcp-steroid#169.
 */
class TransientMcpRequestException(message: String) : RuntimeException(message)

/**
 * Thrown when an MCP request genuinely failed: curl could not reach the server (a non-`-1` non-zero exit —
 * curl uses no `--max-time`, so our process timeout is the only thing that kills a *busy* server with -1;
 * any other non-zero exit means connection refused / unreachable), or the server returned a malformed HTTP
 * envelope. A [WaitAbortedError] (an [Error]), so [waitFor] stops the loop at once — a full MCP failure
 * fails the test immediately instead of being retried for the whole indexing budget, and a `catch
 * (Exception)` retry loop does not swallow it. This is the fatal twin of the transient
 * [TransientMcpRequestException]; we expect it only on a real crash, which is rare, so we deliberately do
 * not retry it. The request layer throws this type directly, so the policy is implicit for every MCP call.
 */
class McpRequestFailedError(message: String) : WaitAbortedError(message)

/**
 * Parse an MCP response body, or fail terminally. A malformed/non-JSON envelope is a protocol breakage,
 * not a busy IDE — so it throws [McpRequestFailedError] (an [Error]) to stop a [waitFor] at once rather
 * than letting a deterministic parse failure be retried for the whole indexing budget. Used by every MCP
 * request-parse boundary reachable from `mcpExecuteCode`'s poll (`mcpInitialize`, `executeMcpRequest`), so
 * the "every terminal path is typed" invariant the typed-retry design relies on actually holds.
 */
fun parseMcpResponseOrFail(body: String): JsonElement =
    try {
        Json.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw McpRequestFailedError("Malformed JSON in MCP response: ${e.message}")
    }

/**
 * The body of a `tools/call` MCP response — its content texts, newline-joined — or throw. The body is
 * returned whether the tool reported success or an error (an error text like `INDEXING IN PROGRESS` or a
 * compile failure IS the payload the caller inspects); [parseMcpToolResultIsError] reads the error flag.
 * A *valid-JSON-but-wrong-shape* envelope (e.g. `result` is a string, `content` is not an array) is a
 * protocol breakage, not a busy IDE — any kotlinx accessor type-mismatch (an [IllegalArgumentException])
 * throws a terminal [McpRequestFailedError] rather than a plain exception a [waitFor] would retry for the
 * whole indexing budget; a non-JSON body throws the same via [parseMcpResponseOrFail]. *Missing* optional
 * fields degrade gracefully (empty body), preserving the normal "script returned an error result" path.
 */
fun parseMcpToolResultBody(response: String): String =
    try {
        buildString {
            parseMcpResponseOrFail(response).jsonObject["result"]?.jsonObject?.get("content")?.jsonArray?.forEach { item ->
                item.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { appendLine(it) }
            }
        }
    } catch (e: IllegalArgumentException) {
        throw McpRequestFailedError("Malformed MCP tool response shape: ${e.message}")
    }

/**
 * The `isError` flag of a `tools/call` MCP response (a missing `result`/`isError` counts as an error —
 * the graceful "script returned an error result" path). Same terminal typing as [parseMcpToolResultBody]:
 * wrong shape → [McpRequestFailedError].
 */
fun parseMcpToolResultIsError(response: String): Boolean =
    try {
        parseMcpResponseOrFail(response).jsonObject["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull ?: true
    } catch (e: IllegalArgumentException) {
        throw McpRequestFailedError("Malformed MCP tool response shape: ${e.message}")
    }

class McpSteroidDriver(
    val driver: ContainerDriver,
    val ijDriver: IntelliJDriver,
    /**
     * The IDE process this MCP server lives in — the liveness signal for [waitForMcpReady]. Passed in
     * from [IntelliJDriver.startIde]'s return value (the driver stays stateless — it can start multiple
     * container processes and holds no mutable process field); kept private so the process-level surface
     * does not leak past this driver.
     */
    private val ideProcess: RunningContainerProcess,
) {
    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    private val json = Json { prettyPrint = true }

    val guestMcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"
    val hostMcpUrl get() = "http://localhost:${driver.mapGuestPortToHostPort(MCP_STEROID_PORT)}/mcp"

    fun waitForMcpReady() {
        waitFor(300_000, "MCP Steroid server ready") {
            // Dead-IDE/dead-container fail-fast: a dead IDE process can never serve MCP, but its symptoms —
            // `docker exec` exiting non-zero (125 on a dead container) or curl connection-refused — are
            // indistinguishable from "server still starting", so they alone must keep retrying. The process
            // liveness (`kill -0` via docker exec; also false when the whole container is gone) is the real
            // signal: when it drops, log the process details and stop the 300s poll at once instead of
            // burning it to the deadline (quorum follow-up to the typed-retry rework).
            if (!ideProcess.isRunning()) {
                ideProcess.printProcessInfo() // exit code + output tails, logged by the process itself
                throw McpRequestFailedError("IDE died while waiting for the MCP Steroid server")
            }

            // The container interactions here are terminal-by-default: reading the IDE log and running the
            // health-check curl both go through `docker exec`, which THROWS if the container has died — a
            // terminal infrastructure failure, so we map it to McpRequestFailedError (an Error) and the wait
            // stops at once instead of polling to the 300s deadline. A still-STARTING server is NOT this: it
            // keeps the container alive and merely makes curl exit nonzero (handled below as "not ready →
            // retry"). The other fail-fast signal is the startup-failure log marker (WaitAbortedError, an
            // Error too — not caught by the `catch (Exception)` below, so it also propagates).
            val healthCheckExit = try {
                findMcpServerStartupFailure(ijDriver.readLogs())?.let { line ->
                    throw WaitAbortedError(
                        "MCP Steroid web server failed to start in ${ijDriver.ideProduct.displayName}: $line",
                    )
                }
                driver.startProcessInContainer {
                    this
                        .args("curl", "-s", "-f", guestMcpUrl, "-H", "Accept: application/json")
                        .timeoutSeconds(5)
                        .quietly()
                        .description("curl health check $guestMcpUrl")
                }.awaitForProcessFinish().exitCode
            } catch (e: Exception) {
                throw McpRequestFailedError("MCP health-check transport failed (${e.javaClass.simpleName}): ${e.message}")
            }

            // The nullable resolveProjectName(dir) overload returns null while the project is simply not open
            // yet (→ retry, the normal startup case) but PROPAGATES a terminal McpRequestFailedError from the
            // MCP call (→ stop). Using it instead of `runCatching { resolveProjectName() }` — which would
            // swallow even that Error and retry to the 300s deadline — keeps the terminal-by-type contract.
            // A transient busy (-1) stays a plain TransientMcpRequestException that waitFor retries.
            healthCheckExit == 0 && resolveProjectName(ijDriver.getGuestProjectDir()) != null
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

        val payload = parseMcpToolResultBody(executeMcpRequest(sessionId, request)).trim()

        // A malformed/missing projects payload is a deterministic protocol breakage, not a busy IDE: type it
        // as McpRequestFailedError so a poll (waitForMcpReady) stops at once instead of retrying to its budget.
        return try {
            parseMcpResponseOrFail(payload).jsonObject["projects"]
                ?.jsonArray
                ?.map {
                    McpProjectInfo(
                        name = it.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                            ?: throw McpRequestFailedError("steroid_list_projects entry missing 'name': $payload"),
                        path = it.jsonObject["path"]?.jsonPrimitive?.contentOrNull
                            ?: throw McpRequestFailedError("steroid_list_projects entry missing 'path': $payload"),
                    )
                }
                ?: throw McpRequestFailedError("steroid_list_projects returned no projects payload: $payload")
        } catch (e: IllegalArgumentException) {
            throw McpRequestFailedError("steroid_list_projects malformed payload: ${e.message}")
        }
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

        val payload = parseMcpToolResultBody(executeMcpRequest(sessionId, request, timeoutSeconds = timeoutSeconds)).trim()

        // A malformed/missing windows payload is a deterministic protocol breakage, not a busy IDE: type it as
        // McpRequestFailedError so waitForIdeWindow's `catch (Exception)` poll does not retry it to the deadline
        // (it lets this Error fail fast). The normal "not ready yet" path returns a valid windows list, never an
        // exception, so this does not affect legitimate polling.
        return try {
            parseMcpResponseOrFail(payload).jsonObject["windows"]
                ?.jsonArray
                ?.map {
                    val window = it.jsonObject
                    McpWindowInfo(
                        // snake_case keys since #381 — the shared ListedWindow contract (project_name/project_path).
                        projectName = window["project_name"]?.jsonPrimitive?.contentOrNull,
                        projectPath = window["project_path"]?.jsonPrimitive?.contentOrNull,
                        modalDialogShowing = window["modalDialogShowing"]?.jsonPrimitive?.booleanOrNull ?: false,
                        indexingInProgress = window["indexingInProgress"]?.jsonPrimitive?.booleanOrNull,
                        projectInitialized = window["projectInitialized"]?.jsonPrimitive?.booleanOrNull,
                    )
                }
                ?: throw McpRequestFailedError("steroid_list_windows returned no windows payload: $payload")
        } catch (e: IllegalArgumentException) {
            throw McpRequestFailedError("steroid_list_windows malformed payload: ${e.message}")
        }
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
     *
     * If the IDE is still indexing, each call waits a short window and returns an "INDEXING IN PROGRESS"
     * result; we poll exactly as an agent would (call again), since indexing always makes progress and a
     * large project can legitimately take a long time. Polling is bounded by [INDEXING_POLL_BUDGET_MS].
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
    ): ProcessResult =
        // Retry-while-busy, the same way an agent would: just call again. The exception types from the
        // request layer drive the wait, so no try/catch is needed here: a transient TransientMcpRequestException
        // (IDE too busy to answer, curl killed, exit -1) is a plain exception that waitFor swallows-and-retries,
        // while a fatal McpRequestFailedError (a WaitAbortedError) stops the loop at once instead of
        // retrying a genuine crash for the whole budget. The last "busy" signal is a clean INDEXING IN
        // PROGRESS result → null → call again.
        waitForValue(INDEXING_POLL_BUDGET_MS, "exec_code '$taskId' to run (IDE busy with import/indexing)") {
            mcpExecuteCodeOnce(code, taskId, reason, timeout, projectName, modal)
                .takeUnless { isIndexingInProgress(it.stdout) }
        }

    private fun mcpExecuteCodeOnce(
        code: String,
        taskId: String,
        reason: String,
        timeout: Int,
        projectName: String,
        modal: ModalMode,
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
        val body = parseMcpToolResultBody(run)
        body.lineSequence().filter { it.isNotBlank() }.forEach { println("[MCP LOG]: $it ") }
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = body,
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
        return ProcessResultValue(
            exitCode = if (parseMcpToolResultIsError(run)) 1 else 0,
            stdout = parseMcpToolResultBody(run),
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
        parseMcpResponseOrFail(responseBody)

        // A missing session header on an otherwise-OK response is a protocol breakage, not a busy IDE —
        // McpRequestFailedError (an Error) so a retrying caller stops at once instead of looping the budget.
        val sessionId = responseHeaders[SESSION_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: throw McpRequestFailedError("MCP initialize response missing $SESSION_HEADER header")

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
        return json.encodeToString(parseMcpResponseOrFail(responseBody.trim()))
    }

    private fun executeMcpRequestRaw(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): Pair<String, Map<String, String>> {
        //TODO: call it directly from the host with an HTTP client

        // Write the request body to a file inside the container and read it with `curl -d @file`.
        // Passing JSON inline via `-d '...'` through `bash -c` is broken on Windows: Java's
        // ProcessBuilder does not escape double-quote characters when building the Windows
        // command-line string, so CommandLineToArgvW strips all `"` from the JSON, producing
        // unquoted keys/values that the MCP server rejects (-32600 "jsonrpc must be 2.0").
        val bodyFile = "/tmp/mcp-steroid-request.json"

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
            add("@$bodyFile")
        }

        // The container/process transport is terminal-by-default. Writing the request file or spawning curl
        // can fail outright when the IDE container has died or is unreachable — a *full* failure, not a busy
        // IDE — so any thrown transport error becomes a McpRequestFailedError (an Error): a poll stops at once
        // instead of retrying it for the whole budget. A merely *busy* IDE never throws here; it surfaces as
        // the returned exit code -1 handled below. This makes the primitive's only outcomes: a valid response,
        // a TransientMcpRequestException (retry), or a McpRequestFailedError (terminal) — no untyped escape.
        val result = try {
            driver.writeFileInContainer(bodyFile, requestBody)
            driver.startProcessInContainer {
                this
                    .args(curlCommand)
                    .timeoutSeconds(timeoutSeconds)
                    .description("curl MCP request")
            }.awaitForProcessFinish()
        } catch (e: Exception) {
            throw McpRequestFailedError("MCP request transport failed (${e.javaClass.simpleName}): ${e.message}")
        }

        // The request layer throws the typed exceptions that drive every retrying caller (mcpExecuteCode's
        // waitForValue and the hand-rolled project-open poll loops); the fatal-vs-retry decision is implicit
        // in the type, so no caller needs a try/catch.
        //  - exit -1 = curl was killed by our process timeout because the IDE was too busy to even answer in
        //    time → transient "call again" (a plain TransientMcpRequestException, swallowed-and-retried).
        //  - any OTHER non-zero exit = curl could not reach the server (no --max-time is set, so a *busy*
        //    server only ever yields -1; a non-`-1` failure means connection refused / unreachable) → a real
        //    crash, McpRequestFailedError (a WaitAbortedError) so the wait stops at once.
        if (result.exitCode == -1) {
            throw TransientMcpRequestException("MCP request did not complete: IDE too busy to answer (curl killed, exit -1)")
        }
        if (result.exitCode != 0) {
            throw McpRequestFailedError("MCP request failed (exit ${result.exitCode}): ${result.stdout} ${result.stderr}")
        }

        val raw = result.stdout.replace("\r\n", "\n")
        val splitIndex = raw.indexOf("\n\n")
        if (splitIndex < 0) {
            throw McpRequestFailedError("Invalid HTTP response from MCP server: missing headers/body separator")
        }

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
