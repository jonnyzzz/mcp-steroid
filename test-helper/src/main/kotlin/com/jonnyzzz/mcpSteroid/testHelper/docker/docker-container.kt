/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.process.RunProcessRequest

/**
 * Base class for managing CLI sessions running inside Docker containers.
 * Provides common functionality for building images, starting/stopping containers,
 * and running commands.
 */
data class ContainerDriver(
    val logPrefix: String,
    val containerId: String,
    val startRequest: StartContainerRequest,
    private val wrapContainerExec: List<ExecContainerProcessRequest.() -> ExecContainerProcessRequest> = listOf(),
    private val wrapHostRun: List<RunProcessRequest.() -> RunProcessRequest> = listOf(),
) {
    val containerIdForLog get() = containerId.take(10)
    val volumes get() = startRequest.volumes

    fun log(message: String) {
        println("[$logPrefix] $message")
    }

    fun configureContainerExec(action: ExecContainerProcessRequest.() -> ExecContainerProcessRequest) =
        copy(wrapContainerExec = wrapContainerExec + action)

    fun configureHostRun(action: RunProcessRequest.() -> RunProcessRequest) =
        copy(wrapHostRun = wrapHostRun + action)

    fun withEnv(key: String, value: String) = configureContainerExec { addEnv(key, value) }

    fun newRunOnHost() = RunProcessRequest()
        .logPrefix(logPrefix)
        .apply(wrapHostRun)

    fun newRunOnHost(forContainerExec: ExecContainerProcessRequest) = newRunOnHost()
        .copy(
            logPrefix = forContainerExec.logPrefix?.takeIf { it.isNotBlank() } ?: logPrefix,
            description = forContainerExec.description,
            quietly = forContainerExec.quietly,
            timeout = forContainerExec.timeout,
            stdin = forContainerExec.stdin,
            secretPatterns = forContainerExec.secretPatterns,
        )

    fun newExecInContainer() = ExecContainerProcessRequest()
        .logPrefix(logPrefix)
        .apply(wrapContainerExec)

    private fun <R : Any> R.apply(wrappers: List<R.()->R>) = wrappers.fold(this) { r, wrapper -> r.wrapper() }

    override fun toString(): String {
        return "Container(containerId='$containerIdForLog', logPrefix='$logPrefix')"
    }

    companion object
}

