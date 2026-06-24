/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.escapeShellArgs
import com.jonnyzzz.mcpSteroid.process.StartedProcess
import com.jonnyzzz.mcpSteroid.process.startProcess

fun ContainerDriver.startProcessInContainer(args : ExecContainerProcessRequest.() -> ExecContainerProcessRequest) =
    startProcessInContainer(newExecInContainer().run(args))

private fun ContainerDriver.startProcessInContainer(
    request: ExecContainerProcessRequest
): StartedProcess {
    val command = buildList {
        add("docker")
        add("exec")
        if (request.detach) add("--detach")
        if (request.interactive) add("-i")
        request.user?.let {
            add("-u")
            add(it)
        }
        request.extraEnvVars.forEach { (key, value) ->
            add("-e")
            add("$key=$value")
        }
        request.workingDirInContainer?.let {
            add("-w")
            add(it)
        }
        add(containerId)
        add("bash")
        add("-c")
        add(escapeShellArgs(request.args))
    }

    return newRunOnHost(request)
        .command(command)
        .description(request.description ?: error("Missing description in $request"))
        .startProcess()
}
