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
/**
 * @return true when at least one dump file was written — callers use this to decide whether
 * the user-facing failure message may claim that diagnostics were stored (review #215:
 * never claim "dumps stored at <path>" when nothing was written).
 */
suspend fun captureDiagnosticDumps(project: Project, executionId: ExecutionId, reason: String): Boolean {
    try {
        val threadDump = ThreadDumper.dumpThreadsToString()
        val coroutineDump = dumpCoroutines(stripDump = false) ?: COROUTINE_DUMP_UNAVAILABLE_NOTE
        val storage = project.executionStorage
        // Each file is written independently: a squatting/failed thread-dump write must not
        // kill the coroutine dump (and vice versa). On a write failure the captured dump is
        // preserved in idea.log at WARN — matching the pre-#215 modal-path behavior of never
        // losing a captured dump.
        var written = 0
        withContext(NonCancellable + Dispatchers.IO) {
            for ((fileName, content) in listOf(
                "thread-dump-$reason.txt" to threadDump,
                "coroutine-dump-$reason.txt" to coroutineDump,
            )) {
                try {
                    storage.writeCodeExecutionData(executionId, fileName, content)
                    written++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("[$executionId] failed to write $fileName ($reason): ${e.message}; dump follows:\n$content")
                }
            }
        }
        if (written > 0) {
            log.info(
                "[$executionId] diagnostic dumps ($reason, $written file(s)) written to " +
                    storage.resolveExecutionDir(executionId).toAbsolutePath()
            )
        }
        return written > 0
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("[$executionId] failed to capture diagnostic dumps ($reason): ${e.message}")
        return false
    }
}
