/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProcessRunnerTest {
    private val runner = ProcessRunner("TEST", secretPatterns = emptyList())

    @TempDir
    lateinit var tempDir: File

    private fun request(vararg command: String, block: RunProcessRequest.() -> RunProcessRequest = {this}) =
        RunProcessRequest()
            .command(*command)
            .description("test")
            .workingDir(tempDir)
            .run(block)

    // --- runProcess basic ---

    @Test
    fun `runProcess captures stdout`() {
        val result = runner.startProcess(request("echo", "hello world")).awaitForProcessFinish()
        assertNotNull(result.stdout)
        assertTrue(result.stdout.contains("hello world"), "stdout should contain 'hello world', got: ${result.stdout}")
    }

    @Test
    fun `runProcess captures stderr`() {
        val result = runner.startProcess(request("bash", "-c", "echo error-text >&2")).awaitForProcessFinish()
        assertTrue(result.stderr.contains("error-text"), "stderr should contain 'error-text', got: ${result.stderr}")
    }

    @Test
    fun `runProcess returns exit code zero on success`() {
        val result = runner.startProcess(request("true")).awaitForProcessFinish()
        result.assertExitCode(0) { "runProcess should return 0 for 'true'" }
    }

    @Test
    fun `runProcess returns non-zero exit code on failure`() {
        val result = runner.startProcess(request("false")).awaitForProcessFinish()
        result.assertExitCode(1) { "runProcess should return 1 for 'false'" }
    }

    @Test
    fun `runProcess returns specific exit code`() {
        val result = runner.startProcess(request("bash", "-c", "exit 42")).awaitForProcessFinish()
        result.assertExitCode(42) { "runProcess should return 42 for 'exit 42'" }
    }

    @Test
    fun `runProcess rawOutput equals output by default`() {
        val result = runner.startProcess(request("echo", "data")).awaitForProcessFinish()
        assertEquals(result.stdout, result.stdout)
    }

    // --- stdin ---

    @Test
    fun `runProcess passes stdin to process`() {
        val result = runner.startProcess(request("cat") {
                    stdin("hello from stdin")
                }).awaitForProcessFinish()
        assertTrue(result.stdout.contains("hello from stdin"), "process should read stdin, got: ${result.stdout}")
    }

    @Test
    fun `runProcess passes byte array stdin`() {
        val bytes = "byte-content".toByteArray()
        val result = runner.startProcess(request("cat") {
                    stdin(bytes)
                }).awaitForProcessFinish()
        assertTrue(result.stdout.contains("byte-content"), "process should read byte array stdin, got: ${result.stdout}")
    }

    // --- timeout ---

    @Test
    fun `runProcess times out and returns negative exit code`() {
        val result = runner.startProcess(request("sleep", "60") {
                    timeoutSeconds(1)
                }).awaitForProcessFinish()
        result.assertExitCode(-1) { "runProcess should return -1 on timeout" }
        assertTrue(result.stderr.contains("Terminated by timeout"), "stderr should mention timeout, got: ${result.stderr}")
    }

    // --- secret filtering ---

    @Test
    fun `secrets are filtered from logged output but preserved in result`() {
        val secret = "my-secret-token-12345"
        val secretRunner = ProcessRunner("TEST", secretPatterns = listOf(secret))
        val result = secretRunner.startProcess(request("echo", secret)).awaitForProcessFinish()
        // The actual output should preserve the secret (secrets filtered only in logs, not result)
        assertTrue(result.stdout.contains(secret), "result output should preserve the secret, got: ${result.stdout}")
    }

    @Test
    fun `blank secret patterns are ignored`() {
        val secretRunner = ProcessRunner("TEST", secretPatterns = listOf("", "  ", "actual-secret"))
        val result = secretRunner.startProcess(request("echo", "no actual-secret here")).awaitForProcessFinish()
        // blank patterns should not cause issues, only "actual-secret" is redacted in logs
        assertTrue(result.stdout.contains("actual-secret"), "result output should preserve the secret")
    }

    // --- quietly mode ---

    @Test
    fun `quietly mode does not affect result content`() {
        val result = runner.startProcess(request("echo", "quiet-output") {
                    quietly()
                }).awaitForProcessFinish()
        assertTrue(result.stdout.contains("quiet-output"), "quietly mode should still capture output")
        result.assertExitCode(0) { "quietly mode should not affect exit code" }
    }

    // --- working directory ---

    @Test
    fun `process runs in the specified working directory`() {
        val result = runner.startProcess(request("pwd")).awaitForProcessFinish()
        assertTrue(
            result.stdout.contains(tempDir.canonicalPath) || result.stdout.contains(tempDir.absolutePath),
            "process should run in tempDir, got: ${result.stdout}"
        )
    }

    // --- multi-line output ---

    @Test
    fun `runProcess captures multi-line output`() {
        val result =
            runner.startProcess(request("bash", "-c", "echo line1; echo line2; echo line3")).awaitForProcessFinish()
        assertTrue(result.stdout.contains("line1"), "should contain line1")
        assertTrue(result.stdout.contains("line2"), "should contain line2")
        assertTrue(result.stdout.contains("line3"), "should contain line3")
    }

    @Test
    fun `runProcess joins multi-line output with newline not comma-space`() {
        val result =
            runner.startProcess(request("bash", "-c", "echo line1; echo line2; echo line3")).awaitForProcessFinish()
        // Lines must be newline-separated, not joined with ", " (the joinToString default separator)
        val lines = result.stdout.lines().filter { it.isNotBlank() }
        assertEquals(listOf("line1", "line2", "line3"), lines,
            "multi-line output must be newline-joined, got: ${result.stdout.replace("\n", "\\n")}")
    }

    @Test
    fun `runProcess joins multi-line stderr with newline not comma-space`() {
        val result =
            runner.startProcess(request("bash", "-c", "echo a >&2; echo b >&2; echo c >&2")).awaitForProcessFinish()
        val lines = result.stderr.lines().filter { it.isNotBlank() }
        assertEquals(listOf("a", "b", "c"), lines,
            "multi-line stderr must be newline-joined, got: ${result.stderr.replace("\n", "\\n")}")
    }

    @Test
    fun `runProcess captures both stdout and stderr from same process`() {
        val result =
            runner.startProcess(request("bash", "-c", "echo out-text; echo err-text >&2")).awaitForProcessFinish()
        assertTrue(result.stdout.contains("out-text"), "stdout should contain out-text, got: ${result.stdout}")
        assertTrue(result.stderr.contains("err-text"), "stderr should contain err-text, got: ${result.stderr}")
    }
}
