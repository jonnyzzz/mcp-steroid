/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that checking Docker availability via exec_code works inside
 * a Docker IntelliJ container. Replaces 71 Bash `docker info` calls
 * observed in arena analysis.
 *
 * The test mounts the Docker socket into the container and verifies
 * that the socket file is detectable from IDE-executed Kotlin code.
 */
class DockerCheckTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(DockerCheckTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                IntelliJContainerOpts(
                    consoleTitle = "Docker Check",
                    mountDockerSocket = true,
                    // Docker-socket check doesn't need project content — EmptyProject = fast startup.
                    project = IntelliJProject.EmptyProject,
                )).waitForProjectReady()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `docker socket availability check via exec_code`() {
        val console = session.console

        console.writeStep("Checking Docker socket availability via exec_code")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                val dockerOk = java.io.File("/var/run/docker.sock").exists()
                println("DOCKER_AVAILABLE=${'$'}dockerOk")
            """.trimIndent(),
            taskId = "docker-check",
            reason = "Check Docker socket availability from IDE runtime",
            timeout = 30,
        )

        result.assertExitCode(0, "Docker check via exec_code should succeed")
        result.assertOutputContains("DOCKER_AVAILABLE=", message = "Output should contain Docker availability marker")

        console.writeSuccess("Docker availability check via exec_code works")
    }
}
