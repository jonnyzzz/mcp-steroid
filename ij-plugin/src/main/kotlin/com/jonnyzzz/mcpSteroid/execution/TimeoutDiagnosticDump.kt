/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.diagnostic.COROUTINE_DUMP_HEADER
import com.intellij.diagnostic.ThreadDumper
import com.intellij.diagnostic.dumpCoroutines
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Captures a native IntelliJ diagnostic dump (JVM threads + kotlinx coroutines)
 * and stores it in the per-execution storage folder (`.idea/mcp-steroid/eid_...`).
 *
 * This deliberately reconstructs the *same* combined dump the platform builds in
 * [ThreadDumper.getThreadDumpInfo] (which is `@ApiStatus.Internal` and therefore
 * off-limits here) out of its two PUBLIC parts:
 *
 *  - [ThreadDumper.dumpThreadsToString] — full JVM thread dump (public, no `@ApiStatus`).
 *  - [dumpCoroutines] with `stripDump = false` — the kotlinx coroutine dump (public,
 *    no `@ApiStatus`); the platform installs the coroutine debug probes at IDE startup.
 *
 * They are concatenated with the platform's public [COROUTINE_DUMP_HEADER] so the file
 * is byte-for-byte the format the IDE itself writes to its log dir — except it lands in
 * the execution folder instead, satisfying the requirement that the dump live *with the
 * execution* and that the tool result mention only its path, never its content.
 *
 * The capture is a plain, fast, non-suspending, non-throwing operation on purpose: it is
 * invoked from a coroutine completion handler ([kotlinx.coroutines.Job.invokeOnCompletion])
 * that runs synchronously on the canceller's thread the moment the execution job starts
 * cancelling — so it observes the wedged/deadlocked frames even if the body never unwinds.
 */
object TimeoutDiagnosticDump {
    const val FILE_NAME: String = "diagnostic-dump-timeout.txt"

    private const val COROUTINE_DUMP_UNAVAILABLE =
        "coroutine dump unavailable: kotlinx debug probes not installed"

    private val log = Logger.getInstance(TimeoutDiagnosticDump::class.java)

    /**
     * Build the combined native dump text (threads + coroutines). Pure, non-throwing:
     * every failure is folded into the returned string so a broken dump never masks the
     * timeout report. Safe to call from a completion handler.
     */
    fun captureText(): String = buildString {
        appendLine(ThreadDumper.dumpThreadsToString())
        appendLine()
        appendLine(COROUTINE_DUMP_HEADER)
        // stripDump = false: keep the full coroutine frames — this is a suspected-deadlock
        // diagnostic, the "useless" kotlinx internal frames are exactly what we want here.
        val coroutines = try {
            dumpCoroutines(stripDump = false)
        } catch (t: Throwable) {
            "coroutine dump failed: ${t.message}"
        }
        appendLine(coroutines ?: COROUTINE_DUMP_UNAVAILABLE)
    }

    /**
     * Capture the combined dump and write it into the execution folder using a *blocking*
     * write — intended for a completion handler, which must not suspend. Returns the path
     * written, or `null` if capture/write failed (logged, never thrown).
     */
    fun writeBlocking(project: Project, executionId: ExecutionId): Path? {
        return try {
            val text = captureText()
            val path = project.executionStorage.resolveExecutionPath(executionId, FILE_NAME)
            path.writeText(text)
            path
        } catch (t: Throwable) {
            log.warn("[$executionId] failed to capture timeout diagnostic dump: ${t.message}", t)
            null
        }
    }
}
