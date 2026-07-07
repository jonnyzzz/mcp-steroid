/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigSteroidDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Live-IDE smoke for the MCP-as-CLI subcommands (epic #188): drives the deployed `devrig` binary as a
 * plain shell CLI against a real dockerized IDE and asserts the tools work end-to-end — the same bridge
 * `devrig mpc` uses, just invoked as `devrig <tool>`.
 *
 * OPT-IN: this lives in `:test-integration` (Docker + a full IDE container), which is excluded from the
 * default `:npx-kt:test` / root `test` runs. Invoke explicitly:
 * `./gradlew :test-integration:test --tests '*CliDevrigToolsIntegrationTest*'`.
 */
class CliDevrigToolsIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun listProjectsJsonExposesRoutingKey() {
        val projectName = waitForCliProjectName()
        val out = devrig("list_projects", "--json").assertExitCode(0) { "list_projects --json\nstdout=$stdout" }.stdout
        val envelope = json.parseToJsonElement(out).jsonObject
        assertEquals("list_projects", envelope["command"]!!.jsonPrimitive.content)
        val projects = envelope["data"]!!.jsonObject["projects"]!!.jsonArray
        val found = projects.map { it.jsonObject["project_name"]!!.jsonPrimitive.content }
        assertTrue(projectName in found, "discovered project_name $projectName must appear in $found")
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun executeCodeRunsInTheRunningIde() {
        val projectName = waitForCliProjectName()
        val marker = "DEVRIG_CLI_EXEC_OK"
        val result = devrig(
            "execute_code",
            "--project_name=$projectName",
            "--task_id=cli-smoke-exec",
            "--reason=verify devrig CLI routes execute_code to the running IDE",
            "--code=println(\"$marker\")",
        ).assertExitCode(0) { "execute_code\nstdout=$stdout\nstderr=$stderr" }
        assertTrue(result.stdout.contains(marker), "execute_code stdout must contain $marker\n${result.stdout}")
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun fetchResourceReturnsGuideForTheProject() {
        val projectName = waitForCliProjectName()
        // A bundled article that always exists; project_name upgrades to IDE-specific rendering.
        val out = devrig(
            "fetch_resource",
            "--uri=mcp-steroid://prompt/skill",
            "--project_name=$projectName",
        ).assertExitCode(0) { "fetch_resource\nstdout=$stdout" }.stdout
        assertTrue(out.isNotBlank(), "fetch_resource must print the guide markdown")
        assertTrue(out.contains("MCP Steroid") || out.contains("IntelliJ"), "unexpected guide content:\n${out.take(300)}")
    }

    // ------------------------------------------------------------------------------------------------

    /** Runs `devrig <args>` in the container and returns the finished process (stdout + exitCode). */
    private fun devrig(vararg args: String, timeoutSeconds: Long = 180): ProcessResult =
        session.scope.startProcessInContainer {
            args(listOf(launcher) + args)
                .timeoutSeconds(timeoutSeconds)
                .description("devrig ${args.joinToString(" ")}")
        }.awaitForProcessFinish()

    /** Polls `devrig list_projects --json` (each call re-runs discovery) until the IDE project appears. */
    private fun waitForCliProjectName(): String {
        repeat(80) {
            val result = devrig("list_projects", "--json")
            if (result.exitCode == 0) {
                val projects = json.parseToJsonElement(result.stdout)
                    .jsonObject["data"]?.jsonObject?.get("projects")?.jsonArray
                val first = projects?.firstOrNull()?.jsonObject?.get("project_name")?.jsonPrimitive?.content
                if (first != null) return first
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for `devrig list_projects` to discover the running IDE\n${session.diagnosticsSummary()}")
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val lifetime by lazy { CloseableStackHost(CliDevrigToolsIntegrationTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "devrig CLI tools smoke",
                aiMode = AiMode.NONE,
            )).waitForProjectReady()
        }
        private val launcher: String by lazy {
            DevrigSteroidDriver.deploy(session.scope, session.mcpSteroid).devrigCommand.command
        }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            session.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
