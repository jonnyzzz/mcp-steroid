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
import com.intellij.diagnostic.PluginException
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
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
import java.util.concurrent.ConcurrentHashMap
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
            // We only wait this short window per call so the request returns promptly. Reaching it does
            // NOT mean anything is wrong: dumb mode means indexing is actively running and making
            // progress. We tell the caller so, and let it poll (call again).
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
            // Not an error and not stuck — indexing is in progress. Tell the caller to keep polling.
            log.info("[$executionId] still indexing after $WAIT_FOR_SMART_MODE_TIMEOUT — asking caller to poll")
            throw ToolCallErrorException(
                "INDEXING IN PROGRESS: the IDE is still indexing this project, so it is not ready yet. " +
                    "This is normal and expected — it is NOT an error and nothing is stuck; indexing always " +
                    "makes progress and simply needs more time. Indexing a medium-size project can take up " +
                    "to an hour, and larger projects longer; that wait is acceptable. Just keep polling: " +
                    "call this tool again to continue waiting (each call waits up to $WAIT_FOR_SMART_MODE_TIMEOUT), " +
                    "and your request runs automatically once indexing finishes."
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
                }
                // #318: the recursive refresh must be awaited OUTSIDE the EDT context. Despite
                // RefreshQueue's suspend overload being documented as background, the captured
                // 31s freeze stack shows the directory scan executing on AWT-EventQueue-0 when
                // awaited from Dispatchers.EDT. Awaiting here (the execution's background
                // dispatcher) keeps the UI responsive with the same consistency contract — we
                // still suspend until the refresh completes before returning.
                project.vfsRefreshService.awaitRefresh()
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
    ): InspectionRunResult {
        checkDisposed()
        log.info("[$executionId] Running inspections directly on ${file.name}...")
        resultBuilder.logProgress("Running inspections on ${file.name}...")

        // Bounded wait first (60 s -> ToolCallErrorException) so a never-finishing indexing fails
        // fast instead of suspending forever inside the unbounded smartReadAction below.
        waitForSmartMode()

        // The sweep runs under smartReadAction, not readAction: dumb mode can begin between
        // waitForSmartMode() and a plain read action, and index-backed tools would then throw
        // IndexNotReadyException mid-visit — a transient IDE condition that must never be recorded
        // as a per-tool crash. smartReadAction re-runs the block under guaranteed smart mode.
        //
        // The failure map is allocated INSIDE the block: smartReadAction cancels and RETRIES its
        // block when a pending write action interrupts it, and failures recorded by an aborted
        // attempt must not leak into the retry — each attempt starts clean.
        val (problems, toolFailures) = intellijSmartReadAction(project) {
            // Per-tool crash isolation (issue #93): first failure of each tool, keyed by short name.
            val attemptFailures = ConcurrentHashMap<String, InspectionToolFailure>()
            val recordFailure: (String, Class<*>?, Throwable, Boolean) -> Unit = { toolId, toolClass, error, logAsError ->
                attemptFailures.putIfAbsent(toolId, InspectionToolFailure(toolClass, error, logAsError))
            }
            val noProblems = emptyMap<String, List<ProblemDescriptor>>()

            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile == null) {
                recordFailure(
                    InspectionRunResult.SWEEP_FAILURE_ID,
                    null,
                    IllegalArgumentException("No PSI file for ${file.path}; runInspectionsDirectly requires a real file VirtualFile"),
                    false
                )
                return@intellijSmartReadAction noProblems to attemptFailures
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
                return@intellijSmartReadAction noProblems to attemptFailures
            }

            // Issue #93: InspectionEngine only guards buildVisitor() — a visit-time exception from
            // ONE tool (e.g. kotlinx-serialization plugin-generated PSI under K2) aborts the whole
            // inspectEx call and loses every other tool's findings. Wrapping each tool in a
            // crash-isolating delegate keeps the sweep a single engine pass while a crashing tool
            // is recorded and skipped. The delegate preserves the original short name, so
            // LocalInspectionToolWrapper(tool) re-attaches the ORIGINAL inspection EP (language
            // applicability, IDs) and the result keys are unchanged.
            val isolatedWrappers = toolWrappers.mapNotNull { wrapper ->
                try {
                    LocalInspectionToolWrapper(
                        CrashIsolatingLocalInspectionTool(wrapper.tool) { toolId, toolClass, error ->
                            recordFailure(toolId, toolClass, error, true)
                        }
                    )
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IndexNotReadyException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    throw e
                } catch (e: Throwable) {
                    // wrapper.tool lazily instantiates the tool from its EP — class loading can fail
                    // with NoClassDefFoundError / LinkageError / AssertionError, not just Exception,
                    // so this mirrors the delegate's Throwable isolation. No tool instance exists
                    // here, hence no class to attribute the failure to (toolClass = null).
                    recordFailure(wrapper.shortName, null, e, true)
                    null
                }
            }

            log.info("[$executionId] Running ${isolatedWrappers.size} inspections on ${file.name}")

            val problemsOfAttempt = try {
                // Run inspections directly - bypasses daemon focus check
                val results = InspectionEngine.inspectEx(
                    isolatedWrappers,
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
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: IndexNotReadyException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw e
            } catch (e: Throwable) {
                // Issue #69: engine-internal PSI traversal can fail outside any single tool —
                // PsiInvalidElementAccessException on stale PSI, AssertionError, stub-tree
                // inconsistency errors. A file-level failure must not throw out of the helper:
                // scripts inspecting files in a loop keep the healthy files' results.
                recordFailure(InspectionRunResult.SWEEP_FAILURE_ID, null, e, true)
                noProblems
            }

            problemsOfAttempt to attemptFailures
        }

        if (toolFailures.isNotEmpty()) {
            for ((toolId, failure) in toolFailures) {
                // Attribute the error to the crashing inspection's plugin (the platform convention
                // from InspectionEngine.createVisitor), so IDE fatal-error balloons blame the plugin
                // that owns the inspection rather than mcp-steroid. Falls back to the raw error when
                // no tool instance exists (EP instantiation or sweep-level failures).
                if (failure.logAsError) {
                    val attributed = failure.toolClass
                        ?.let { toolClass -> PluginException.createByClass("Inspection tool '$toolId' failed", failure.error, toolClass) }
                        ?: failure.error
                    log.error("[$executionId] inspection '$toolId' crashed while inspecting ${file.name} — findings from other tools are preserved", attributed)
                } else {
                    log.warn("[$executionId] inspection '$toolId' did not run for ${file.path}: ${failure.error.message}")
                }
            }
            resultBuilder.logMessage(
                "WARNING: ${toolFailures.size} inspection issue(s) while inspecting ${file.name} " +
                    "(findings from any completed tools are preserved): " +
                    toolFailures.keys.sorted().joinToString(", ") +
                    ". Details are in the result's failedTools property."
            )
        }

        val failedTools = toolFailures.entries
            .map { (toolId, failure) -> FailedInspection(toolId = toolId, error = "${failure.error.javaClass.name}: ${failure.error.message}") }
            .sortedBy { it.toolId }
        return InspectionRunResult(problems, failedTools)
    }

    /**
     * One recorded failure of [runInspectionsDirectly]: the raw [error] (surfaced verbatim in
     * [FailedInspection.error]) plus the crashing tool's class, used only to attribute the logged
     * error to the inspection's plugin via [PluginException.createByClass]. [toolClass] is null
     * when no tool instance exists — EP instantiation failures and sweep-level failures.
     * [logAsError] is false for user/setup inputs that make a sweep impossible, such as passing
     * a directory [VirtualFile]; those are reported in-band through failedTools and logged as warn.
     */
    private class InspectionToolFailure(val toolClass: Class<*>?, val error: Throwable, val logAsError: Boolean)

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

    override fun findFile(absolutePath: String): VirtualFile? {
        val lfs = LocalFileSystem.getInstance()
        if (ApplicationManager.getApplication().isReadAccessAllowed) {
            // Synchronous refresh off-EDT under read access deadlocks (the write action
            // delivering VFS events can never start while our read lock is held —
            // VirtualFile.refresh / VfsUtil.markDirtyAndRefresh contract). Inside
            // read/write actions the helper is snapshot-only, same as before #156.
            return lfs.findFileByPath(absolutePath)?.takeIf { it.isValid }
        }
        // #156: ALWAYS refresh. A snapshot hit may still carry stale content — the
        // refresh-and-find utilities only instantiate path segments missing from the
        // snapshot and never re-stat an existing file, and in a headless eval IDE the
        // file watcher cannot be trusted to mark external changes dirty.
        val vf = lfs.refreshAndFindFileByPath(absolutePath) ?: return null
        // An UNSAVED in-memory Document + forced refresh would engage the platform's
        // memory-vs-disk conflict resolver (a dialog in production, IllegalStateException
        // in tests). The unsaved Document IS the newest content here — keep the snapshot.
        if (!FileDocumentManager.getInstance().isFileModified(vf)) {
            VfsUtil.markDirtyAndRefresh(/* async = */ false, /* recursive = */ false, /* reloadChildren = */ false, vf)
        }
        return vf.takeIf { it.isValid }
    }

    override suspend fun findPsiFile(absolutePath: String): PsiFile? {
        val vf = findFile(absolutePath) ?: return null
        return readAction { PsiManager.getInstance(project).findFile(vf) }
    }

    override fun findProjectFile(relativePath: String): VirtualFile? {
        // #156: agents routinely pass absolute paths (every tool result shows them).
        // Absolute input behaves exactly like findFile(absolutePath).
        if (isAbsolutePath(relativePath)) return findFile(relativePath)
        val basePath = project.basePath ?: return null
        return findFile("$basePath/$relativePath")
    }

    private fun isAbsolutePath(path: String): Boolean =
        path.startsWith("/") || runCatching { Path.of(path).isAbsolute }.getOrDefault(false)

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
        // Delegate through findProjectFile so absolute-path tolerance and refresh
        // semantics live in exactly one place (#156).
        val vf = findProjectFile(relativePath) ?: return null
        return readAction { PsiManager.getInstance(project).findFile(vf) }
    }

}
