/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostArchitecture
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
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
 * Same exercise as [YouTrackDbMavenTest], but pinned via [IdeDistribution.FromUrl]
 * to IntelliJ IDEA 2025.3 (build prefix `IU-253.*`). 253 is the IDE version the
 * plugin itself is compiled against (see CLAUDE.md → "Multi-Version Compatibility
 * Strategy"). Keeping a 253-pinned regression run validates that the
 * `mcp-steroid` plugin still operates correctly against the build-target IDE
 * even after the surrounding test fleet has moved its default to 261/262.
 *
 * Update the URL when a new 2025.3.x patch ships; do NOT bump it to 2026.x —
 * use the [YouTrackDbMaven261Test] sibling for that.
 */
class YouTrackDbMaven253Test {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method claude on 253`() = agentRunsOneMavenTest(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method codex on 253`() = agentRunsOneMavenTest(session.aiAgents.codex)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `agent runs one maven test method gemini on 253`() = agentRunsOneMavenTest(session.aiAgents.gemini)

    private fun agentRunsOneMavenTest(agent: AiAgentSession) {
        val prompt = buildYouTrackDbMavenPrompt()

        val result = agent.runPrompt(prompt, timeoutSeconds = 1500).awaitForProcessFinish()
        result.assertExitCode(0, message = "youtrackdb maven test run for ${agent.displayName} on 253")

        assertYouTrackDbMavenAgentSucceeded(result.stdout + "\n" + result.stderr)

        println("[TEST] Agent '${agent.displayName}' successfully ran a youtrackdb Maven test on pinned 253")
    }

    companion object {

        // Pinned IntelliJ IDEA 2025.3 (build prefix IU-253.*) — Linux tarball.
        // 253 is the build target of the mcp-steroid plugin itself; this sibling
        // keeps a regression run on the build-target IDE even after the default
        // STABLE channel has moved on. Bump the patch suffix when 2025.3.6+
        // ships; never bump to 2026.x — use the YouTrackDbMaven261Test sibling.
        private val PINNED_253_URL: String = when (resolveHostArchitecture()) {
            HostArchitecture.ARM64 -> "https://download.jetbrains.com/idea/ideaIU-2025.3-aarch64.tar.gz"
            HostArchitecture.X86_64 -> "https://download.jetbrains.com/idea/ideaIU-2025.3.tar.gz"
        }

        @JvmStatic
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "youtrackdb-253",
                project = IntelliJProject.YouTrackDbProject,
                distribution = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdea,
                    url = PINNED_253_URL,
                ),
            )).waitForProjectReady(buildSystem = BuildSystem.MAVEN)
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
