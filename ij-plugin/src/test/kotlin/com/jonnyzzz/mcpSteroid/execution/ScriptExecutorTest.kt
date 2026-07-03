/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Files
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
     * #213: `progressIndicator` is usable from script code (compiles through the CodeButcher
     * wrapping) and is NOT cancelled during a normal run.
     */
    fun testProgressIndicatorNotCancelledDuringNormalScript(): Unit = timeoutRunBlocking(60.seconds) {
        val code = """
            println("indicator cancelled: " + progressIndicator.isCanceled)
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code), builder)

        // A running script must never observe its own indicator as cancelled.
        assertFalse(
            "progressIndicator must not be cancelled during a normal run",
            builder.messages.any { it.contains("indicator cancelled: true") }
        )
        // Engine-tolerant per this class's convention: assert the printed value only
        // when the script actually ran.
        if (!builder.isFailed) {
            assertTrue(
                "script should have printed the indicator state:\n${builder.messages}",
                builder.messages.any { it.contains("indicator cancelled: false") }
            )
        }
    }

    /**
     * #213: the execution timeout must cancel the per-execution `progressIndicator`, so a
     * script blocked in an indicator-polling loop (the `InspectionEngine.inspectEx` shape —
     * `ProgressManager.runProcess(task, indicator)` installs the indicator on the thread and
     * the task polls `ProgressManager.checkCanceled()`) unwinds promptly instead of running
     * to the end of its work.
     *
     * Without the ScriptExecutor watcher wiring, the loop below would spin its full 120 s
     * hard cap and blow this test's 60 s harness bound — the test passing at all proves the
     * job-cancellation → indicator bridge fired and the loop unwound via
     * ProcessCanceledException.
     */
    fun testTimeoutCancelsProgressIndicatorAndUnwindsBlockingLoop(): Unit = timeoutRunBlocking(60.seconds) {
        val code = """
            import com.intellij.openapi.progress.ProgressManager

            ProgressManager.getInstance().runProcess(
                Runnable {
                    // 120s hard cap so a broken bridge fails the harness bound instead of hanging forever
                    val deadline = System.nanoTime() + 120_000_000_000L
                    while (System.nanoTime() < deadline) {
                        ProgressManager.checkCanceled()
                    }
                },
                progressIndicator,
            )
            println("UNREACHABLE: the blocking loop was not cancelled")
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(nextExecutionId(), testExecParams(code, timeout = 2), builder)

        assertTrue("Should fail (timeout or engine unavailable)", builder.isFailed)
        assertFalse(
            "the blocking loop must be cancelled, not run to completion:\n${builder.messages}",
            builder.messages.any { it.contains("UNREACHABLE") }
        )
        // Engine-tolerant: assert the timeout wording only when the script reached runtime
        // (an environment without the script engine fails earlier with a different message).
        val failure = builder.failureMessage ?: ""
        if (failure.contains("timed out")) {
            assertTrue(
                "timeout failure must carry the configured timeout: $failure",
                failure.contains("2 seconds")
            )
        }
    }

    /**
     * #215: a timed-out execution must leave both `thread-dump-timeout.txt` and
     * `coroutine-dump-timeout.txt` in its execution folder, and the FAILED line must carry the
     * absolute folder path ONLY — never the dump content.
     *
     * The script mirrors the #177 reproducer shape: a thread stuck in a non-interruptible sleep
     * loop keeps running past the timeout (so the dump taken inside the
     * TimeoutCancellationException handler still shows the stuck frames), while the script
     * coroutine suspends in `delay` so `withTimeout` can fire.
     *
     * Engine-tolerant per this class's convention: the dump-file assertions apply only when the
     * script actually reached runtime and timed out (an environment without the script engine
     * fails earlier with a different message and never enters the timeout branch).
     */
    fun testTimeoutWritesDumpsAndReportsFolderPathOnly(): Unit = timeoutRunBlocking(60.seconds) {
        // Unique per test method AND run: `nextExecutionId()` restarts at "test-1" for every test
        // (JUnit3 instantiates the class per method) while the storage dir may be shared — and this
        // test asserts on files inside the execution folder, so a clash would corrupt it.
        val executionId = ExecutionId("test-215-timeout-dumps-${System.nanoTime()}")
        val stuckThreadName = "mcp-steroid-215-stuck-thread"
        val code = """
            val stuck = Thread {
                // parkNanos ignores interrupts (returns without throwing) — the loop runs to its
                // deadline no matter what, mirroring a non-cancellable stuck block (#177 shape).
                val deadline = System.nanoTime() + 8_000_000_000L
                while (System.nanoTime() < deadline) {
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000_000L)
                }
            }
            stuck.name = "$stuckThreadName"
            stuck.isDaemon = true
            stuck.start()
            kotlinx.coroutines.delay(10_000)
        """.trimIndent()

        val builder = TestResultBuilder()
        executor.executeWithProgress(executionId, testExecParams(code, timeout = 1), builder)

        assertTrue("Should fail (timeout or engine unavailable)", builder.isFailed)
        val failure = builder.failureMessage ?: ""
        if (failure.contains("timed out")) {
            val dir = project.executionStorage.resolveExecutionDir(executionId).toAbsolutePath()

            val threadDumpFile = dir.resolve("thread-dump-timeout.txt")
            assertTrue("thread-dump-timeout.txt must exist in $dir", Files.exists(threadDumpFile))
            val threadDump = Files.readString(threadDumpFile)
            assertTrue(
                "the thread dump must show the still-running stuck thread:\n${threadDump.take(2000)}",
                threadDump.contains(stuckThreadName)
            )

            val coroutineDumpFile = dir.resolve("coroutine-dump-timeout.txt")
            assertTrue("coroutine-dump-timeout.txt must exist in $dir", Files.exists(coroutineDumpFile))
            assertTrue(
                "coroutine dump file must carry a dump or the unavailability note",
                Files.readString(coroutineDumpFile).isNotBlank()
            )

            // The FAILED line carries the absolute execution-folder path...
            assertTrue(
                "timeout failure must mention the dump folder path: $failure",
                failure.contains(dir.toString())
            )
            // ...and never the dump content.
            assertFalse(
                "timeout failure must not embed dump content: $failure",
                failure.contains("at java.lang.")
            )
            assertFalse(
                "the result messages must not embed the coroutine dump content:\n${builder.messages}",
                builder.messages.any { it.contains("---------- Coroutine dump") }
            )
        }
    }

    /**
     * #215: a failed dump capture must never mask the timeout report. Instead of a test-only
     * injection seam (banned), the dump write is made to fail for real: a directory squats the
     * `thread-dump-timeout.txt` filename, so the storage write throws. The execution must still
     * fail with the plain timeout message.
     */
    fun testDumpCaptureFailureDoesNotMaskTimeoutError(): Unit = timeoutRunBlocking(60.seconds) {
        // Unique id for the same reason as testTimeoutWritesDumpsAndReportsFolderPathOnly — this
        // test squats the dump filename with a directory, which must not leak into other tests.
        val executionId = ExecutionId("test-215-dump-failure-${System.nanoTime()}")
        val dir = project.executionStorage.resolveExecutionDir(executionId)
        Files.createDirectories(dir.resolve("thread-dump-timeout.txt"))

        val builder = TestResultBuilder()
        executor.executeWithProgress(
            executionId,
            testExecParams("kotlinx.coroutines.delay(10_000)", timeout = 1),
            builder,
        )

        assertTrue("Should fail (timeout or engine unavailable)", builder.isFailed)
        // Engine-tolerant: assert the timeout wording only when the script reached runtime.
        val failure = builder.failureMessage ?: ""
        if (failure.contains("timed out")) {
            assertTrue(
                "timeout must be reported even when the dump write fails: $failure",
                failure.contains("Execution timed out after 1 seconds")
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
