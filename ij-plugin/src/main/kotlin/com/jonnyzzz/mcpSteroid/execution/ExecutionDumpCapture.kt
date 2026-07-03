/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.diagnostic.ThreadDumper
import com.intellij.diagnostic.dumpCoroutines
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private val log = Logger.getInstance("com.jonnyzzz.mcpSteroid.execution.ExecutionDumpCapture")

/**
 * Written into `coroutine-dump-<reason>.txt` when [dumpCoroutines] returns `null` — the kotlinx
 * debug probes are not installed (the `idea.enable.coroutine.dump` system property is off, or a
 * test application that never ran the IDE bootstrap). We do NOT install the probes ourselves:
 * `DebugProbes.install()` is `@ExperimentalCoroutinesApi` and the platform owns that lifecycle.
 */
const val COROUTINE_DUMP_UNAVAILABLE_NOTE: String =
    "coroutine dump unavailable: kotlinx debug probes not installed"

/**
 * Record a full JVM thread dump and a kotlin coroutine dump with the execution — the shared
 * diagnostics for every suspected-deadlock site (#215): the main script timeout in
 * [ScriptExecutor.executeCodeBlocks], the modality gate, and the modal-path sites in
 * [McpScriptContextImpl] (`syncDocuments-timeout`, `modal-monitor`, …).
 *
 * Files written into the execution's storage folder (`.idea/mcp-steroid/eid_<executionId>/`):
 * - `thread-dump-<reason>.txt` — [ThreadDumper.dumpThreadsToString] (public API; the combined
 *   [ThreadDumper.getThreadDumpInfo] is `@ApiStatus.Internal` and off-limits),
 * - `coroutine-dump-<reason>.txt` — [dumpCoroutines]`(stripDump = false)` (public top-level fun),
 *   or [COROUTINE_DUMP_UNAVAILABLE_NOTE] when the probes are not installed.
 *
 * Both dumps are captured as plain strings first (non-suspending calls), then written under
 * `withContext(NonCancellable + Dispatchers.IO)`. [NonCancellable] is REQUIRED: the timeout case
 * can race with an external cancellation of the whole tool call, and the storage write path does
 * `withContext(Dispatchers.IO)` internally, which throws on entry if the caller's job is already
 * cancelled.
 *
 * Never masks the caller's error handling: [CancellationException] is rethrown (Logger contract
 * for control-flow exceptions); any other failure is logged at WARN and swallowed, so the caller's
 * failure report still happens.
 */
suspend fun captureDiagnosticDumps(project: Project, executionId: ExecutionId, reason: String) {
    try {
        val threadDump = ThreadDumper.dumpThreadsToString()
        val coroutineDump = dumpCoroutines(stripDump = false) ?: COROUTINE_DUMP_UNAVAILABLE_NOTE
        val storage = project.executionStorage
        withContext(NonCancellable + Dispatchers.IO) {
            storage.writeCodeExecutionData(executionId, "thread-dump-$reason.txt", threadDump)
            storage.writeCodeExecutionData(executionId, "coroutine-dump-$reason.txt", coroutineDump)
        }
        log.info(
            "[$executionId] thread + coroutine dumps ($reason) written to " +
                storage.resolveExecutionDir(executionId).toAbsolutePath()
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[$executionId] failed to capture diagnostic dumps ($reason): ${e.message}")
    }
}
