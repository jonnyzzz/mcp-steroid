/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.DevrigMcpInstaller
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The devrig CLI distribution deployed into a throwaway Linux container (the shared `mcp-cli` image:
 * Debian + Temurin 25 JRE). devrig CLI integration tests run the launcher through THIS, never the host
 * launcher via a local `RunProcessRequest` — running devrig on the host would create the developer's real
 * `~/.mcp-steroid` (devrig's home is the hardcoded `user.home/.mcp-steroid`, no longer overridable). Inside
 * the container the home is the container-natural `/home/agent/.mcp-steroid`, fully isolated from the host.
 */
class DevrigCliContainer(
    val container: ContainerDriver,
    /** Absolute path of the devrig launcher inside the container. */
    val launcher: String,
) {
    /** Start `devrig mcp` in the container as a stdio MCP server, driven over the docker-exec stdin/stdout. */
    fun startMpc(lifetime: CloseableStack, resourceName: String = "devrig-mcp"): StdioMcpProcess =
        startStdioMcpProcess(lifetime, resourceName) { stdin: Flow<ByteArray> ->
            container.startProcessInContainer {
                args(launcher, "mcp")
                    .interactive()
                    .stdin(stdin)
                    .timeoutSeconds(300)
                    .description("devrig mcp (container)")
                    .quietly()
            }
        }

    /** Run the devrig launcher once with [args] in the container, returning the finished process result. */
    fun runDevrig(vararg args: String, timeoutSeconds: Long = 60): ProcessResult =
        container.startProcessInContainer {
            args(listOf(launcher) + args.toList())
                .timeoutSeconds(timeoutSeconds)
                .description("devrig ${args.joinToString(" ")}")
                .quietly()
        }.awaitForProcessFinish()

    /** Run `devrig mcp` once in the container feeding [stdin] (e.g. a fixed handshake), returning the result. */
    fun runMpcWithStdin(stdin: ByteArray, timeoutSeconds: Long = 60): ProcessResult =
        container.startProcessInContainer {
            args(launcher, "mcp")
                .interactive()
                .stdin(flowOf(stdin))
                .timeoutSeconds(timeoutSeconds)
                .description("devrig mcp (container, fixed stdin)")
                .quietly()
        }.awaitForProcessFinish()
}

/** Build the `mcp-cli` image, start a container, and copy the devrig dist in. Owned by [this] stack. */
fun CloseableStack.startDevrigCliContainer(): DevrigCliContainer {
    val dockerfile = ProjectHomeDirectory.requireProjectHomeDirectory()
        .resolve("test-helper/src/main/docker/mcp-cli/Dockerfile")
        .toFile()
    check(dockerfile.isFile) { "mcp-cli Dockerfile missing at $dockerfile" }
    val image = buildDockerImage(logPrefix = "MCP-CLI", dockerfilePath = dockerfile, timeoutSeconds = 600)
    val container = startDockerContainerAndDispose(this, StartContainerRequest().image(image))
    // `docker cp <installDir> <id>:/tmp` lands the dist at /tmp/<basename> ("devrig" = applicationName).
    val installDir = DevrigMcpInstaller.resolveInstallDir()
    container.copyToContainer(installDir, "/tmp")
    return DevrigCliContainer(container, "/tmp/${installDir.name}/bin/devrig")
}
