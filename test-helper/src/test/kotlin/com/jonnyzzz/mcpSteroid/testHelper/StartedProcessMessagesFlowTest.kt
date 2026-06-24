/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.process.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StartedProcessMessagesFlowTest {
    private val runner = ProcessRunner("TEST", secretPatterns = emptyList())

    @TempDir
    lateinit var tempDir: File

    private fun request(vararg command: String, block: RunProcessRequest.() -> RunProcessRequest = {this}) =
        RunProcessRequest()
            .command(*command)
            .description("test")
            .workingDir(tempDir)
            .run(block)

    @Test
    fun `messagesFlow emits stdout lines`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo hello; sleep 0.3; echo world; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .toList()
            }

            val texts = lines.map { it.line }
            Assertions.assertTrue(texts.any { it.contains("hello") }, "should contain hello, got: $texts")
            Assertions.assertTrue(texts.any { it.contains("world") }, "should contain world, got: $texts")
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow emits stderr lines`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo err-msg >&2; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDERR }
                    .toList()
            }

            val texts = lines.map { it.line }
            Assertions.assertTrue(texts.any { it.contains("err-msg") }, "should contain err-msg, got: $texts")
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow emits both stdout and stderr`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo out-line; echo err-line >&2; sleep 0.3")
        )
        try {
            val lines = withTimeout(5_000) {
                started.messagesFlow.toList()
            }

            val stdoutLines = lines.filter { it.type == ProcessStreamType.STDOUT }.map { it.line }
            val stderrLines = lines.filter { it.type == ProcessStreamType.STDERR }.map { it.line }

            Assertions.assertTrue(
                stdoutLines.any { it.contains("out-line") },
                "stdout should contain out-line, got: $stdoutLines"
            )
            Assertions.assertTrue(
                stderrLines.any { it.contains("err-line") },
                "stderr should contain err-line, got: $stderrLines"
            )
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow completes when process exits`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo done; sleep 0.2")
        )
        try {
            val result = withTimeout(5_000) {
                started.messagesFlow.toList()
            }

            // flow completed within timeout — process must have exited
            Assertions.assertNotNull(result)
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow streams lines incrementally`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "echo first; sleep 0.5; echo second; sleep 0.5; echo third; sleep 0.3")
        )
        try {
            val timestamps = mutableListOf<Pair<String, Long>>()
            val start = System.currentTimeMillis()

            withTimeout(10_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .collect { line ->
                        timestamps.add(line.line to (System.currentTimeMillis() - start))
                    }
            }

            // We should have received at least 2 lines
            Assertions.assertTrue(timestamps.size >= 2, "should have at least 2 stdout lines, got: $timestamps")

            // Lines should arrive at different times (not all batched together)
            // The first line should arrive noticeably before the last
            val firstTime = timestamps.first().second
            val lastTime = timestamps.last().second
            Assertions.assertTrue(
                lastTime - firstTime >= 200,
                "lines should arrive incrementally, but first=$firstTime, last=$lastTime (diff=${lastTime - firstTime}ms)"
            )
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `messagesFlow preserves line order`() = runBlocking {
        val started = runner.startProcess(
            request("bash", "-c", "for i in 1 2 3 4 5; do echo line-\$i; sleep 0.1; done; sleep 0.3")
        )
        try {
            val lines = withTimeout(10_000) {
                started.messagesFlow
                    .filter { it.type == ProcessStreamType.STDOUT }
                    .toList()
                    .map { it.line }
            }

            // Extract the numeric suffixes that were captured
            val numbers = lines.mapNotNull { line ->
                Regex("line-(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
            }

            Assertions.assertTrue(numbers.size >= 3, "should capture at least 3 numbered lines, got: $lines")

            // Verify ordering is preserved
            for (i in 1 until numbers.size) {
                Assertions.assertTrue(
                    numbers[i] > numbers[i - 1],
                    "lines should be in order, but got: $numbers"
                )
            }
        } finally {
            started.destroyForcibly()
        }
    }

    @Test
    fun `destroyForcibly terminates the process`() = runBlocking {
        val started = runner.startProcess(
            request("sleep", "60")
        )

        started.toString()

        started.destroyForcibly()

        // Give OS a moment to clean up
        Thread.sleep(200)

        // After destroy, exitCode should be available
        Assertions.assertNotNull(started.awaitForProcessFinish().exitCode, "process should have exited after destroyForcibly")
    }
}
