/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.process

import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

@Timeout(120, unit = TimeUnit.SECONDS)
class ProcessRunTest {
    @TempDir
    lateinit var logsDir: Path

    private fun spec(
        vararg fixtureMode: String,
        timeout: Duration = 1.minutes,
        merge: Boolean = true,
        name: String? = null,
        workingDir: Path? = null,
        environment: Map<String, String> = emptyMap(),
    ) = ProcessRunSpec(
        command = processFixtureCommand(*fixtureMode),
        timeout = timeout,
        name = name,
        workingDir = workingDir,
        environment = environment,
        mergeStderrIntoStdout = merge,
        logsDir = logsDir,
    )

    private fun awaitTrue(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    private fun awaitProcessDead(pid: Long) =
        awaitTrue("process $pid to die") { ProcessHandle.of(pid).map { !it.isAlive }.orElse(true) }

    @Test
    fun `exit code zero propagates and files match the contract`() {
        val result = runProcess(spec("exit", "0"))
        assertEquals(0, result.exitCode)
        assertTrue(result.duration.isPositive()) { "duration: ${result.duration}" }

        val namePattern = Regex("process-java(\\.exe)?-\\d{8}-\\d{6}-\\d{3}-\\d+-\\d+-command\\.log")
        assertTrue(result.logs.commandLog.name.matches(namePattern)) {
            "unexpected command log name: ${result.logs.commandLog.name}"
        }
        assertTrue(result.logs.commandLog.exists())
        assertTrue(result.logs.stdoutLog.exists())
        assertNull(result.logs.stderrLog)

        val commandLog = result.logs.commandLog.readText()
        assertTrue(commandLog.contains("\"exit\"")) { "argv json expected in:\n$commandLog" }
        assertTrue(commandLog.contains(Regex("exit:\\s+0"))) { commandLog }
        assertTrue(commandLog.contains(Regex("pid:\\s+\\d+"))) { commandLog }
        assertTrue(commandLog.contains("duration:")) { commandLog }
    }

    @Test
    fun `nonzero exit code propagates without throwing`() {
        assertEquals(7, runProcess(spec("exit", "7")).exitCode)
    }

    @Test
    fun `environment keys are recorded sorted but values stay out of the command log`() {
        val result = runProcess(
            spec(
                "env", "MCP_STEROID_FIXTURE_TEST_ENV",
                environment = mapOf(
                    "MCP_STEROID_FIXTURE_TEST_ENV" to "secret-token-42",
                    "AAA_FIRST_KEY" to "another-secret",
                ),
            ),
        )
        assertTrue(result.logs.readStdout().contains("env:secret-token-42"))
        val commandLog = result.logs.commandLog.readText()
        val envKeysLine = commandLog.lines().single { it.startsWith("env-keys:") }
        assertTrue(envKeysLine.contains("MCP_STEROID_FIXTURE_TEST_ENV")) { commandLog }
        assertTrue(envKeysLine.indexOf("AAA_FIRST_KEY") < envKeysLine.indexOf("MCP_STEROID_FIXTURE_TEST_ENV")) {
            "env keys must be sorted:\n$commandLog"
        }
        assertFalse(commandLog.contains("secret-token-42")) { "env VALUE leaked into command log:\n$commandLog" }
        assertFalse(commandLog.contains("another-secret")) { "env VALUE leaked into command log:\n$commandLog" }
    }

    @Test
    fun `merged mode interleaves both streams into one file in write order`() {
        val result = runProcess(spec("exit", "0"))
        val output = result.logs.readStdout()
        val outFirst = output.indexOf("out-first")
        val errFirst = output.indexOf("err-first")
        val outSecond = output.indexOf("out-second")
        assertTrue(outFirst in 0 until errFirst && errFirst < outSecond) { "order broken:\n$output" }
        assertEquals("", result.logs.readStderr())
    }

    @Test
    fun `split mode separates the streams`() {
        val result = runProcess(spec("exit", "0", merge = false))
        val stdout = result.logs.readStdout()
        val stderr = result.logs.readStderr()
        assertTrue(stdout.contains("out-first") && stdout.contains("out-second") && !stdout.contains("err-first")) { stdout }
        assertTrue(stderr.contains("err-first") && !stderr.contains("out-first")) { stderr }
        assertTrue(result.logs.stderrLog!!.exists())
    }

    @Test
    fun `output is read back as utf8 with replacement for malformed bytes`() {
        val output = runProcess(spec("utf8")).logs.readStdout()
        assertTrue(output.contains("héllo-你好-😀")) { output }
        assertTrue(output.contains("b�d")) { output }
    }

    @Test
    fun `stdin is closed - child sees immediate eof`() {
        val result = runProcess(spec("stdin", timeout = 30.seconds))
        assertEquals(0, result.exitCode)
        assertTrue(result.logs.readStdout().contains("stdin-eof:0")) { result.logs.readStdout() }
    }

    @Test
    fun `timeout kills the child tree and throws with tail and kept files`() {
        val ex = assertThrows(ProcessTimeoutException::class.java) {
            runProcess(spec("grandchild", timeout = 10.seconds))
        }
        assertTrue(ex.pid > 0)
        assertEquals(10.seconds, ex.timeout)
        awaitProcessDead(ex.pid)

        val stdout = ex.logs.readStdout()
        val grandchildPid = Regex("grandchild-pid:(\\d+)").find(stdout)?.groupValues?.get(1)?.toLong()
        checkNotNull(grandchildPid) { "fixture did not report a grandchild pid:\n$stdout" }
        awaitProcessDead(grandchildPid)

        assertTrue(ex.outputTail.any { it.text.contains("grandchild-pid:") }) { "tail: ${ex.outputTail}" }
        assertTrue(ex.logs.commandLog.readText().contains("TIMEOUT")) { ex.logs.commandLog.readText() }
        assertTrue(ex.logs.stdoutLog.exists(), "files must be kept on timeout")
        assertFalse(ex.message!!.contains("grandchild-pid:"), "output text must not leak into the message")
    }

    @Test
    fun `interrupting the caller kills the child tree and restores the flag`() {
        var thrown: Throwable? = null
        var interruptedFlag = false
        val runner = Thread {
            try {
                runProcess(spec("grandchild", timeout = 2.minutes))
            } catch (t: Throwable) {
                thrown = t
                interruptedFlag = Thread.currentThread().isInterrupted
            }
        }
        runner.start()
        // The stdout log file is written live; wait until the child reported its grandchild.
        awaitTrue("fixture to start and report") {
            logsDir.listDirectoryEntries("process-*-stdout.log").any { it.readText().contains("grandchild-pid:") }
        }
        val stdout = logsDir.listDirectoryEntries("process-*-stdout.log").single().readText()
        val grandchildPid = Regex("grandchild-pid:(\\d+)").find(stdout)!!.groupValues[1].toLong()

        runner.interrupt()
        runner.join(30_000)
        assertFalse(runner.isAlive, "runner thread must finish")
        assertTrue(thrown is InterruptedException) { "expected InterruptedException, got $thrown" }
        assertTrue(interruptedFlag, "interrupt status must be restored")
        awaitProcessDead(grandchildPid)
        val commandLog = logsDir.listDirectoryEntries("process-*-command.log").single().readText()
        assertTrue(commandLog.contains("INTERRUPTED")) { commandLog }
    }

    @Test
    fun `missing executable throws ProcessStartException carrying logs`() {
        val ex = assertThrows(ProcessStartException::class.java) {
            runProcess(
                ProcessRunSpec(
                    command = listOf("mcp-steroid-no-such-binary-a6f1"),
                    timeout = 10.seconds,
                    logsDir = logsDir,
                ),
            )
        }
        assertTrue(ex.cause is java.io.IOException) { "cause: ${ex.cause}" }
        assertEquals(-1, ex.pid)
        assertTrue(ex.logs.commandLog.readText().contains("START-FAILED")) { ex.logs.commandLog.readText() }
    }

    @Test
    fun `working directory and environment reach the child`() {
        val dir = Files.createDirectory(logsDir.resolve("work")).toRealPath()
        val result = runProcess(spec("cwd", workingDir = dir))
        assertTrue(result.logs.readStdout().contains("cwd:$dir")) { result.logs.readStdout() }
    }

    @Test
    fun `readStdout is bounded and appends a marker that does not end in a digit`() {
        val result = runProcess(spec("flood", "5000"))
        val bounded = result.logs.readStdout(maxChars = 1_000)
        assertTrue(bounded.length in 1_000..1_100) { "length ${bounded.length}" }
        assertTrue(bounded.contains("[truncated by ProcessRunner]")) { bounded.takeLast(200) }
        assertFalse(bounded.last().isDigit(), "marker must not end in a digit (PID-parsing callers)")
        val full = result.logs.readStdout()
        assertTrue(full.contains("flood-4999-")) { "full read must not truncate here" }
        assertThrows(IllegalArgumentException::class.java) { result.logs.readStdout(maxChars = 0) }
    }

    @Test
    fun `readStdout cut inside a multi byte character does not corrupt the rest`() {
        val file = logsDir.resolve("multibyte-probe.txt")
        // "😀" is 4 UTF-8 bytes; cut after 2 of them.
        Files.write(file, "ab😀".toByteArray(Charsets.UTF_8))
        val logs = ProcessRunLogs(commandLog = file, stdoutLog = file, stderrLog = null)
        val cut = logs.readStdout(maxChars = 4)
        assertTrue(cut.startsWith("ab")) { cut }
        assertTrue(cut.contains('�')) { "expected replacement char at the cut: $cut" }
    }

    @Test
    fun `delete removes exactly this runs files - idempotent - reads return empty`() {
        val keep = runProcess(spec("exit", "0"))
        val victim = runProcess(spec("exit", "0", merge = false))
        victim.logs.delete()
        assertFalse(victim.logs.commandLog.exists())
        assertFalse(victim.logs.stdoutLog.exists())
        assertFalse(victim.logs.stderrLog!!.exists())
        victim.logs.delete() // idempotent
        assertEquals("", victim.logs.readStdout())
        assertEquals("", victim.logs.readStderr())
        assertTrue(keep.logs.commandLog.exists())
        assertTrue(keep.logs.stdoutLog.exists())
    }

    @Test
    fun `name is sanitized and an all-symbol name falls back to a usable tag`() {
        val weird = runProcess(spec("exit", "0", name = "we!rd/na me\\x"))
        val fileName = weird.logs.commandLog.name
        assertTrue(fileName.startsWith("process-")) { fileName }
        assertFalse(fileName.contains('/') || fileName.contains('\\') || fileName.contains('!') || fileName.contains(' ')) { fileName }

        val emoji = runProcess(spec("exit", "0", name = "😀🎉"))
        assertTrue(emoji.logs.commandLog.name.matches(Regex("process-[A-Za-z0-9._-]+-\\d{8}.*"))) {
            "all-symbol name must fall back: ${emoji.logs.commandLog.name}"
        }
    }

    @Test
    fun `concurrent runs in one jvm produce fully distinct files`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..4).map { pool.submit<ProcessRunResult> { runProcess(spec("exit", "0", merge = false)) } }
                .map { it.get(60, TimeUnit.SECONDS) }
            val allFiles = results.flatMap { listOfNotNull(it.logs.commandLog, it.logs.stdoutLog, it.logs.stderrLog) }
            assertEquals(12, allFiles.size)
            assertEquals(12, allFiles.map { it.name }.toSet().size) { "file names must be unique: $allFiles" }
            results.forEach { assertTrue(it.logs.readStdout().contains("out-first")) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `invalid specs fail at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRunSpec(command = emptyList(), timeout = 10.seconds, logsDir = logsDir)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRunSpec(command = listOf("x"), timeout = Duration.ZERO, logsDir = logsDir)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRunSpec(command = listOf("x"), timeout = (-5).seconds, logsDir = logsDir)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProcessRunSpec(command = listOf("x"), timeout = Duration.INFINITE, logsDir = logsDir)
        }
    }

    @Test
    fun `null device reads as empty on this os`() {
        FileInputStream(nullDevice()).use { assertEquals(-1, it.read()) }
    }
}
