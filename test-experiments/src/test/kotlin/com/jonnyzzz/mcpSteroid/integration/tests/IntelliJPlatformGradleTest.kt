/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: verifies an AI agent can navigate the IntelliJ Platform
 * Gradle Plugin project, pick one fast unit-test method, and run it through
 * IntelliJ's Gradle integration.
 *
 * Project under test: https://github.com/JetBrains/intellij-platform-gradle-plugin
 * (Kotlin, Gradle Kotlin DSL, single root project + a `build-logic` includedBuild,
 * `jvmToolchain(17)` — fully compatible with the Temurin 17 already in the test
 * container, no exotic vendor pinning).
 *
 * The agent is intentionally NOT told which test to run. The point is to confirm
 * the agent can:
 *   1. Discover the Gradle layout via PSI/VFS (root + `build-logic`)
 *   2. Pick one fast plain Kotlin/JUnit test (e.g. `VersionTest`,
 *      `GradlePropertiesTest`) — NOT a `*PluginTestBase` integration test
 *   3. Drive Gradle through IntelliJ — `GradleRunConfiguration.isRunAsTest = true`
 *      + `(descriptor.executionConsole as SMTRunnerConsoleView).resultsViewer
 *      .testsRootNode.allTests` — NOT through `Bash` / `./gradlew`
 *   4. Report pass/fail through the marker contract
 *
 * Counterpart to [KeycloakMavenTest] / [YouTrackDbMavenTest], but for the Gradle
 * recipe documented at `mcp-steroid://skill/execute-code-gradle`. Picked over the
 * earlier Retrofit candidate because Retrofit pins per-JDK toolchains to Azul Zulu
 * 14/16, which the container's Temurin-only JDK install cannot satisfy on ARM64.
 */
class IntelliJPlatformGradleTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method claude`() = agentRunsOneGradleTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method codex`() = agentRunsOneGradleTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one gradle test method gemini`() = agentRunsOneGradleTest(session.aiAgents.gemini)

    private fun agentRunsOneGradleTest(agent: AiAgentSession) {
        val prompt = buildIntelliJPlatformGradlePrompt()

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "intellij-platform-gradle-plugin test run for ${agent.displayName}")

        assertIntelliJPlatformGradleAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran an IntelliJ Platform Gradle Plugin test")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "intellij-platform-gradle-plugin",
                project = IntelliJProject.IntelliJPlatformGradlePluginProject,
            )).waitForProjectReady(buildSystem = BuildSystem.GRADLE)
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Repo cache warming is handled automatically by IntelliJContainer.create()
            // based on the project's getRepoUrlForCache(). No explicit call needed here.
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
