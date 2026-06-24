/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

import java.io.InputStream
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.process.ProcessRunner")

private fun String.truncate(maxLength: Int, ellipsis: String = "..."): String =
    if (length <= maxLength) this
    else take(maxLength - ellipsis.length) + ellipsis

//TODO: hide this class
data class ProcessResultValue(
    override val exitCode: Int,
    override val stdout: String,
    override val stderr: String,
) : ProcessResult


private fun RunProcessRequest.withDefaultLogPrefix(prefix: String) = if (this.logPrefix.isNullOrEmpty()) withLogPrefix(prefix) else this


fun RunProcessRequest.startProcess(processRunner: ProcessRunner): StartedProcess {
    return processRunner.startProcess(this)
}


/**
 * Utility for running processes with consistent logging.
 * All output is logged via SLF4J with prefixes for easy debugging.
 * Supports filtering secrets from log output.
 */
class ProcessRunner(
    private val logPrefix: String,
    private val secretPatterns: List<String>,
) {
    /**
     * Run a process using the request configuration and waits for it to complete
     * This is the primary method for running processes.
     * Secrets are filtered from log output but preserved in returned ProcessResult.
     *
     * @param request The process run request with all configuration
     */
    fun startProcess(request: RunProcessRequest): StartedProcess {
        return startProcessImpl(applyTemplate(request))
    }

    fun applyTemplate(request: RunProcessRequest) =  request.addSecretPatterns(secretPatterns).withDefaultLogPrefix(logPrefix)
}


fun RunProcessRequest.startProcess() : StartedProcess = startProcessImpl(this)

/**
 * Filter secrets from text, replacing them with REDACTED.
 */
private fun RunProcessRequest.filterSecrets(text: String): String {
    var result = text
    for (pattern in secretPatterns) {
        if (pattern.isNotBlank()) {
            result = result.replace(pattern, "[REDACTED]")
        }
    }
    return result
}

private fun startProcessImpl(request: RunProcessRequest): StartedProcessImpl {
    // Filter secrets from command line and description for logging
    val logPrefix = request.logPrefix

    run {
        val filteredCommand = request.args.map { request.filterSecrets(it) }
        val filteredDescription = request.filterSecrets(request.description ?: request.args.joinToString(" ") { it.truncate(20) })
        log.debug("[{}] {}", logPrefix, filteredDescription)
        log.debug("[{}] {}", logPrefix, filteredCommand)
    }

    val processBuilder = ProcessBuilder(request.args)
    processBuilder.directory(request.workingDir)
    processBuilder.environment().putAll(request.environment)
    processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
    processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)

    val process = processBuilder.start()

    val messagesChannel = Collections.synchronizedList(mutableListOf<ProcessStreamLine>())

    fun readOutput(stream: InputStream, prefix: String, type: ProcessStreamType) {
        try {
            stream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val filterSecrets = request.filterSecrets(line)
                    if (!request.quietly) {
                        log.debug("[{}] {}", prefix, filterSecrets)
                    }
                    messagesChannel.add(ProcessStreamLine(type, line))
                }
            }
        } catch (e: Exception) {
            log.debug("[{}] Error reading output: {}", prefix, e.message)
        }
    }

    // Thread for copying stdin to the process
    val stdinThread = thread(start = false, name = "$logPrefix-stdin-reader") {
        try {
            process.outputStream.use { output ->
                runBlocking(CoroutineName("$logPrefix-stdin")) {
                    @Suppress("BlockingMethodInNonBlockingContext")
                    request.stdin.collect {
                        output.write(it)
                        output.flush()
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("[{}] stdin copy error: {}", logPrefix, e.message)
            messagesChannel.add(
                ProcessStreamLine(
                    ProcessStreamType.INFO,
                    "Failed to send STDIN: ${e.message}\n" + e.stackTraceToString()
                )
            )
        }
    }

    val outputThread = thread(start = false, name = "$logPrefix-stdout") {
        readOutput(process.inputStream, "$logPrefix OUT", ProcessStreamType.STDOUT)
    }

    val errorThread = thread(start = false, name = "$logPrefix-stderr") {
        readOutput(process.errorStream, "$logPrefix ERR", ProcessStreamType.STDERR)
    }

    stdinThread.start()
    outputThread.start()
    errorThread.start()

    return StartedProcessImpl(
        request,
        process,
        messagesChannel,
        listOf(stdinThread, outputThread, errorThread)
    )
}

private class StartedProcessImpl(
    val request: RunProcessRequest,
    val process: Process,
    val messagesChannel: List<ProcessStreamLine>,
    val thread: List<Thread>,
) : StartedProcess {
    val pid: PID get() = process.PID()

    private val logPrefix get() = request.logPrefix

    override fun destroyForcibly() {
        process.destroyForcibly()
    }

    val exitCode: Int?
        get() = runCatching { process.exitValue() } .getOrNull()

    override val messagesFlow: Flow<ProcessStreamLine>
        get() = flow {
            var offset = 0
            while (true) {
                messagesChannel.drop(offset).forEach {
                    offset++
                    emit(it)
                }

                @Suppress("BlockingMethodInNonBlockingContext")
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    return@flow
                }
            }
        }

    private fun builder(type: ProcessStreamType) : String {
        return messagesChannel
            .filter { it.type == type }
            .joinToString(separator = "\n") { it.line }
    }

    val stdout: String
        get() = builder(ProcessStreamType.STDOUT)

    val stderr: String
        get() = builder(ProcessStreamType.STDERR)

    override fun toString(): String {
        return "StartedProcessImpl(pid=$pid, exitCode=$exitCode, output='$stdout', stderr='$stderr')"
    }

    private fun waitForThreads() {
        thread.forEach {
            runCatching {
                it.join(3_000)
                if (it.isAlive) {
                    log.debug("[{}] Waiting for process thread {}", logPrefix, it.name)
                    it.interrupt()
                    it.join(10_000)
                    if (it.isAlive) {
                        log.warn("[{}] Thread {} still alive after interrupt — giving up", logPrefix, it.name)
                    }
                }
            }
        }
    }

    override fun awaitForProcessFinish(): ProcessResult {
        val completed = process.waitFor(request.timeout.toMillis(), TimeUnit.MILLISECONDS)

        if (!completed) {
            process.destroyForcibly()
            // Close streams so reader threads leave blocking readLine() calls.
            closeStreamAfterTimeout("stdout", process.inputStream)
            closeStreamAfterTimeout("stderr", process.errorStream)
            closeStreamAfterTimeout("stdin", process.outputStream)
            waitForThreads()

            log.debug("[{}] Process is terminated by timeout after {}", logPrefix, request.timeout)
            return ProcessResultValue(-1, stdout, "Terminated by timeout\n${stderr}\n\n ERROR: Terminated by timeout")
        } else {
            waitForThreads()
            val exitCode = process.exitValue()
            log.debug("[{}] Process exited with code: {}", logPrefix, exitCode)
            return ProcessResultValue(exitCode, stdout, stderr)
        }
    }

    private fun closeStreamAfterTimeout(name: String, stream: AutoCloseable) {
        try {
            stream.close()
        } catch (e: Exception) {
            log.warn("[{}] Failed to close process {} stream after timeout: {}", logPrefix, name, e.message)
        }
    }
}
