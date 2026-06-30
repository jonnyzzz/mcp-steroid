/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import java.time.Duration
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach


@Suppress("DATA_CLASS_COPY_VISIBILITY_WILL_BE_CHANGED_WARNING", "DataClassPrivateConstructor")
data class StartContainerRequest private constructor(
    val image: String? = null,
    val logPrefix: String? = null,
    val extraEnvVars: Map<String, String> = emptyMap(),
    val volumes: List<ContainerVolume> = emptyList(),
    val ports: List<ContainerPort> = emptyList(),
    val entryPoint: List<String> = emptyList(),
    val autoRemove: Boolean = true,
    val init: Boolean = false,
    val quietly: Boolean = false,
    val timeout: Duration = Duration.ofMinutes(5),
) {
    companion object {
        operator fun invoke(): StartContainerRequest = StartContainerRequest()
    }

    fun logPrefix(logPrefix: String) = copy(logPrefix = logPrefix)
    fun image(image: String) = copy(image = image)
    fun image(image: ImageDriver) = copy(image = image.imageId, logPrefix = image.logPrefix)
    fun extraEnvVars(extraEnvVars: Map<String, String>) = copy(extraEnvVars = extraEnvVars)
    fun volumes(volumes: List<ContainerVolume>) = copy(volumes = volumes)
    fun volumes(vararg volumes: ContainerVolume) = volumes(volumes.asList())
    fun ports(ports: List<ContainerPort>) = copy(ports = ports)
    fun ports(vararg ports: ContainerPort) = ports(ports.asList())
    fun entryPoint(args: List<String>) = copy(entryPoint = args)
    fun entryPoint(vararg args: String) = entryPoint(args.toList())
    fun autoRemove(autoRemove: Boolean) = copy(autoRemove = autoRemove)
    fun enableInit() = copy(init = true)
    fun timeout(timeout: Duration) = copy(timeout = timeout)
    fun quietly() = copy(quietly = true)
}

/**
 * The hostname a container uses to reach a service on the Docker host. Added as a host alias to every
 * container via `--add-host` below; reused wherever a host endpoint is rewritten for in-container use
 * (e.g. the agent LLM-gateway base URL — see AgentEndpoint).
 */
const val DOCKER_HOST_ALIAS = "host.docker.internal"

fun startDockerContainerAndForget(
    request: StartContainerRequest,
): ContainerDriver {
    val imageId = request.image ?: error("No image name")
    val logPrefix = request.logPrefix ?: error("No log prefix")

    val command = buildList {
        add("docker")
        add("run")
        add("-d")
        if (request.autoRemove) add("--rm")
        if (request.init) add("--init")
        add("--add-host=$DOCKER_HOST_ALIAS:host-gateway")

        // NOTE: we deliberately do NOT pass --user $(id -u):$(id -g). Tried
        // that as "standard docker-run hygiene" but the ide-agent image and
        // agent-CLI images bake a user `agent` with uid 1000 and pre-populate
        // /home/agent/.fluxbox, /home/agent/.m2, etc. owned by that uid.
        // Forcing a different host uid (TC agent's uid 999) via --user made
        // every `mkdir -p /home/agent/.fluxbox` fail with EACCES inside the
        // container.
        //
        // Bind-mount write correctness is handled separately:
        //   * host runDir gets setWritable(…, ownerOnly=false) before being
        //     mounted at /mcp-run-dir (see intelliJ-factory.kt) so the
        //     container's uid-1000 user can write there regardless of who
        //     owns the dir on the host
        //   * git's dubious-ownership check on the read-only /repo-cache
        //     mount is suppressed via a `git config --global --add
        //     safe.directory <path>` call in GitDriver.cloneFromCachedBare()
        //
        // Re-introduce --user only when the image contract is rewritten to
        // support arbitrary runtime uids (e.g. by chmodding $HOME entries
        // in the Dockerfile to 0777 or using numeric USER).

        request.extraEnvVars.forEach { (key, value) ->
            add("-e")
            add("$key=$value")
        }

        request.volumes.forEach { v ->
            add("-v")
            add("${v.host.absolutePath}:${v.guest}:${v.mode}")
        }

        request.ports.forEach { p ->
            add("-p")
            add(p.dockerPublishSpec())
        }

        add(imageId)

        // Add container command if specified
        addAll(request.entryPoint)
    }

    val result = RunProcessRequest()
        .command(command)
        .logPrefix(logPrefix)
        .description("Start container from $imageId with ${request.entryPoint}")
        .withTimeout(request.timeout)
        .quietly(request.quietly)
        .startProcess()
        .assertExitCode(0) {
            "Failed to start Docker container: $stderr"
        }

    val containerId = result.stdout.trim()
    if (containerId.isEmpty()) {
        throw IllegalStateException("Failed to start Docker container: ${result.stderr}")
    }

    println("[$logPrefix] Container started: $containerId")
    return ContainerDriver(
        logPrefix = logPrefix,
        containerId = containerId,
        startRequest = request,
    )
}
