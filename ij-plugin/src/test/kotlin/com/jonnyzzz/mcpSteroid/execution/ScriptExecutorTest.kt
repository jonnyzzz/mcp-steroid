/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the ScriptExecutor.
 *
 * These tests verify that execution failures are reported quickly (no timeout waiting)
 * and that the execution flow handles errors correctly.
 *
 * NOTE: In the test environment, the Kotlin script engine may not be available
 * because the Kotlin plugin is not loaded. Tests should still pass by verifying
 * that failures are reported quickly with ERROR status.
 *
 * The ScriptExecutor uses ExecutionResultBuilder to collect output, so we use
 * a TestResultBuilder to capture the results.
 */
class ScriptExecutorTest : BasePlatformTestCase() {

    // Run tests off the EDT so `timeoutRunBlocking` doesn't park the dispatch
    // thread while ScriptExecutor's internals dispatch back to EDT.
    override fun runInDispatchThread(): Boolean = false

    private val executor: ScriptExecutor get() = project.service()

    private var executionCounter = 0
    private fun nextExecutionId() = ExecutionId("test-${++executionCounter}")

    /**
     * Test that when the script engine is not available, we get a fast error response.
     * This is the expected case in the test environment.
     */
    fun testScriptEngineNotAvailableReturnsFast(): Unit = timeoutRunBlocking(60.seconds) {
        val code = """
            println("Hello")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

        // Should complete quickly (not wait 60 seconds for timeout)
        // Either has messages (success) or failed (error) - but completes fast
        assertTrue(
            "Should complete with output or error",
            builder.messages.isNotEmpty() || builder.isFailed
        )
    }

    /**
     * This test verifies fast reporting for compilation errors.
     * Uses invalid Kotlin syntax that should fail immediately.
     *
     * Note: When the script engine is available, this should fail with a compilation error.
     * When the script engine is NOT available, it will also fail (script engine not available).
     * Either way, execution should complete quickly and not wait for a timeout.
     */
    fun testCompilationFailureFast(): Unit = timeoutRunBlocking(60.seconds) {
        val invalidCode = """
            please fail; this is invalid Kotlin code
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(invalidCode), builder)

        // Either failed, has messages, or has exceptions logged.
        // The 60s timeoutRunBlocking guards against a runaway compile loop;
        // a healthy compile-failure path returns in well under a second.
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that syntax errors are caught and reported immediately.
     *
     * Note: When the script engine is not available, this will fail with a different error.
     */
    fun testSyntaxErrorFast(): Unit = timeoutRunBlocking(60.seconds) {
        val syntaxErrorCode = """
            val x = // incomplete statement
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(syntaxErrorCode), builder)

        // Either failed, has messages, or has exceptions - verifies fast completion
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that top-level script body executes without execute {} wrapper.
     */
    fun testTopLevelScriptBody(): Unit = timeoutRunBlocking(60.seconds) {
        val noExecuteCode = """
            // Top-level script body
            val x = 1 + 2
            println(x)
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(noExecuteCode), builder)

        // Either failed (engine missing) or produced output
        // Either way, should complete quickly
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    fun testExecuteWrapperStillWorks(): Unit = timeoutRunBlocking(60.seconds) {
        val executeWrapperCode = """
            execute {
                val x = 40 + 2
                println(x)
            }
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(executeWrapperCode), builder)

        // Either failed (engine missing) or produced output
        assertTrue("Should complete with some output", builder.hasAnyOutput())
    }

    /**
     * Test that top-level statements are executed in order.
     * When the script engine is available, statements should run sequentially.
     * If it is not available, we should get an error.
     */
    fun testTopLevelStatementsOrder(): Unit = timeoutRunBlocking(60.seconds) {
                val multiCode = """
            println("First")
            println("Second")
            println("Third")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(multiCode), builder)

        // Either SUCCESS (if engine is available) or ERROR (if not)
        // If successful, verify FIFO order in output. Drop the pre-flight/run/
        // post-flight stage markers (`[PRE] …`, `[RUN] …`, `[POST] …`) the
        // executor emits around the script body so we assert only on the
        // user-script println output.
        val scriptOutput = builder.messages.filterNot {
            it.startsWith("[PRE]") || it.startsWith("[RUN]") || it.startsWith("[POST]")
        }
        if (!builder.isFailed && scriptOutput.isNotEmpty()) {
            assertTrue("Should have 3 messages", scriptOutput.size >= 3)
            assertEquals("First message", "First", scriptOutput[0])
            assertEquals("Second message", "Second", scriptOutput[1])
            assertEquals("Third message", "Third", scriptOutput[2])
        }
    }

    /**
     * Test that a runtime error in the script body is caught and reported.
     */
    fun testRuntimeErrorInScript(): Unit = timeoutRunBlocking(60.seconds) {
        val errorCode = """
            throw RuntimeException("Test runtime error")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(errorCode), builder)

        // Should fail
        assertTrue("Should fail", builder.isFailed)
    }

    // NOTE: a parallel `testElevatedModality...` test was attempted but
    // `LaterInvocator.enterModal` elevates EDT modality, and
    // `ScriptExecutor.commitAndSaveAllDocuments` dispatches via plain
    // `Dispatchers.EDT` (queue is gated on the current modality state),
    // so the executor deadlocks waiting for the EDT to accept its task.
    //
    // Likewise a `testNonModalDialogDuringExecuteDoesNotBlock` test that
    // showed a real `JFrame` was deleted: it produced a host-visible popup
    // during `:ij-plugin:test` (user-reported), and host-side test JVMs
    // now run headless (root `build.gradle.kts` sets `java.awt.headless=true`
    // on every Test task) so `JFrame.setVisible(true)` would throw
    // HeadlessException anyway.
    //
    // Coverage for the modal-DialogWrapper + dialog-killer path lives in
    // `test-integration/DialogKillerIntegrationTest` (Docker + Xvfb where
    // dialogs actually render and the killer can dispatch under
    // ModalityState.any()). Non-modal coverage belongs there too if needed.

    /**
     * Test that a timeout is reported correctly when execution takes too long.
     */
    fun testTimeoutReported(): Unit = timeoutRunBlocking(60.seconds) {
        val slowCode = """
            println("Starting")
            kotlinx.coroutines.delay(5000) // 5 seconds
            println("Done")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(slowCode, timeout = 1), builder)

        // Should fail due to timeout (or error if engine not available)
        assertTrue("Should fail", builder.isFailed)
    }

    /**
     * #215: when an execution times out (suspected deadlock), a native IntelliJ diagnostic
     * dump (JVM threads + kotlinx coroutines) must be written INTO the per-execution storage
     * folder (`.idea/mcp-steroid/eid_...`), and the tool result must mention ONLY that file's
     * path — never any dump content.
     *
     * The dump is captured from a `Job.invokeOnCompletion(onCancelling = true)` handler on the
     * `withTimeout` job, so it fires even if the body is wedged. This test wedges the body with a
     * non-cancellable `Thread.sleep` (not `delay`) under a short timeout, mirroring the #177
     * deadlock reproducer.
     *
     * Engine-tolerant per this class's convention: the file/message assertions apply only when
     * the run actually reached the timeout path (failure message carries "Execution timed out
     * after"); an environment without the script engine fails earlier with a different message.
     */
    fun testTimeoutWritesDiagnosticDumpToExecutionFolder(): Unit = timeoutRunBlocking(60.seconds) {
        val wedgedCode = """
            println("Starting")
            Thread.sleep(30000) // non-interruptible; outlives the 1s timeout
            println("Done")
        """.trimIndent()

        val executionId = nextExecutionId()
        val builder = TestResultBuilder()
        executor.executeWithProgress(executionId, testExecParams(wedgedCode, timeout = 1), builder)

        assertTrue("Should fail (timeout or engine-missing)", builder.isFailed)

        val failure = builder.failureMessage ?: ""
        if (failure.startsWith("Execution timed out after")) {
            val dumpFile = project.executionStorage
                .resolveExecutionPath(executionId, TimeoutDiagnosticDump.FILE_NAME)
            assertTrue(
                "Diagnostic dump must be written into the execution folder: $dumpFile",
                java.nio.file.Files.isRegularFile(dumpFile)
            )

            val dumpText = java.nio.file.Files.readString(dumpFile)
            // The native combined dump must include both a thread dump and the coroutine section.
            assertTrue(
                "Dump must contain thread-dump content:\n$dumpText",
                dumpText.contains("java.lang.Thread.State") || dumpText.contains("\" ") || dumpText.contains("at ")
            )
            assertTrue(
                "Dump must contain the coroutine-dump section header:\n$dumpText",
                dumpText.contains("Coroutine dump")
            )

            // The failure message mentions ONLY the path — no dump content leaks into the result.
            assertTrue(
                "Failure message must mention the dump-folder path:\n$failure",
                failure.contains(dumpFile.toString()) || failure.contains(dumpFile.parent.toString())
            )
            assertFalse(
                "Failure message must NOT contain dump content (thread/coroutine frames):\n$failure",
                failure.contains("Coroutine dump") || failure.contains("java.lang.Thread.State")
            )
        }
    }

    /**
     * #156: a messageless throwable (the bare NullPointerException from `!!`) must never
     * produce an empty "Unexpected error during execution: " FAILED line — the summary
     * must carry the exception class so the agent (and the hint engine) can react.
     *
     * Engine-tolerant per this class's convention: the assertion applies only when the
     * script actually reached runtime (the failure message carries the runtime prefix);
     * an environment without the script engine fails earlier with a different message.
     */
    fun testMesslessExceptionProducesNonEmptyFailure(): Unit = timeoutRunBlocking(60.seconds) {
        val code = """
            val value: String? = null
            value!!
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

        assertTrue("Execution of `null!!` must fail", builder.isFailed)
        val failure = builder.failureMessage ?: ""
        if (failure.startsWith("Unexpected error during execution:")) {
            // Runtime was reached: the summary must not be empty after the prefix.
            assertTrue(
                "FAILED line must name the exception class for messageless throwables:\n$failure",
                failure.contains("NullPointerException (no message)")
            )
        }
    }
}
