/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.process

import java.io.File
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

data class RunProcessRequest(
    val workingDir: File? = null,
    val args: List<String> = listOf(),

    val logPrefix: String? = null,
    val description: String? = null,
    val quietly: Boolean = false,

    val timeout: Duration = Duration.ofSeconds(30),

    val stdin: Flow<ByteArray> = emptyFlow(),

    val environment: Map<String, String> = emptyMap(),

    val secretPatterns: List<String> = listOf(),
) {
    fun withWorkingDir(workingDir: File) = copy(workingDir = workingDir)
    fun withArgs(args: List<String>) = copy(args = args)

    fun withLogPrefix(logPrefix: String) = copy(logPrefix = logPrefix)
    fun withDescription(description: String) = copy(description = description)
    fun withTimeout(timeout: Duration) = copy(timeout = timeout)
    fun withQuietly(quietly: Boolean) = copy(quietly = quietly)
    fun withStdin(stdin: Flow<ByteArray>) = copy(stdin = stdin)
    fun withEnvironment(environment: Map<String, String>) = copy(environment = environment)

    fun addSecretPatterns(secretPatterns: List<String>) = copy(secretPatterns = (this.secretPatterns + secretPatterns).distinct())

    fun logPrefix(logPrefix: String) = withLogPrefix(logPrefix)
    fun workingDir(workingDir: File) = withWorkingDir(workingDir)
    fun command(command: List<String>) = withArgs(command)
    fun command(builder: MutableList<String>.() -> Unit) = command(buildList(builder))
    fun command(vararg command: String) = command(command.toList())
    fun description(description: String) = withDescription(description)
    fun timeoutSeconds(timeoutSeconds: Long) = withTimeout(Duration.ofSeconds(timeoutSeconds))
    fun quietly(quietly: Boolean) = withQuietly(quietly)
    fun quietly() = quietly(true)
    fun stdin(stdin: ByteArray) = withStdin(flowOf(stdin))
    fun stdin(stdin: String) = stdin(stdin.toByteArray())
    fun environment(environment: Map<String, String>) = withEnvironment(environment)
}
