/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper.docker

import com.jonnyzzz.mcpSteroid.process.ProcessRunner
import com.jonnyzzz.mcpSteroid.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.process.startProcess
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Test for the custom Docker reaper cleanup mechanism.
 *
 * Containers are started as "fire and forget" (no CloseableStack lifetime) —
 * the reaper is solely responsible for cleanup. When the reaper socket closes
 * (on [DockerReaper.shutdown]), the reaper kills all registered containers and
 * then exits by itself.
 */
class DockerReaperTest {
    private val processRunner = ProcessRunner("TEST", emptyList())

    @BeforeEach
    fun setUp() {
        // Give the reaper a chance to kill any containers registered by a previous test run
        try {
            DockerReaper.shutdown()
        } catch (e: Exception) {
            System.err.println("DockerReaper.shutdown() failed during setUp: ${e.message}")
        }
    }

    @Test
    fun `reaper kills registered containers when socket closes`() {
        DockerReaper.start()
        val reaperId = DockerReaper.reaperContainerId
            ?: error("Reaper container ID not set after start()")

        // Fire and forget — reaper is the only cleanup mechanism, not a CloseableStack lifetime.
        // The container exits after the reaper detects socket close and kills it.
        val container = startDockerContainerAndForget(
            StartContainerRequest()
                .image("alpine:latest")
                .logPrefix("test-reaper")
                .entryPoint("sleep", "infinity")
        )
        DockerReaper.registerContainer(container)

        // Verify both the reaper container and the test container are running
        val reaperRunning = RunProcessRequest()
            .command("docker", "ps", "--filter", "id=$reaperId", "--format", "{{.ID}}")
            .description("Check reaper container")
            .timeoutSeconds(5)
            .quietly()
            .startProcess(processRunner)
            .assertExitCode(0) { "Failed to check reaper: $stderr" }
        assertTrue(reaperRunning.stdout.trim().isNotEmpty(), "Reaper container should be running")

        val containerRunning = RunProcessRequest()
            .command("docker", "ps", "-q", "--filter", "id=${container.containerId}")
            .description("Check test container before shutdown")
            .timeoutSeconds(5)
            .quietly()
            .startProcess(processRunner)
            .awaitForProcessFinish()
        assertTrue(containerRunning.stdout.trim().isNotEmpty(), "Test container should be running before shutdown")

        // Close the socket — reaper detects connection loss, kills registered containers, then exits
        DockerReaper.shutdown()

        // Poll until the test container exits (reaper uses a ~3 s ping timeout before acting)
        val deadline = System.currentTimeMillis() + 15_000
        var containerGone = false
        while (System.currentTimeMillis() < deadline) {
            val check = RunProcessRequest()
                .command("docker", "ps", "-q", "--filter", "id=${container.containerId}")
                .timeoutSeconds(5)
                .quietly()
                .startProcess(processRunner)
                .awaitForProcessFinish()
            if (check.stdout.trim().isEmpty()) {
                containerGone = true
                break
            }
            Thread.sleep(500)
        }
        assertTrue(containerGone, "Reaper should kill registered containers after socket close")
        println("[REAPER-TEST] Container ${container.containerId} cleaned up by reaper after socket close")
    }
}
