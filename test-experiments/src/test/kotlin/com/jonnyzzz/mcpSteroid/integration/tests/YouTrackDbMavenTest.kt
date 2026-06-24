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
 * Integration test: verifies an AI agent can navigate a real-world Maven multi-module
 * project, pick one fast test method, and run it through IntelliJ's Maven integration.
 *
 * Project under test: https://github.com/JetBrains/youtrackdb (Java, Apache Maven,
 * many submodules — picks heavy work for any agent).
 *
 * The agent is intentionally NOT told which test to run. The point is to confirm
 * the agent can:
 *   1. Discover the multi-module Maven layout via PSI/VFS
 *   2. Pick one fast unit test (NOT an integration / Testcontainers / @IT test)
 *   3. Drive Maven through IntelliJ (`MavenRunConfigurationType` + `SMTRunnerEventsListener`)
 *      — NOT through `Bash` / `./mvnw` — proving the IDE-control value of MCP Steroid
 *   4. Capture pass/fail via `testsRoot.isPassed` and report it back
 */
class YouTrackDbMavenTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method claude`() = agentRunsOneMavenTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method codex`() = agentRunsOneMavenTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method gemini`() = agentRunsOneMavenTest(session.aiAgents.gemini)

    private fun agentRunsOneMavenTest(agent: AiAgentSession) {
        val prompt = buildYouTrackDbMavenPrompt()

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "youtrackdb maven test run for ${agent.displayName}")

        assertYouTrackDbMavenAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran a youtrackdb Maven test")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "youtrackdb",
                project = IntelliJProject.YouTrackDbProject,
            )).waitForProjectReady(buildSystem = BuildSystem.MAVEN)
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
