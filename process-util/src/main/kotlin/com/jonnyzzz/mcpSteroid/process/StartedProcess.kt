/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class PID(val pid: String)

fun Process.PID() = PID(this.pid().toString())

enum class ProcessStreamType {
    STDOUT, STDERR, INFO
}

data class ProcessStreamLine(
    val type: ProcessStreamType,
    val line: String,
)

interface StartedProcess {
    /**
     * Returns flow of all messages of process output, each time
     */
    val messagesFlow: Flow<ProcessStreamLine>

    /**
     * Waits for process to finish with respect to process run timeout
     */
    fun awaitForProcessFinish() : ProcessResult

    /**
     * Forcibly destroys the underlying process.
     */
    fun destroyForcibly()
}

fun StartedProcess.assertExitCode(expectedExitCode: Int, message: ProcessResult.() -> String) =
    awaitForProcessFinish().assertExitCode(expectedExitCode, message)

/** Wraps a [ProcessResult] as a [StartedProcess] that has already finished. */
fun ProcessResult.asStartedProcess(): StartedProcess = object : StartedProcess {
    override val messagesFlow = emptyFlow<ProcessStreamLine>()
    override fun awaitForProcessFinish(): ProcessResult = this@asStartedProcess
    override fun destroyForcibly() {}
}
