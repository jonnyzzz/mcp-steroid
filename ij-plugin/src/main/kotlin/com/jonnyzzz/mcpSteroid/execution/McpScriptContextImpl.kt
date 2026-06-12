/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiManager
import com.intellij.util.PairProcessor
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.diagnostic.ThreadDumper
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.time.Duration
import com.intellij.openapi.application.readAction as intellijReadAction
import com.intellij.openapi.application.writeAction as intellijWriteAction
import com.intellij.openapi.application.smartReadAction as intellijSmartReadAction
import kotlin.time.Duration.Companion.milliseconds

/**
 * Implementation of McpScriptContext.
 *
 * Key features:
 * - Has a Disposable that scripts can use to register cleanup
 * - Rejects output operations after disposed
 * - Supports progress reporting via MCP notifications (throttled to 1/sec)
 * - No coroutineScope property - suspend functions get scope implicitly
 */
class McpScriptContextImpl(
    override val project: Project,
    val executionId: ExecutionId,
    override val disposable: Disposable,
    private val resultBuilder: ExecutionResultBuilder,
    /**
     * The execution's coroutine scope. [monitorAndCloseModalDialogs] launches its watcher here so it is
     * cancelled when the execution ends, and so throwing from it (on a detected modal) fails the execution.
     */
    private val executionScope: CoroutineScope,
) : McpScriptContext {

    /** The modal-dialog monitor job, if [monitorAndCloseModalDialogs] is active. */
    @Volatile
    private var modalMonitorJob: Job? = null

    /** Deadlock guard for [syncDocuments] (EDT write-intent can be withheld by a modal). */
    private val SYNC_DOCUMENTS_TIMEOUT = 60.seconds

    /** Deadlock guard for [waitForSmartMode] when indexing never reaches smart mode. */
    private val WAIT_FOR_SMART_MODE_TIMEOUT = 60.seconds
    private val log = Logger.getInstance(McpScriptContextImpl::class.java)

    private val objectMapper = ObjectMapper().apply {
        enable(SerializationFeature.INDENT_OUTPUT)
        // Don't fail on empty beans
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }.writerWithDefaultPrettyPrinter()

    private val disposed = AtomicBoolean(false).also {
        Disposer.register(disposable) { it.set(true) }
    }

    override val isDisposed: Boolean
        get() = disposed.get()

    private fun checkDisposed() {
        if (disposed.get()) {
            throw IllegalStateException("Context has been disposed - cannot perform output operations")
        }
    }

    override fun println(vararg values: Any?) {
        checkDisposed()
        resultBuilder.logMessage(values.joinToString(" ") { it?.toString() ?: "null" })
        resultBuilder.noteUserOutput()
    }

    override fun printException(message: String, throwable: Throwable) {
        checkDisposed()
        resultBuilder.logException(message, throwable)
    }

    override fun printJson(obj: Any?) {
        checkDisposed()
        try {
            val jsonString = when (obj) {
                null -> "null"
                is String -> obj
                else -> objectMapper.writeValueAsString(obj)
            }
            resultBuilder.logMessage(jsonString)
            resultBuilder.noteUserOutput()
        } catch (e: CancellationException) {
            // Cancellation propagates — don't wrap it as a serialization error.
            throw e
        } catch (e: Exception) {
            resultBuilder.logMessage("Failed to serialize to JSON: ${e.message}")
        }
    }

    override fun printCsv(headers: List<String>, rows: Iterable<List<Any?>>, dictColumns: Set<String>) {
        checkDisposed()
        try {
            val csv = formatCsv(headers, rows, dictColumns).trimEnd('\n')
            resultBuilder.logMessage(csv)
            resultBuilder.noteUserOutput()
        } catch (e: IllegalArgumentException) {
            // formatCsv validates row width and non-empty headers — surface
            // the contract violation as a normal log line so the script
            // doesn't crash mid-output.
            resultBuilder.logMessage("printCsv: ${e.message}")
        }
    }

    override fun printToon(value: Any?) {
        checkDisposed()
        resultBuilder.logMessage(formatToon(value))
        resultBuilder.noteUserOutput()
    }

    override fun progress(message: String) {
        checkDisposed()
        log.info("[$executionId] progress: $message")
        // Report progress directly without storing in output
        resultBuilder.logProgress(message)
    }

    override suspend fun takeIdeScreenshot(fileName: String): String? {
        checkDisposed()
        if (fileName.isNotBlank() && fileName != "ide-screenshot.png") {
            resultBuilder.logMessage("NOTE: takeIdeScreenshot ignores custom fileName and uses screenshot.png.")
        }
        return try {
            val artifacts = VisionService.getInstance(project).capture(executionId)
            resultBuilder.logImage("image/png", Base64.getEncoder().encodeToString(artifacts.imageBytes), artifacts.meta.imageFile)
            resultBuilder.logMessage("window_id: ${artifacts.meta.windowId}")
            resultBuilder.logMessage("Screenshot saved to ${artifacts.imagePath}")
            resultBuilder.logMessage("Component tree saved to ${artifacts.treePath}")
            resultBuilder.logMessage("Screenshot metadata saved to ${artifacts.metaPath}")
            artifacts.imagePath.toString()
        } catch (e: CancellationException) {
            // Coroutine cancellation propagates — same rule as
            // ProcessCanceledException (below): never log, never wrap.
            throw e
        } catch (e: Exception) {
            resultBuilder.logException("Failed to capture IDE screenshot", e)
            null
        }
    }

    override suspend fun waitForSmartMode() {
        checkDisposed()
        requireNonModal("waitForSmartMode")
        if (!DumbService.isDumb(project)) return

        log.info("[$executionId] Waiting for indexing to complete...")
        resultBuilder.logProgress("Waiting for indexing to complete...")

        try {
            // Bounded as a deadlock safety net (indexing that never reaches smart mode) — the tool docs
            // promise the wait is bounded. Timeout => fail the execution with a clear message.
            withTimeout(WAIT_FOR_SMART_MODE_TIMEOUT) {
                suspendCancellableCoroutine<Unit> { cont ->
                    fun waitForSmart() {
                        if (disposed.get()) {
                            cont.cancel()
                            return
                        }
                        DumbService.getInstance(project).smartInvokeLater {
                            if (disposed.get()) {
                                cont.cancel()
                            } else if (DumbService.isDumb(project)) {
                                waitForSmart()
                            } else {
                                cont.resume(Unit)
                            }
                        }
                    }
                    waitForSmart()
                }
            }
        } catch (_: TimeoutCancellationException) {
            captureThreadDump("waitForSmartMode-timeout")
            log.error("[$executionId] waitForSmartMode did not reach smart mode within $WAIT_FOR_SMART_MODE_TIMEOUT")
            throw ToolCallErrorException(
                "waitForSmartMode did not reach smart mode within $WAIT_FOR_SMART_MODE_TIMEOUT — indexing may " +
                    "be stuck. See the thread dump under execution '${executionId.executionId}'."
            )
        } finally {
            log.info("[$executionId] Waiting for indexing completed")
            resultBuilder.logProgress("Waiting for indexing completed")
        }
    }

    // ============================================================
    // Modal Dialog Control
    // ============================================================

    override suspend fun closeModalDialogs(): Int {
        checkDisposed()
        val found = dialogWindowsLookup().withDialogWindows(project) { it.size }
        // Nothing to close (the common case under smart_non_modal) — don't attach a diagnostic dump.
        if (found == 0) return 0
        captureThreadDump("closeModalDialogs")
        // killProjectDialogs captures a screenshot before closing each dialog (VisionService).
        dialogKiller().killProjectDialogs(
            project = project,
            executionId = executionId,
            logMessage = { resultBuilder.logMessage(it) },
            forceEnabled = true,
        )
        return found
    }

    override fun monitorAndCloseModalDialogs() {
        checkDisposed()
        if (modalMonitorJob?.isActive == true) return
        log.info("[$executionId] modal-dialog monitor started")
        val job = executionScope.launch(CoroutineName("modal-monitor-$executionId")) {
            while (isActive) {
                delay(1000L.milliseconds)
                if (disposed.get()) return@launch
                // Only a real modal DialogWrapper counts — not mere indexing/progress modality.
                val hasModalDialog = dialogWindowsLookup().withModalityCheck { it }
                if (!hasModalDialog) continue
                log.warn("[$executionId] modal dialog appeared while running — closing and failing the execution")
                captureThreadDump("modal-monitor")
                val closed = closeModalDialogs()
                throw ToolCallErrorException(
                    "A modal dialog appeared while the script was running — closed $closed dialog(s) and " +
                        "failed the run. If your script opens a dialog on purpose, call allowModalDialog() " +
                        "first. See the screenshot + thread dump under execution '${executionId.executionId}'."
                )
            }
        }
        modalMonitorJob = job
        Disposer.register(disposable) { job.cancel() }
    }

    override fun allowModalDialog() {
        checkDisposed()
        log.info("[$executionId] modal-dialog monitor suspended by script (allowModalDialog)")
        modalMonitorJob?.cancel()
        modalMonitorJob = null
    }

    override suspend fun syncDocuments() {
        checkDisposed()
        requireNonModal("syncDocuments")
        try {
            withTimeout(SYNC_DOCUMENTS_TIMEOUT) {
                withContext(Dispatchers.EDT) {
                    PsiDocumentManager.getInstance(project).commitAllDocuments()
                    FileDocumentManager.getInstance().saveAllDocuments()
                    project.vfsRefreshService.awaitRefresh()
                }
            }
        } catch (_: TimeoutCancellationException) {
            captureThreadDump("syncDocuments-timeout")
            log.error("[$executionId] syncDocuments did not complete within $SYNC_DOCUMENTS_TIMEOUT (EDT likely blocked by a modal)")
            throw ToolCallErrorException(
                "syncDocuments did not complete within $SYNC_DOCUMENTS_TIMEOUT — a modal dialog likely " +
                    "blocks the EDT. See the thread dump under execution '${executionId.executionId}'."
            )
        }
        // A modal may have surfaced as a side effect of commit/save/refresh — fail rather than continue stale.
        if (dialogWindowsLookup().isModalEdt()) {
            captureThreadDump("syncDocuments-modal-side-effect")
            throw ToolCallErrorException(
                "syncDocuments surfaced a modal dialog (commit/save/refresh side effect) — failing the run. " +
                    "See the thread dump under execution '${executionId.executionId}'."
            )
        }
    }

    /** Fail the execution if the IDE is currently in a modal state (Yury's check). */
    private suspend fun requireNonModal(operation: String) {
        if (dialogWindowsLookup().isModalEdt()) {
            captureThreadDump("$operation-requires-non-modal")
            throw ToolCallErrorException(
                "$operation requires a non-modal IDE, but a modal dialog is present. " +
                    "Use modal=smart_non_modal (closes dialogs first) or call closeModalDialogs() before this. " +
                    "See the thread dump under execution '${executionId.executionId}'."
            )
        }
    }

    /** Record a thread dump with the execution (diagnostics for a stuck/modal EDT). */
    private suspend fun captureThreadDump(reason: String) {
        try {
            val dump = ThreadDumper.dumpThreadsToString()
            log.info("[$executionId] thread dump ($reason):\n$dump")
            project.executionStorage.writeCodeExecutionData(executionId, "thread-dump-$reason.txt", dump)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("[$executionId] failed to capture thread dump ($reason): ${e.message}")
        }
    }

    // ============================================================
    // Daemon Code Analysis
    // ============================================================

    override suspend fun isEditorHighlightingCompleted(file: VirtualFile): Boolean {
        checkDisposed()

        val editor = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).getSelectedEditor(file)
        } ?: return false

        return readAction {
            DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)
        }
    }

    override suspend fun waitForEditorHighlighting(file: VirtualFile, timeout: Duration): Boolean {
        checkDisposed()
        log.info("[$executionId] Waiting for daemon analysis on ${file.name}...")
        resultBuilder.logProgress("Waiting for daemon analysis on ${file.name}...")

        // First wait for smart mode
        waitForSmartMode()

        // Find the editor for the file
        val editor = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).getEditors(file)
                .filterIsInstance<TextEditor>()
                .firstOrNull()
        }

        if (editor == null) {
            log.warn("[$executionId] No text editor found for ${file.name}, cannot wait for highlighting")
            return false
        }

        // Wait for highlighting to complete
        val completed = withTimeoutOrNull(timeout) {
            while (!disposed.get()) {
                val isComplete = withContext(Dispatchers.EDT) {
                    DaemonCodeAnalyzerEx.isHighlightingCompleted(editor, project)
                }
                if (isComplete) break
                delay(50.milliseconds)
            }
            true
        } ?: false

        if (completed) {
            log.info("[$executionId] Daemon analysis completed for ${file.name}")
        } else {
            log.warn("[$executionId] Timeout waiting for daemon analysis on ${file.name}")
        }
        return completed
    }

    override suspend fun getHighlightsWhenReady(
        file: VirtualFile,
        minSeverityValue: Int,
        timeout: Duration
    ): List<HighlightInfo> {
        checkDisposed()

        // Wait for analysis to complete
        val completed = waitForEditorHighlighting(file, timeout)
        if (!completed) {
            return emptyList()
        }

        // Get document for the file
        val document = readAction {
            FileDocumentManager.getInstance().getDocument(file)
        } ?: return emptyList()

        // Get all highlights
        return readAction {
            getHighlightsFromDaemon(document, minSeverityValue)
        }
    }

    private fun getHighlightsFromDaemon(document: Document, minSeverityValue: Int): List<HighlightInfo> {
        val allHighlights = mutableListOf<HighlightInfo>()

        DaemonCodeAnalyzerEx.processHighlights(
            document,
            project,
            null, // null severity means all severities
            0,
            document.textLength
        ) { info ->
            if (info.severity.myVal >= minSeverityValue) {
                allHighlights.add(info)
            }
            true // continue processing
        }

        return allHighlights
    }

    // ============================================================
    // Direct Inspection Execution (bypasses daemon focus check)
    // ============================================================

    override suspend fun runInspectionsDirectly(
        file: VirtualFile,
        includeInfoSeverity: Boolean
    ): Map<String, List<ProblemDescriptor>> {
        checkDisposed()
        log.info("[$executionId] Running inspections directly on ${file.name}...")
        resultBuilder.logProgress("Running inspections on ${file.name}...")

        // Wait for smart mode first
        waitForSmartMode()

        return readAction {
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile == null) {
                log.warn("[$executionId] Could not find PSI file for ${file.name}")
                return@readAction emptyMap()
            }

            // Get inspection profile and enabled tools
            val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
            val toolWrappers = profile.getAllEnabledInspectionTools(project)
                .mapNotNull { toolState ->
                    val tool = toolState.tool
                    if (tool is LocalInspectionToolWrapper) {
                        // Filter by severity if needed
                        val key = HighlightDisplayKey.find(tool.shortName)
                        if (key != null) {
                            val severity = profile.getErrorLevel(key, psiFile).severity
                            if (includeInfoSeverity || severity.myVal >= HighlightSeverity.WEAK_WARNING.myVal) {
                                tool
                            } else {
                                null
                            }
                        } else {
                            // Include tool if we can't determine severity
                            tool
                        }
                    } else {
                        null
                    }
                }

            if (toolWrappers.isEmpty()) {
                log.info("[$executionId] No applicable inspection tools found")
                return@readAction emptyMap()
            }

            log.info("[$executionId] Running ${toolWrappers.size} inspections on ${file.name}")

            // Run inspections directly - bypasses daemon focus check
            val results = InspectionEngine.inspectEx(
                toolWrappers,
                psiFile,
                psiFile.textRange,
                psiFile.textRange,
                false,  // isOnTheFly = false (batch mode)
                false,  // inspectInjectedPsi
                true,   // ignoreSuppressedElements
                EmptyProgressIndicator(),
                PairProcessor.alwaysTrue()
            )

            // Convert to map of tool ID -> problems
            results.mapKeys { (wrapper, _) -> wrapper.shortName }
                .filterValues { it.isNotEmpty() }
        }
    }

    // ============================================================
    // Read/Write Actions - Convenience Wrappers
    // ============================================================

    override suspend fun <T> readAction(action: () -> T): T = intellijReadAction(action)

    override suspend fun <T> writeAction(action: () -> T): T = intellijWriteAction(action)

    override suspend fun <T> smartReadAction(action: () -> T): T = intellijSmartReadAction(project, action)

    // ============================================================
    // Search Scopes
    // ============================================================

    override fun projectScope(): GlobalSearchScope = GlobalSearchScope.projectScope(project)

    override fun allScope(): GlobalSearchScope = GlobalSearchScope.allScope(project)

    // ============================================================
    // File Access
    // ============================================================

    override fun findFile(absolutePath: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(absolutePath)

    override suspend fun findPsiFile(absolutePath: String): PsiFile? {
        val vf = findFile(absolutePath) ?: return null
        return readAction { PsiManager.getInstance(project).findFile(vf) }
    }

    override fun findProjectFile(relativePath: String): VirtualFile? {
        val basePath = project.basePath ?: return null
        return findFile("$basePath/$relativePath")
    }

    override suspend fun findProjectFiles(globPattern: String): List<VirtualFile> {
        if (globPattern.isBlank()) return emptyList()

        val projectRoot = project.guessProjectDir()
            ?: project.basePath?.let(::findFile)
            ?: return emptyList()

        val primaryMatcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$globPattern")
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        val fallbackMatcher = runCatching {
            val adjusted = globPattern.replace('/', File.separatorChar)
            if (adjusted == globPattern) null else FileSystems.getDefault().getPathMatcher("glob:$adjusted")
        }.getOrNull()

        return readAction {
            val matches = mutableListOf<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(projectRoot, null) { file ->
                val relativePathText = VfsUtilCore.getRelativePath(file, projectRoot, '/')
                    ?: return@iterateChildrenRecursively true
                val relativePath = runCatching { Path.of(relativePathText) }
                    .getOrElse { return@iterateChildrenRecursively true }

                if (primaryMatcher.matches(relativePath) || (fallbackMatcher?.matches(relativePath) == true)) {
                    matches += file
                }
                true
            }

            matches.sortedBy { it.path }
        }
    }

    override suspend fun findProjectPsiFile(relativePath: String): PsiFile? {
        val basePath = project.basePath ?: return null
        return findPsiFile("$basePath/$relativePath")
    }

    override suspend fun applyPatch(block: ApplyPatchBuilder.() -> Unit): ApplyPatchResult {
        val builder = ApplyPatchBuilder()
        builder.block()
        // Reuse findFile so apply-patch handles every VFS flavour the rest of the
        // script context handles (LocalFileSystem in production, temp:// in unit
        // tests, mock VFS in fixtures) — no hard-coded LocalFileSystem.
        return executeApplyPatch(project, builder.hunks) { path -> findFile(path) }
    }
}
