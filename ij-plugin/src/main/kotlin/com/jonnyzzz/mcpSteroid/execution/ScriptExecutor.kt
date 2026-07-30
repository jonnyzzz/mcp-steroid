/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.jonnyzzz.mcpSteroid.koltinc.LineMapping
import com.jonnyzzz.mcpSteroid.mcp.ToolCallErrorException
import com.intellij.diagnostic.ThreadDumper
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

inline val Project.scriptExecutor: ScriptExecutor get() = service()

/**
 * Executes Kotlin scripts using IntelliJ's script engine.
 *
 * Execution flow:
 * 1. Script is compiled and evaluated to capture runnable script blocks
 * 2. Lambdas are executed in FIFO order inside a supervisorScope
 * 3. Any failure marks the whole execution as complete
 * 4. On timeout or cancellation, the Disposable is disposed and coroutine canceled
 *
 * Editing-guard pre/post-flight (former McpEditingGuard, inlined here as the
 * only caller):
 *
 *  - **Kill stuck modals.** Run [DialogKiller] to dismiss any modal dialogs
 *    left over from a previous step or background activity.
 *  - **Modality fail-fast.** Re-check via [DialogWindowsLookup]. If a modal
 *    is still up, abort with a clean tool error.
 *  - **Pre-flight commit + save + refresh.** Commit pending PSI edits, flush
 *    dirty documents, await VFS refresh. Guarantees the body sees disk-truth
 *    and any external write the body performs lands on a clean VFS.
 *  - **Run the body.** The actual script-blocks loop with the periodic dialog
 *    killer below.
 *  - **Post-flight refresh** in `finally` so the next agent step (compile,
 *    grep, follow-up edit) sees disk changes the body made (e.g. via Bash).
 *
 * Modality handling is driven by the `modal` option (see [ExecCodeParams.modal] / [ModalMode]); each
 * profile is sugar over the [McpScriptContext] methods:
 * - `smart_non_modal` (default): closeModalDialogs + require-non-modal + syncDocuments + waitForSmartMode,
 *   then start the modal monitor — a modal appearing mid-run is closed and the run fails.
 * - `non_modal`: require-non-modal only. `unleashed`: nothing.
 * - If the script intentionally shows a dialog (e.g. a refactoring confirmation), call
 *   `allowModalDialog()` on the script context BEFORE the action so the monitor leaves it alone.
 *
 * Non-modal dialogs DO NOT block execution — they neither pin the EDT nor
 * count for the modality check; the script runs to completion against the
 * non-modal-dialog-visible IDE state.
 *
 * IMPORTANT: This executor runs the captured suspend block inside a supervisorScope.
 * The script code gets the coroutine context implicitly - no runBlocking needed.
 */
@Service(Service.Level.PROJECT)
class ScriptExecutor(
    private val project: Project
) : Disposable {
    private val log = Logger.getInstance(ScriptExecutor::class.java)
    override fun dispose() = Unit

    /**
     * Executes a script with progress reporting and returns its output.
     * It is a suspending function that runs inside the caller's coroutine context.
     *
     * Fast failure: If the script engine is not available or compilation fails,
     * it returns immediately with an error - no waiting.
     */
    suspend fun executeWithProgress(
        executionId: ExecutionId,
        exec: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
    ) {
        // exec_code must never be driven from the EDT: the pre-flight dispatches
        // back to the EDT (isModalEdt / commit / VFS refresh) via withContext(EDT),
        // which deadlocks if the calling coroutine is itself parking the EDT (e.g.
        // runBlocking on the EDT, as a misconfigured BasePlatformTestCase does).
        // Fail fast with a clear message instead of hanging.
        ThreadingAssertions.assertBackgroundThread()

        log.info("Starting execution $executionId")

        coroutineScope {
            withContext(AppExecutorUtil.getAppExecutorService().asCoroutineDispatcher()) {
                val executionDisposable = Disposer.newDisposable(this@ScriptExecutor, "mcp-execution-$executionId")
                try {
                    executeWithProgressImpl(executionId, exec, resultBuilder, executionDisposable)
                }  finally {
                    Disposer.dispose(executionDisposable)
                }
            }
        }
    }

    private suspend fun CoroutineScope.executeWithProgressImpl(
        executionId: ExecutionId,
        exec: ExecCodeParams,
        resultBuilder: ExecutionResultBuilder,
        executionDisposable: Disposable,
    ) {
        val evalResult = project
            .codeEvalManager
            .evalCode(executionId, exec.code, resultBuilder) ?: return

        log.info("Running script block(s) for $executionId with timeout ${exec.timeout}s, modal=${exec.modal}")

        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = executionDisposable,
            resultBuilder = resultBuilder,
            // The modal-dialog monitor (monitorAndCloseModalDialogs) launches into this scope.
            executionScope = this,
        )

        // Pre-flight per `modal` profile. Each profile is sugar over the context APIs
        // (closeModalDialogs / syncDocuments / waitForSmartMode / monitorAndCloseModalDialogs),
        // which a script in any mode can also call on demand.
        //
        // Stage progress ([PRE]/[RUN]/[POST]) goes to the IDE log ONLY, never into the tool result
        // (#154): the result carries just the execution_id header plus the script's own output, so a
        // script that prints a single JSON document stays machine-parseable after stripping the
        // execution_id line. The same holds for ALL in-flight progress (indexing waits, compile
        // waits, multi-block progress): ExecutionManager.logProgress delivers it via MCP progress
        // notifications + idea.log + event storage, never the result content. Because the framing
        // no longer localizes a failure in the output, every stage failure names its step and
        // modality profile in the error itself (see [preFlight]).
        when (exec.modal) {
            ModalMode.SMART_NON_MODAL -> {
                preFlight(executionId, exec.modal, "close modal dialogs") { context.closeModalDialogs() }
                log.info("[$executionId] [PRE] require non-modal (modal=${exec.modal.wire})")
                requireNonModalOrFail(executionId, exec.modal)
                preFlight(executionId, exec.modal, "sync documents") { context.syncDocuments() }
                // Step name deliberately avoids the "smart mode" substring: the hint engine
                // (ExecutionSuggestionService) matches it and would append a smartReadAction TIP
                // to the INDEXING IN PROGRESS error, whose own instruction is "just keep polling".
                preFlight(executionId, exec.modal, "wait for indexing") { context.waitForSmartMode() }
                preFlight(executionId, exec.modal, "start modal-dialog monitor") { context.monitorAndCloseModalDialogs() }
            }

            ModalMode.NON_MODAL -> {
                log.info("[$executionId] [PRE] require non-modal (modal=${exec.modal.wire})")
                requireNonModalOrFail(executionId, exec.modal)
            }

            ModalMode.UNLEASHED -> {
                log.info("[$executionId] [PRE] unleashed — no modality checks")
            }
        }

        monitorExceptions(context, executionDisposable)

        log.info("[$executionId] [RUN] script (modal=${exec.modal.wire}, timeout=${exec.timeout}s)")
        executeCodeBlocks(exec, context, evalResult, executionId, resultBuilder)

        // Post-flight: re-sync to disk only for `smart_non_modal`, whose profile owns the document-
        // consistency contract. `non_modal` is intentionally start-gate-only and `unleashed` does nothing —
        // neither re-syncs (a fresh isModalEdt() read, since the body may have changed modality).
        if (exec.modal == ModalMode.SMART_NON_MODAL && !isModalEdt()) {
            log.info("[$executionId] [POST] sync documents (modal=${exec.modal.wire})")
            try {
                context.syncDocuments()
            } catch (e: ToolCallErrorException) {
                // Non-fatal by design — the script itself succeeded. But this is an anomaly the agent
                // must see (its edits may not have reached disk), not progress framing, so it stays in
                // the result as an explicit warning that names the step and profile.
                log.warn("[$executionId] [POST] sync documents skipped: ${e.message}")
                resultBuilder.logMessage(
                    "WARNING: post-flight 'sync documents' (modal=${exec.modal.wire}) was skipped: ${e.message}"
                )
            }
        }
    }

    /**
     * Runs one pre-flight step of the `modal` profile. The step's progress is logged to the IDE log
     * only — never added to the tool result (#154). When the step fails, it is rethrown with the
     * step name and modality profile prefixed, so the returned error is self-sufficient now that no
     * `[PRE]` framing in the output localizes the failing step. The original exception is chained
     * as the cause, so the stack trace logged upstream (ExecutionManager) still points at the real
     * throw site inside the step. Cancellation (CE/PCE) propagates untouched.
     */
    private suspend fun preFlight(
        executionId: ExecutionId,
        modal: ModalMode,
        step: String,
        action: suspend () -> Unit,
    ) {
        log.info("[$executionId] [PRE] $step (modal=${modal.wire})")
        try {
            action()
        } catch (e: ToolCallErrorException) {
            throw ToolCallErrorException("pre-flight '$step' (modal=${modal.wire}): ${e.message}")
                .apply { initCause(e) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            // Explicit defense for any PCE that does not extend CancellationException
            // (mirrors executeCodeBlocks): control-flow exceptions propagate untouched.
            throw e
        } catch (t: Throwable) {
            // Unexpected failures (e.g. a RuntimeException out of commitAllDocuments) must also
            // name the failing step + profile — ExecutionManager's generic handler reports the
            // wrapper's message and stack (with this cause chain) in the result.
            throw RuntimeException("pre-flight '$step' (modal=${modal.wire}): ${t.message}", t)
        }
    }

    /**
     * Fail the execution (with a screenshot) when the IDE is in an elevated-modality state and the
     * profile requires non-modal. Uses the shared [DialogWindowsLookup.isModalEdt] check, so the gate
     * agrees with the context APIs' non-modal asserts.
     */
    private suspend fun requireNonModalOrFail(executionId: ExecutionId, modal: ModalMode) {
        if (!isModalEdt()) return
        // Capture the same diagnostics the during-run monitor does (screenshot + thread dump) — a gate
        // failure often means a modal is stuck on a background process, where the thread dump is key.
        try {
            VisionService.getInstance(project).capture(executionId)
        } catch (e: Exception) {
            log.warn("Failed to capture modal screenshot for $executionId: ${e.message}", e)
        }
        try {
            project.executionStorage.writeCodeExecutionData(
                executionId, "thread-dump-modality-gate.txt", ThreadDumper.dumpThreadsToString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Failed to capture modality-gate thread dump for $executionId: ${e.message}")
        }
        throw ToolCallErrorException(
            "modal=${modal.name.lowercase()} requires a non-modal IDE, but a modal dialog/progress is present " +
                "and could not be cleared. Use modal=unleashed to run anyway (no PSI guarantees). " +
                "See the screenshot + thread dump under execution '${executionId.executionId}'."
        )
    }

    private fun CoroutineScope.monitorExceptions(
        context: McpScriptContextImpl,
        executionDisposable: Disposable
    ) {
        launch {
            service<ExceptionCaptureService>().exceptions.collect { ex ->
                context.println(buildString {
                    appendLine("=== IDE Exception Captured ===")
                    appendLine("Time: ${ex.timestamp}")
                    ex.pluginId?.let { appendLine("Plugin: $it") }
                    appendLine("Message: ${ex.message}")
                    appendLine("Stacktrace:")
                    append(ex.stacktrace)
                    appendLine("=== END ===")
                })
            }
        }.also {
            Disposer.register(executionDisposable) {
                it.cancel()
            }
        }
    }

    private suspend fun executeCodeBlocks(
        exec: ExecCodeParams,
        context: McpScriptContextImpl,
        evalResult: EvalResult,
        executionId: ExecutionId,
        resultBuilder: ExecutionResultBuilder
    ) {
        try {
            withTimeout(exec.timeout.seconds) {
                val capturedBlocks = evalResult.result
                for ((index, block) in capturedBlocks.withIndex()) {
                    yield()
                    if (capturedBlocks.size > 1) {
                        log.info("Executing block #${index + 1}/${capturedBlocks.size} for $executionId")
                        context.progress("Executing block ${index + 1} of ${capturedBlocks.size}...")
                    }
                    block(context)
                }
                log.info("Execution $executionId completed normally")
            }
        } catch (e: TimeoutCancellationException) {
            // Timeout - report as error (must be caught before CancellationException since it's a subclass)
            log.warn("Execution $executionId timed out: ${e.message}")
            resultBuilder.logRemappedException("Execution timed out", e, evalResult.lineMapping)
            // Name the phase explicitly: with the [RUN] framing gone from the result (#154), the
            // error itself must say the timeout hit the script body after pre-flight completed.
            resultBuilder.reportFailed(
                "Execution timed out after ${exec.timeout} seconds while running the script body " +
                    "(modal=${exec.modal.wire}; pre-flight completed)"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProcessCanceledException) {
            // Control-flow exception (Logger contract): rethrow, never report as a script
            // failure. On current platforms PCE extends CancellationException, so the CE
            // branch above already covers it — this branch is explicit defense for any
            // PCE that does not.
            throw e
        } catch (t: Throwable) {
            // #156: never report an empty error. A messageless throwable (e.g. the bare
            // NullPointerException from `!!`) used to produce "FAILED: Unexpected error
            // during execution: " — undiagnosable for the agent and invisible to the
            // hint engine (which matches on errorMessages, not the logged stack trace).
            val rawMessage = t.message?.takeIf { it.isNotBlank() }
                ?: "${t.javaClass.simpleName} (no message) — see stack trace above"
            log.warn("Unexpected error during execution $executionId: $rawMessage", t)
            val remappedMessage = evalResult.lineMapping.remapStackTrace(rawMessage)
            resultBuilder.logRemappedException("Unexpected error during execution: $remappedMessage", t, evalResult.lineMapping)
            resultBuilder.reportFailed("Unexpected error during execution: $remappedMessage")
        }
    }

    // Single source of truth, shared with the dialog killer (DialogWindowsLookup): the
    // gate and the killer must agree on what "modal" means. Yuriy's check — EDT under
    // ModalityState.any(), current() != nonModal().
    private suspend fun isModalEdt(): Boolean = dialogWindowsLookup().isModalEdt()

    /**
     * Logs an exception with stack trace line numbers remapped from wrapped-file coordinates
     * to user-code coordinates, so agents see meaningful line references.
     */
    private fun ExecutionResultBuilder.logRemappedException(
        message: String,
        throwable: Throwable,
        lineMapping: LineMapping,
    ) {
        val cleanTrace = lineMapping.cleanStackTrace(throwable.stackTraceToString())
        val text = "ERROR: $message\n$cleanTrace"
        logMessage(text)
    }
}
