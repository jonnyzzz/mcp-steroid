/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import java.io.File
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.slf4j.LoggerFactory

/**
 * Hermetic, cross-platform tests for the [ProcessRunner] core.
 *
 * Every child process is the SAME JVM the tests run on ([ProcessUtilTestMain]); no shell,
 * `echo`, or `sleep` whose semantics differ across Linux/macOS/Windows. The per-OS CI matrix
 * runs these everywhere, so they must stay platform-agnostic.
 */
class ProcessRunnerTest {
    private val runner = ProcessRunner("PROCESS-UTIL-TEST", secretPatterns = emptyList())

    /** Absolute path to the `java` binary of the JVM the tests run on. */
    private val javaBinary: String =
        ProcessHandle.current().info().command().orElseGet {
            File(System.getProperty("java.home"), "bin/java").absolutePath
        }

    /** Build a request that launches [ProcessUtilTestMain] in the current JVM with [mode] + [args]. */
    private fun childRequest(mode: String, vararg args: String): RunProcessRequest =
        RunProcessRequest()
            .command(
                buildList {
                    add(javaBinary)
                    add("-cp")
                    add(System.getProperty("java.class.path"))
                    add(ProcessUtilTestMain::class.java.name)
                    add(mode)
                    addAll(args)
                }
            )
            .description("process-util child: $mode")

    @Test
    @Timeout(60)
    fun `stdout and stderr are both captured to memory`() {
        val result = childRequest("both").startProcess(runner).awaitForProcessFinish()
        assertEquals(0, result.exitCode) { "child should exit 0" }
        assertTrue(
            result.stdout.contains(ProcessUtilTestMain.STDOUT_MARKER),
            "stdout must be captured, got: ${result.stdout}",
        )
        assertTrue(
            result.stderr.contains(ProcessUtilTestMain.STDERR_MARKER),
            "stderr must be captured, got: ${result.stderr}",
        )
        // The two streams must stay separate.
        assertFalse(
            result.stdout.contains(ProcessUtilTestMain.STDERR_MARKER),
            "stderr content must not leak into stdout, got: ${result.stdout}",
        )
    }

    @Test
    @Timeout(60)
    fun `exit code zero is captured on success`() {
        val result = childRequest("exit", "0").startProcess(runner).awaitForProcessFinish()
        assertEquals(0, result.exitCode) { "child 'exit 0' should report 0" }
    }

    @Test
    @Timeout(60)
    fun `non-zero exit code is captured`() {
        val result = childRequest("exit", "37").startProcess(runner).awaitForProcessFinish()
        assertEquals(37, result.exitCode) { "child 'exit 37' should report 37" }
    }

    @Test
    @Timeout(60)
    fun `empty closed stdin does not hang a process that reads stdin`() {
        // #150 root cause: an open stdin pipe with no writer would block a child that reads
        // stdin forever. With the default empty stdin Flow the runner closes the pipe, the child
        // reads EOF immediately and exits. The @Timeout makes a regression fail fast instead of hanging.
        val result = childRequest("readline").startProcess(runner).awaitForProcessFinish()
        assertEquals(0, result.exitCode) { "child reading closed stdin should still finish, got: $result" }
        assertTrue(
            result.stdout.contains(ProcessUtilTestMain.NO_STDIN_MARKER),
            "child should have seen EOF on stdin, got: ${result.stdout}",
        )
    }

    @Test
    @Timeout(60)
    fun `stdin is delivered when provided`() {
        val result = childRequest("readline")
            .stdin("hello-from-stdin\n")
            .startProcess(runner)
            .awaitForProcessFinish()
        assertEquals(0, result.exitCode) { "child should finish, got: $result" }
        assertTrue(
            result.stdout.contains("child-read:hello-from-stdin"),
            "child should echo the stdin line, got: ${result.stdout}",
        )
    }

    @Test
    @Timeout(60)
    fun `timeout destroys a long-running process and returns within the bound`() {
        val timeout = Duration.ofSeconds(1)
        val startedAt = System.nanoTime()
        // Child would sleep 60s; the 1s timeout must destroyForcibly-kill it well before that.
        val result = childRequest("sleep", "60000")
            .withTimeout(timeout)
            .startProcess(runner)
            .awaitForProcessFinish()
        val elapsed = Duration.ofNanos(System.nanoTime() - startedAt)

        assertEquals(-1, result.exitCode) { "timed-out process should report -1, got: $result" }
        assertTrue(
            result.stderr.contains("Terminated by timeout"),
            "stderr should mention the timeout, got: ${result.stderr}",
        )
        // Generous upper bound (timeout + thread-join slack) — far below the child's 60s sleep,
        // so a broken timeout that blocks on the full sleep fails here.
        assertTrue(
            elapsed.toSeconds() < 30,
            "awaitForProcessFinish must return shortly after the ${timeout.toSeconds()}s timeout, took $elapsed",
        )
    }

    @Test
    @Timeout(60)
    fun `secret is redacted from logs but preserved in the result`() {
        val secret = "super-secret-token-9f8e7d6c"
        val secretRunner = ProcessRunner("PROCESS-UTIL-TEST", secretPatterns = listOf(secret))

        // Capture the runner's DEBUG output (it logs every output line) so we can assert the
        // secret is redacted in the logs but preserved in the returned ProcessResult.
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.process.ProcessRunner") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val previousLevel = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            // The child echoes the secret it reads on stdin back to its stdout; the runner logs
            // each captured output line at DEBUG, applying secret filtering to the logged copy.
            val result = secretRunner
                .startProcess(childRequest("readline").stdin("$secret\n"))
                .awaitForProcessFinish()

            assertEquals(0, result.exitCode) { "child should finish, got: $result" }

            // 1) The returned ProcessResult preserves the secret verbatim (filtering is log-only).
            assertTrue(
                result.stdout.contains(secret),
                "the returned ProcessResult must preserve the secret verbatim, got: ${result.stdout}",
            )

            // 2) The logged output must never contain the raw secret, and must show [REDACTED].
            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertFalse(
                logged.contains(secret),
                "the secret must be redacted from logged output, got logs:\n$logged",
            )
            assertTrue(
                logged.contains("[REDACTED]"),
                "logged output should show the [REDACTED] placeholder, got logs:\n$logged",
            )
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
    }

    @Test
    @Timeout(60)
    fun `blank secret patterns are ignored`() {
        val secretRunner = ProcessRunner("PROCESS-UTIL-TEST", secretPatterns = listOf("", "   "))
        val result = secretRunner.startProcess(childRequest("both")).awaitForProcessFinish()
        assertEquals(0, result.exitCode) { "blank secret patterns must not break the run, got: $result" }
        assertNotEquals("", result.stdout)
    }
}
