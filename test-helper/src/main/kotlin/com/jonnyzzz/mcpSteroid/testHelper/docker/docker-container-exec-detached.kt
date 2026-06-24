/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.time.Duration
import java.time.LocalDateTime.now
import java.time.format.DateTimeFormatter

//TODO: use builder args
fun ContainerDriver.runInContainerDetached(
    args: List<String>,
    workingDir: String? = null,
    extraEnvVars: Map<String, String> = emptyMap(),
): RunningContainerProcess {
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(now())
    val name = args.first().substringAfterLast("/")
    val logDir = "/tmp/run-$timestamp-$name"
    // Build the wrapper script that runs the real command,
    // captures its PID, and redirects output to files
    val innerCommand = escapeShellArgs(args)
    val wrapperScript = buildString {
        appendLine("#!/bin/bash")
        appendLine("$innerCommand >$logDir/stdout.log 2>$logDir/stderr.log &")
        appendLine($$"_PID=$!")
        appendLine($$"echo $_PID > $$logDir/pid")
        appendLine($$"wait $_PID")
        appendLine($$"echo $? > $$logDir/exitcode")
    }
    // Write the wrapper script into the container
    val scriptPath = "$logDir/run.sh"
    writeFileInContainer(scriptPath, wrapperScript, executable = true)
    // Run the wrapper script detached

    startProcessInContainer {
        this
            .args("bash", scriptPath)
            .description("In detached $innerCommand")
            .timeout(Duration.ofSeconds(10))
            .quietly()
            .workingDirInContainer(workingDir)
            .let { r -> extraEnvVars.entries.fold(r) { acc, (k, v) -> acc.addEnv(k, v) } }
            .detach(true)
    }.assertExitCode(0) { "Failed to start detached process '$name': $stderr" }

    log("Detached process '$name' started, stdout/stderr at $logDir")
    val process = RunningContainerProcess(this, name, logDir)
    require(process.isRunning()) { "Process '$name' is not running:\n${process.stdout}\n\n${process.stderr}" }
    return process
}

/**
 * Represents a process running inside a Docker container with output redirected to files.
 * Stdout, stderr, and PID are stored in [logDir] inside the container.
 * Use [readStdOut], [readStderr] to fetch content via `docker exec`.
 * Use [kill] to terminate the process if it is still running.
 */
//TODO: Seemps like the [StartedProcess]
class RunningContainerProcess(
    private val driver: ContainerDriver,
    /** Name/label for this background process */
    val name: String,
    /** Container-side directory holding stdout.log, stderr.log, and pid */
    val logDir: String,
) : ProcessResult {
    val stdoutPath: String get() = "$logDir/stdout.log"
    val stderrPath: String get() = "$logDir/stderr.log"
    private val pidPath: String get() = "$logDir/pid"
    private val exitCodePath: String get() = "$logDir/exitcode"

    override val exitCode: Int?
        get() = readExitCode()

    override val stdout: String
        get() = readStdOut()

    override val stderr: String
        get() = readStderr()

    fun printProcessInfo() {
        val exitCode = readExitCode()
        val isRunning = isRunning()
        val output = buildString {
            appendLine()
            if (isRunning) {
                appendLine("[$name] Process is till running...")
            } else {
                appendLine("[$name] Process is exited with code $exitCode!")
            }
            readStdOut().lineSequence().forEach { line -> appendLine("[$name OUT] $line") }
            appendLine()
            readStderr().lineSequence().forEach { line -> appendLine("[$name ERR] $line") }
            appendLine()
        }
        println(output)
    }

    /** Read current stdout content from the container. */
    fun readStdOut(timeoutSeconds: Long = 10): String {
        return driver.startProcessInContainer {
            this
                .args("cat", stdoutPath)
                .timeoutSeconds(timeoutSeconds)
                .quietly()
                .description("cat $stdoutPath")
        }.awaitForProcessFinish().stdout
    }

    /** Read current stderr content from the container. */
    fun readStderr(timeoutSeconds: Long = 10): String {
        return driver.startProcessInContainer {
            this
                .args("cat", stderrPath)
                .timeoutSeconds(timeoutSeconds)
                .quietly()
                .description("cat $stderrPath")
        }.awaitForProcessFinish().stdout
    }

    val pid: Long by lazy {
        driver.startProcessInContainer {
            this
                .args("cat", pidPath)
                .timeoutSeconds(5)
                .quietly()
                .description("cat $pidPath")
        }.assertExitCode(0) { "cat $pidPath failed" }.stdout.trim().toLong()
    }

    /**
     * Read the exit code of the process. Returns null if the process
     * is still running (exitcode file not yet written).
     */
    fun readExitCode(timeoutSeconds: Long = 5): Int? {
        val result = driver.startProcessInContainer {
            this
                .args("cat", exitCodePath)
                .timeoutSeconds(timeoutSeconds)
                .quietly()
                .description("cat $exitCodePath")
        }.awaitForProcessFinish()
        if (result.exitCode != 0) return null
        return result.stdout.trim().toIntOrNull()
    }

    /** Kill the background process if it is still running. */
    fun kill(signal: String = "TERM", timeoutSeconds: Long = 5) {
        driver.startProcessInContainer {
            this
                .args("kill", "-$signal", pid.toString())
                .timeoutSeconds(timeoutSeconds)
                .quietly()
                .description("kill -$signal $pid")
        }.awaitForProcessFinish()
    }

    /** Check if the process is still running. */
    fun isRunning(timeoutSeconds: Long = 5): Boolean {
        val result = driver.startProcessInContainer {
            this
                .args("kill", "-0", pid.toString())
                .timeoutSeconds(timeoutSeconds)
                .quietly()
                .description("kill -0 $pid")
        }.awaitForProcessFinish()
        return result.exitCode == 0
    }

    override fun toString(): String = "RunningContainerProcess(name=$name, logDir=$logDir)"
}
