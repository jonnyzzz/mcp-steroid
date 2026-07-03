/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the shared dump-capture helper (#215) — the single implementation behind the
 * script-timeout dumps in [ScriptExecutor] and the modal-path diagnostics in
 * [McpScriptContextImpl] (`syncDocuments-timeout`, `modal-monitor`, the modality gate, …).
 */
class ExecutionDumpCaptureTest : BasePlatformTestCase() {

    // Off-EDT, same as ScriptExecutorTest: the helper dispatches to Dispatchers.IO.
    override fun runInDispatchThread(): Boolean = false

    /**
     * The helper writes both `thread-dump-<reason>.txt` and `coroutine-dump-<reason>.txt` into
     * the execution folder. This is the contract every modal-path call site
     * (`captureThreadDump(reason)`) now relies on — the thread-dump file keeps its historical
     * `thread-dump-<reason>.txt` name, and the coroutine dump is adopted alongside it.
     */
    fun testCaptureWritesThreadAndCoroutineDumpFiles(): Unit = timeoutRunBlocking(30.seconds) {
        val executionId = ExecutionId("dump-capture-test-1")

        captureDiagnosticDumps(project, executionId, "unit-test")

        val dir = project.executionStorage.resolveExecutionDir(executionId)

        val threadDumpFile = dir.resolve("thread-dump-unit-test.txt")
        assertTrue("thread-dump-unit-test.txt must exist in $dir", Files.exists(threadDumpFile))
        val threadDump = Files.readString(threadDumpFile)
        assertTrue(
            "a JVM thread dump must contain stack frames:\n${threadDump.take(500)}",
            threadDump.contains("at ")
        )

        val coroutineDumpFile = dir.resolve("coroutine-dump-unit-test.txt")
        assertTrue("coroutine-dump-unit-test.txt must exist in $dir", Files.exists(coroutineDumpFile))
        val coroutineDump = Files.readString(coroutineDumpFile)
        // Environment-tolerant: the kotlinx debug probes may or may not be installed in the test
        // JVM. Either way the file must be self-explaining — a real dump or the one-line note.
        assertTrue(
            "coroutine dump file must carry a dump or the unavailability note: '$coroutineDump'",
            coroutineDump.isNotBlank()
        )
    }

    /** A failed write (directory squatting the dump filename) must be swallowed, not thrown. */
    fun testCaptureFailureDoesNotThrow(): Unit = timeoutRunBlocking(30.seconds) {
        val executionId = ExecutionId("dump-capture-test-2")
        val dir = project.executionStorage.resolveExecutionDir(executionId)
        Files.createDirectories(dir.resolve("thread-dump-squatted.txt"))

        // Must complete normally — failures are logged at WARN inside, never rethrown.
        captureDiagnosticDumps(project, executionId, "squatted")
    }
}
