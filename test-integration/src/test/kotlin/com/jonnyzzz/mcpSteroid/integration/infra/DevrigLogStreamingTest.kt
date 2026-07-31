/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevrigLogStreamingTest {

    @Test
    fun `cleanup stops log scanner naturally without interruption or an uncaught exception`(@TempDir logsDir: Path) {
        val lifetime = CloseableStackHost("devrig-log-stream-test")
        val lineChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val console = ConsoleDriver(
            container = ContainerDriver(
                logPrefix = "devrig-log-stream-test",
                containerId = "not-used",
                startRequest = StartContainerRequest(),
            ),
            consoleFile = "/not-used",
            lineChannel = lineChannel,
        )
        Files.writeString(logsDir.resolve("devrig-cleanup.log"), "cleanup follower\n")
        val threadsBefore = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.threadId() }

        try {
            streamDevrigLogsToConsole(lifetime, logsDir.toFile(), console)
            val scanner = awaitThread(threadsBefore, "devrig-log-tee-scan")
            val follower = awaitThread(threadsBefore, "devrig-log-tee-devrig-cleanup.log")
            val uncaught = AtomicReference<Throwable?>()
            scanner.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }
            follower.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error -> uncaught.set(error) }

            awaitTimedWaiting(scanner)
            awaitTimedWaiting(follower)
            lifetime.closeAllStacks()

            assertFalse(scanner.isAlive, "devrig log scanner must terminate during cleanup")
            assertFalse(follower.isAlive, "devrig log follower must terminate during cleanup")
            assertFalse(scanner.isInterrupted, "normal cleanup must let the polling loop observe stopped without interrupting it")
            assertFalse(follower.isInterrupted, "normal cleanup must let the follower observe stopped without interruption")
            assertNull(uncaught.get(), "normal cleanup must not escape the scanner thread")
        } finally {
            lifetime.closeAllStacks()
            lineChannel.close()
        }
    }

    @Test
    fun `log streaming never follows symlinks outside the mounted log root`(@TempDir logsDir: Path) {
        val lifetime = CloseableStackHost("devrig-log-security-test")
        val lineChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val console = ConsoleDriver(
            container = ContainerDriver(
                logPrefix = "devrig-log-security-test",
                containerId = "not-used",
                startRequest = StartContainerRequest(),
            ),
            consoleFile = "/not-used",
            lineChannel = lineChannel,
        )
        val safeText = "SAFE_DEVRIG_LOG_LINE"
        val secretText = "HOST_SECRET_MUST_NOT_BE_STREAMED"
        val outsideSecret = logsDir.parent.resolve("outside-secret.txt")
        Files.writeString(outsideSecret, "$secretText\n")
        Files.writeString(logsDir.resolve("devrig-safe.log"), "$safeText\n")
        Files.createDirectory(logsDir.resolve("devrig-directory.log"))
        Files.createSymbolicLink(logsDir.resolve("devrig-secret.log"), outsideSecret)
        val threadsBefore = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.threadId() }

        try {
            streamDevrigLogsToConsole(lifetime, logsDir.toFile(), console)
            awaitThread(threadsBefore, "devrig-log-tee-devrig-safe.log")
            val output = awaitConsoleOutput(lineChannel, safeText) + collectConsoleOutput(lineChannel, 1_200)

            assertTrue(safeText in output, "a real regular devrig log must still be streamed")
            assertFalse(secretText in output, "a symlinked host file must never be streamed")
            val newThreads = Thread.getAllStackTraces().keys.filter { it.threadId() !in threadsBefore }
            assertFalse(
                newThreads.any { it.name == "devrig-log-tee-devrig-secret.log" },
                "the scanner must reject a symlink before starting a follower",
            )
            assertFalse(
                newThreads.any { it.name == "devrig-log-tee-devrig-directory.log" },
                "the scanner must reject a directory even when its name matches",
            )
        } finally {
            lifetime.closeAllStacks()
            lineChannel.close()
        }
    }

    @Test
    fun `log scanner retries after the mounted log directory temporarily disappears`(@TempDir logsDir: Path) {
        val lifetime = CloseableStackHost("devrig-log-retry-test")
        val lineChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val console = ConsoleDriver(
            container = ContainerDriver(
                logPrefix = "devrig-log-retry-test",
                containerId = "not-used",
                startRequest = StartContainerRequest(),
            ),
            consoleFile = "/not-used",
            lineChannel = lineChannel,
        )
        val recoveredText = "RECOVERED_DEVRIG_LOG_LINE"
        val threadsBefore = Thread.getAllStackTraces().keys.mapTo(mutableSetOf()) { it.threadId() }

        try {
            streamDevrigLogsToConsole(lifetime, logsDir.toFile(), console)
            val scanner = awaitThread(threadsBefore, "devrig-log-tee-scan")
            awaitTimedWaiting(scanner)

            Files.delete(logsDir)
            Thread.sleep(1_200)
            Files.createDirectory(logsDir)
            Files.writeString(logsDir.resolve("devrig-recovered.log"), "$recoveredText\n")

            awaitThread(threadsBefore, "devrig-log-tee-devrig-recovered.log")
            val output = awaitConsoleOutput(lineChannel, recoveredText)
            assertTrue(recoveredText in output, "the scanner must resume after a transient directory failure")
            assertTrue(scanner.isAlive, "a transient directory failure must not terminate the scanner")
        } finally {
            lifetime.closeAllStacks()
            lineChannel.close()
        }
    }

    private fun awaitThread(threadsBefore: Set<Long>, name: String): Thread {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            val found = Thread.getAllStackTraces().keys.firstOrNull { thread ->
                thread.threadId() !in threadsBefore && thread.name == name
            }
            if (found != null) return found
            Thread.sleep(10)
        }
        error("thread did not start: $name")
    }

    private fun awaitTimedWaiting(scanner: Thread) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (scanner.state == Thread.State.TIMED_WAITING) return
            Thread.sleep(10)
        }
        error("devrig log scanner did not enter its polling sleep; state=${scanner.state}")
    }

    private fun awaitConsoleOutput(channel: Channel<ByteArray>, expected: String): String {
        val deadline = System.nanoTime() + 3_000_000_000L
        val output = StringBuilder()
        while (System.nanoTime() < deadline) {
            output.append(drainConsoleOutput(channel))
            if (expected in output) return output.toString()
            Thread.sleep(10)
        }
        return output.toString()
    }

    private fun collectConsoleOutput(channel: Channel<ByteArray>, durationMillis: Long): String {
        val deadline = System.nanoTime() + durationMillis * 1_000_000L
        val output = StringBuilder()
        while (System.nanoTime() < deadline) {
            output.append(drainConsoleOutput(channel))
            Thread.sleep(10)
        }
        output.append(drainConsoleOutput(channel))
        return output.toString()
    }

    private fun drainConsoleOutput(channel: Channel<ByteArray>): String = buildString {
        while (true) {
            val received = channel.tryReceive()
            if (received.isFailure) break
            append(received.getOrThrow().toString(Charsets.UTF_8))
        }
    }
}
