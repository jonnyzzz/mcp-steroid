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
 * Integration test: verifies an AI agent can navigate the Keycloak Maven
 * multi-module project, pick one fast unit-test method, and run it through
 * IntelliJ's Maven integration.
 *
 * Project under test: https://github.com/keycloak/keycloak (Java, Apache Maven,
 * many submodules including `core`, `common`, `crypto`, `services`, `quarkus`,
 * `testsuite`, etc. — heavy work for any agent).
 *
 * The agent is intentionally NOT told which test to run. The point is to confirm
 * the agent can:
 *   1. Discover the multi-module Maven layout via PSI/VFS
 *   2. Pick one fast unit test (NOT an integration / Quarkus / `testsuite` test)
 *   3. Drive Maven through IntelliJ (`MavenRunConfigurationType` + descriptor's
 *      `processHandler.exitCode`) — NOT through `Bash` / `./mvnw`
 *   4. Capture pass/fail and report it back via the marker contract
 *
 * Sibling-install path is exercised in practice: Keycloak's `core` module
 * depends on `keycloak-common` (and a few others). On a fresh checkout the
 * agent is expected to install only the missing sibling — through IntelliJ —
 * and retry the targeted test. The recipe at `mcp-steroid://skill/execute-code-maven`
 * documents this.
 */
class KeycloakMavenTest {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method claude`() = agentRunsOneMavenTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method codex`() = agentRunsOneMavenTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method gemini`() = agentRunsOneMavenTest(session.aiAgents.gemini)

    private fun agentRunsOneMavenTest(agent: AiAgentSession) {
        val prompt = buildKeycloakMavenPrompt()

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "keycloak maven test run for ${agent.displayName}")

        assertKeycloakMavenAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran a Keycloak Maven test")
    }

    companion object {

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak",
                project = IntelliJProject.KeycloakProject,
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
