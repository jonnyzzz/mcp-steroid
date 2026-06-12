/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Context provided to exec_code scripts.
 *
 * IMPORTANT: Script code runs in a suspend context; exec_code executes the script body directly.
 * Under the default modal=smart_non_modal profile, waitForSmartMode() is called automatically before your
 * script starts; other modal modes (non_modal / unleashed) must call it explicitly if they need it.
 * This context provides helper functions for common IntelliJ operations.
 *
 * ## Quick Reference
 *
 * ```kotlin
 * // Under modal=smart_non_modal (default), waitForSmartMode() is called automatically before your script
 * // Read PSI/VFS data (use helpers - no imports needed!):
 * val psiFile = readAction {
 *     PsiManager.getInstance(project).findFile(virtualFile)
 * }
 *
 * // Or use even simpler helpers:
 * val psiFile = findPsiFile("/path/to/file.kt")
 *
 * // Modify PSI/VFS:
 * writeAction {
 *     document.setText("new content")
 * }
 *
 * // Get search scopes easily:
 * val scope = projectScope()  // or allScope()
 * ```
 *
 * NEVER use runBlocking - it causes deadlocks.
 */
interface McpScriptContext {
    /** The IntelliJ Project this execution is associated with */
    val project: Project

    /** Allows binding a disposable to the execution context; use coroutineScope {} for coroutine API */
    val disposable: Disposable

    /** Allows to check if the context is disposed */
    val isDisposed: Boolean

    // ============================================================
    // Output Methods
    // ============================================================

    /**
     * Print values to the output, separated by spaces, followed by a newline.
     * Each argument is converted to a string via toString().
     *
     * ```kotlin
     * println("Hello", "World", 42)  // prints: "Hello World 42"
     * println()  // prints empty line
     * ```
     */
    fun println(vararg values: Any?)

    /**
     * Serialize an object to pretty-printed JSON and output it.
     * Uses Jackson ObjectMapper with indentation.
     *
     * ```kotlin
     * printJson(mapOf("name" to "value", "count" to 42))
     * ```
     */
    fun printJson(obj: Any?)

    /**
     * Print rows as CSV (RFC 4180 escaping) with a single header line. Best
     * fit for flat tabular results from PSI / index searches: find-references,
     * call-hierarchy, project-search, hierarchy-search, document-symbols.
     *
     * When [dictColumns] is non-empty, those columns are emitted once as an
     * `@<col>:` dictionary preamble; each cell in those columns is then
     * replaced with a short ID (`p1`, `p2`, …) so duplicated values (such as
     * absolute file paths repeated across rows) cost their tokens once
     * instead of N times. Unknown column names in [dictColumns] are silently
     * ignored; entries with the same first character collide on the ID
     * prefix — pick column names with distinct first letters.
     *
     * ```kotlin
     * printCsv(
     *   headers = listOf("idx", "path", "line", "col", "snippet"),
     *   rows = refs.mapIndexed { i, r -> listOf(i + 1, r.path, r.line, r.col, r.snippet) },
     *   dictColumns = setOf("path"),
     * )
     * ```
     */
    fun printCsv(
        headers: List<String>,
        rows: Iterable<List<Any?>>,
        dictColumns: Set<String> = emptySet(),
    )

    /**
     * Encode a Kotlin/Java value as TOON (Token-Oriented Object Notation)
     * and print it. Drop-in for [printJson] when the data is array-of-records
     * shaped — TOON's array-with-header form is roughly 40% cheaper than the
     * equivalent JSON for that case while preserving accuracy on extraction
     * tasks (https://github.com/toon-format/toon).
     *
     * ```kotlin
     * printToon(listOf(
     *   mapOf("id" to 1, "name" to "Alice"),
     *   mapOf("id" to 2, "name" to "Bob"),
     * ))
     * // [2]{id,name}:
     * //   1,Alice
     * //   2,Bob
     * ```
     */
    fun printToon(value: Any?)

    /**
     * Report an error to the MCP client with stack trace.
     * Does not mark the execution as failed.
     *
     * Recommended for error handling - includes full stack trace in output.
     */
    fun printException(message: String, throwable: Throwable)

    /**
     * Report progress to the MCP client.
     * Messages are throttled to at most once per second to avoid overwhelming the connection.
     *
     * ```kotlin
     * progress("Starting analysis...")
     * // do work
     * progress("Processing file 1 of 10")
     * // more work
     * progress("Analysis complete")
     * ```
     */
    fun progress(message: String)

    /**
     * Capture a screenshot of the IDE frame and send it as image content in the MCP response.
     * The image and related artifacts are saved under the execution folder using fixed filenames:
     * - screenshot.png
     * - screenshot-tree.md
     * - screenshot-meta.json
     *
     * The fileName parameter is ignored to keep filenames stable across tools.
     *
     * @param fileName ignored; kept for compatibility (default: ide-screenshot.png)
     * @return absolute path to the saved screenshot, or null if capture failed
     */
    suspend fun takeIdeScreenshot(fileName: String = "ide-screenshot.png"): String?

    // ============================================================
    // IDE Utilities - Waiting
    // ============================================================

    /**
     * Wait for indexing to complete (smart mode). Runs automatically before your script under the
     * `modal=smart_non_modal` option (default); call it yourself in other modes or after triggering
     * indexing mid-script.
     *
     * Asserts the IDE is non-modal and FAILS the execution if a modal dialog is present (smart mode cannot
     * complete under a modal — it would otherwise hang). This is only a point-in-time wait: another
     * dumb-mode pass may begin before the next statement. Prefer smartReadAction { } for index-dependent
     * reads. After project import/sync/configuration, use Observation.awaitConfiguration(project) before
     * the indexed read.
     *
     * ```kotlin
     * // If you trigger indexing mid-script:
     * waitForSmartMode()
     * // Then keep indexed PSI work inside smartReadAction { ... }
     * ```
     */
    suspend fun waitForSmartMode()

    // ============================================================
    // IDE Utilities - Daemon Code Analysis
    // ============================================================

    /**
     * Check whether code highlighting is completed and up to date for the selected file in the editor.
     * This method returns a result instantly without waiting for any delay.
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor first
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Check analysis completed
     * val completed = isEditorHighlightingCompleted(file)
     * if (completed) {
     *     println("Analysis completed!")
     * }
     * ```
     */
    suspend fun isEditorHighlightingCompleted(file: VirtualFile): Boolean

    /**
     * Wait for the code analysis process to complete highlighting on the given file.
     * The file must be open in the editor for highlighting to work.
     *
     * @param file The virtual file to wait for analysis completion
     * @param timeout Maximum time to wait (default: 30 seconds)
     * @return true if highlighting completed, false if timeout occurred
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor first
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Wait for analysis
     * val completed = waitForEditorHighlighting(file)
     * if (completed) {
     *     println("Analysis complete!")
     * }
     * ```
     */
    suspend fun waitForEditorHighlighting(file: VirtualFile, timeout: Duration = 30.seconds): Boolean

    /**
     * Waits for the daemon analysis to complete and then returns highlights for the file.
     * Returns highlights with severity of at least WEAK_WARNING by default.
     *
     * **NOTE**: This method relies on the daemon code analyzer which may return stale results
     * if the IDE window is not focused (see GitHub issue #20). For reliable results regardless
     * of window focus, use [runInspectionsDirectly] instead.
     *
     * @param file The virtual file to get highlights for.
     * @param minSeverityValue Minimum severity value (default: WEAK_WARNING). Use HighlightSeverity.*.myVal.
     * @param timeout Maximum time to wait for analysis (default: 30 seconds).
     * @return List of HighlightInfo for the file, or empty list if timeout.
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     * // Open file in editor
     * withContext(Dispatchers.EDT) {
     *     FileEditorManager.getInstance(project).openFile(file, true)
     * }
     * // Get all warnings and errors
     * val highlights = getHighlightsWhenReady(file)
     * highlights.forEach { info ->
     *     println("${info.severity}: ${info.description}")
     * }
     * ```
     */
    suspend fun getHighlightsWhenReady(
        file: VirtualFile,
        minSeverityValue: Int = 200, // HighlightSeverity.WEAK_WARNING.myVal
        timeout: Duration = 30.seconds
    ): List<HighlightInfo>

    /**
     * Run inspections directly on a file without relying on the daemon code analyzer.
     *
     * This method bypasses the daemon's focus-dependent caching and runs inspections directly
     * using InspectionEngine.inspectEx(). It works reliably regardless of whether the IDE
     * window is focused or active.
     *
     * Use this method when you need accurate inspection results in automated/headless scenarios.
     *
     * @param file The virtual file to inspect
     * @param includeInfoSeverity Whether to include INFO-level problems (default: false)
     * @return Map of inspection tool ID to a list of ProblemDescriptors found
     *
     * ```kotlin
     * val file = findProjectFile("src/Main.kt") ?: error("File not found")
     *
     * val problems = runInspectionsDirectly(file)
     * problems.forEach { (toolId, descriptors) ->
     *     descriptors.forEach { problem ->
     *         println("[$toolId] ${problem.descriptionTemplate}")
     *     }
     * }
     * ```
     *
     * @see getHighlightsWhenReady for daemon-based highlights (requires window focus)
     */
    suspend fun runInspectionsDirectly(
        file: VirtualFile,
        includeInfoSeverity: Boolean = false
    ): Map<String, List<ProblemDescriptor>>

    // ============================================================
    // Modal Dialog Control
    //
    // The `modal` exec_code option picks a default stance; these methods let any
    // mode do the same things on demand:
    //  - smart_non_modal ≡ closeModalDialogs() + (require non-modal) + syncDocuments()
    //    + waitForSmartMode() + monitorAndCloseModalDialogs()
    //  - non_modal       ≡ (require non-modal) only
    //  - unleashed       ≡ nothing — call these yourself as needed
    // ============================================================

    /**
     * Close all currently-showing modal dialogs, deepest (top-most) first. Captures a diagnostic
     * screenshot and a thread dump (recorded with the execution) before closing. Does NOT fail the
     * execution — it is an explicit, on-demand sweep. Returns the number of dialogs closed.
     *
     * Use from `unleashed` / `non_modal` scripts to dismiss a leftover dialog on demand. `smart_non_modal`
     * runs this automatically before your script.
     *
     * ```kotlin
     * val closed = closeModalDialogs()
     * println("closed $closed modal dialog(s)")
     * ```
     */
    suspend fun closeModalDialogs(): Int

    /**
     * Poll for showing modal dialogs (~1s) for the rest of the execution. A modal dialog still showing at a
     * poll tick is closed (deepest-first), a screenshot + thread dump are captured, and the execution FAILS.
     * (A dialog that opens and closes between ticks is not observed.) Does NOT sweep dialogs already on
     * screen — call [closeModalDialogs] first for those. No-op if the monitor is already active. This is the
     * guard `smart_non_modal` enables automatically; call it explicitly from `non_modal` / `unleashed` to
     * get the same behavior. Call [allowModalDialog] before opening a dialog on purpose to disable the guard.
     */
    fun monitorAndCloseModalDialogs()

    /**
     * Disable the [monitorAndCloseModalDialogs] guard for the REST of this execution — it does NOT
     * auto-resume — so a modal dialog your script opens on purpose is neither closed nor treated as a
     * failure. To re-arm the guard afterwards, call [monitorAndCloseModalDialogs] again. No-op when the
     * monitor is not running. (Replaces the former `doNotCancelOnModalityStateChange()`.)
     *
     * ```kotlin
     * allowModalDialog()                 // about to show a refactoring confirmation on purpose
     * ActionManager.getInstance().getAction("ExtractMethod") // ...
     * ```
     */
    fun allowModalDialog()

    /**
     * Commit PSI changes + save all documents + refresh the VFS, so the script reads disk-consistent state.
     * Asserts the IDE is non-modal and FAILS the execution if a modal is present (these operations hang or
     * are unreliable under a modal — including one surfaced as a side effect). `smart_non_modal` runs this
     * automatically before your script.
     */
    suspend fun syncDocuments()

    // ============================================================
    // Read/Write Actions - Convenience Wrappers
    // ============================================================
    // These save you from importing com.intellij.openapi.application.readAction/writeAction

    /**
     * Execute a block under read lock.
     * Use for all PSI/VFS/index reads.
     *
     * ```kotlin
     * val psiFile = readAction {
     *     PsiManager.getInstance(project).findFile(virtualFile)
     * }
     * ```
     *
     * @see com.intellij.openapi.application.readAction
     */
    suspend fun <T> readAction(action: () -> T): T

    /**
     * Execute a block under write lock on EDT.
     * Use for all PSI/VFS/document modifications.
     *
     * ```kotlin
     * writeAction {
     *     document.insertString(0, "// Header\n")
     * }
     * ```
     *
     * @see com.intellij.openapi.application.writeAction
     */
    suspend fun <T> writeAction(action: () -> T): T

    /**
     * Execute a read action that automatically waits for smart mode.
     * Runs the action under IntelliJ's smart-mode read constraint, so the
     * indexed read is performed while the project is smart.
     *
     * ```kotlin
     * val classes = smartReadAction {
     *     KotlinClassShortNameIndex.get("MyClass", project, projectScope())
     * }
     * ```
     *
     * @see com.intellij.openapi.application.smartReadAction
     */
    suspend fun <T> smartReadAction(action: () -> T): T

    // ============================================================
    // Search Scopes - Convenience Methods
    // ============================================================

    /**
     * Get a search scope covering all project files (excludes libraries).
     *
     * ```kotlin
     * val scope = projectScope()
     * FilenameIndex.getFilesByName(project, "build.gradle.kts", scope)
     * ```
     */
    fun projectScope(): GlobalSearchScope

    /**
     * Get a search scope covering project files AND all libraries.
     *
     * ```kotlin
     * val scope = allScope()
     * JavaPsiFacade.getInstance(project).findClass("java.util.List", scope)
     * ```
     */
    fun allScope(): GlobalSearchScope

    // ============================================================
    // File Access - Convenience Methods
    // ============================================================

    /**
     * Find a VirtualFile by an absolute path.
     * Returns null if the file doesn't exist.
     *
     * ```kotlin
     * val vf = findFile("/path/to/file.kt")
     * if (vf != null) {
     *     val content = String(vf.contentsToByteArray())
     * }
     * ```
     */
    fun findFile(absolutePath: String): VirtualFile?

    /**
     * Find a PsiFile by an absolute path.
     * Requires a read action context or uses one internally.
     * Returns null if the file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findPsiFile("/path/to/file.kt")
     * println(psiFile?.name)
     * ```
     */
    suspend fun findPsiFile(absolutePath: String): PsiFile?

    /**
     * Find a VirtualFile relative to the project base path.
     * Returns null if the file doesn't exist.
     *
     * ```kotlin
     * val vf = findProjectFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    fun findProjectFile(relativePath: String): VirtualFile?

    /**
     * Find project files by a glob pattern relative to the project root.
     *
     * Uses Java NIO glob matching. Common glob patterns:
     * - starstar-slash-star.kt for all Kotlin files recursively
     * - src/main/starstar-slash-Demo-star.kt for demo classes
     * - star.md for markdown files in root
     *
     * Returns files sorted by absolute path for deterministic results.
     */
    suspend fun findProjectFiles(globPattern: String): List<VirtualFile>

    /**
     * Find a PsiFile relative to the project base path.
     * Returns null if the file doesn't exist or can't be parsed.
     *
     * ```kotlin
     * val psiFile = findProjectPsiFile("src/main/kotlin/MyClass.kt")
     * ```
     */
    suspend fun findProjectPsiFile(relativePath: String): PsiFile?

    // ============================================================
    // Multi-Site Literal Patch
    // ============================================================

    /**
     * Apply N literal-text substitutions across one or more files as a single
     * atomic, undoable command. This is the idiomatic replacement for a chain
     * of native `Edit(old, new)` tool calls — you ship only the data, the
     * plugin owns the threading and validation.
     *
     * Pre-flight (read action) verifies every `oldString` occurs exactly once
     * in its file; if any hunk is missing or non-unique, an
     * [ApplyPatchException] is thrown BEFORE any edit lands.
     *
     * Apply (write action + one `CommandProcessor.executeCommand`) runs every
     * hunk as one undo step. Multi-hunk edits in the same file are applied in
     * descending offset order automatically.
     *
     * The returned [ApplyPatchResult] carries per-hunk `path:line:column` info
     * for auditing; calling `println(result)` emits a human-readable summary.
     *
     * ```kotlin
     * val result = applyPatch {
     *     hunk("/abs/A.java", "old1", "new1")
     *     hunk("/abs/A.java", "old2", "new2")
     *     hunk("/abs/B.java", "oldX", "newX")
     * }
     * println(result)
     * // apply-patch: 3 hunks across 2 file(s) applied atomically.
     * //   [#0] /abs/A.java:17:5 (12→18 chars)
     * //   [#1] /abs/A.java:42:9 (5→9 chars)
     * //   [#2] /abs/B.java:88:13 (7→5 chars)
     * ```
     */
    suspend fun applyPatch(block: ApplyPatchBuilder.() -> Unit): ApplyPatchResult
}
