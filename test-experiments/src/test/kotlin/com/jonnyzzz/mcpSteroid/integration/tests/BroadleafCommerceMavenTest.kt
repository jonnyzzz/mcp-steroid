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
 * Experimental real-world project test for BroadleafCommerce.
 *
 * The default upstream branch is `develop-7.0.x`; [IntelliJProject.ProjectFromRemoteGit]
 * follows the remote HEAD and warms the bare repo cache before deploying the project.
 */
class BroadleafCommerceMavenTest {

    @Test @Timeout(value = 35, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method claude`() = agentRunsOneMavenTest(session.aiAgents.claude)

    @Test @Timeout(value = 35, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method codex`() = agentRunsOneMavenTest(session.aiAgents.codex)

    @Test @Timeout(value = 35, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method gemini`() = agentRunsOneMavenTest(session.aiAgents.gemini)

    private fun agentRunsOneMavenTest(agent: AiAgentSession) {
        val result = agent.runPrompt(buildBroadleafCommerceMavenPrompt(), timeoutSeconds = 2100).awaitForProcessFinish()
        result.assertExitCode(0, message = "BroadleafCommerce Maven test run for ${agent.displayName}")

        assertBroadleafCommerceMavenAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran a BroadleafCommerce Maven test")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime,IntelliJContainerOpts(
                "ide-agent",
                consoleTitle = "broadleaf",
                project = IntelliJProject.BroadleafCommerceProject,
            )).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
                projectJdkVersion = "17",
                compileProject = false,
            )
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
