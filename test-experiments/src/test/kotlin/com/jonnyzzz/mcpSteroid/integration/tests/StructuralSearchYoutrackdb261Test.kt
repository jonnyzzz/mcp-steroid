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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * The MCP-only SSR skill audit on youtrackdb, pinned via
 * [IdeDistribution.FromUrl] to a specific 2026.1.x (build prefix `IU-261.*`)
 * Linux build. The default `IdeChannel.STABLE` resolves to "whatever the
 * current GA is" — pinning locks the 261 generation in place so any
 * 2026.1-specific SSR / Maven / indexing behavior keeps the same regression
 * coverage. Mirror of [YouTrackDbMaven261Test].
 *
 * Historical note: [StructuralSearchYoutrackdbTest] used to run this same
 * audit on the STABLE IDE; it is now the with/without-MCP A/B experiment
 * (Optional.get() audit), so this pinned sibling is the only remaining
 * consumer of `runStructuralSearchYoutrackdbAudit`.
 *
 * Update [PINNED_261_URL] to a newer 2026.1.x patch when one ships; do NOT
 * jump it to 2026.2 — that would defeat the purpose of this sibling.
 */
class StructuralSearchYoutrackdb261Test {

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb claude on 261`() = audit(session.aiAgents.claude)

    @Test @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `ssr audit on youtrackdb codex on 261`() = audit(session.aiAgents.codex)

    private fun audit(agent: AiAgentSession) =
        runStructuralSearchYoutrackdbAudit(session, agent, label = "ssr-youtrackdb-261")

    companion object {

        // Pinned IntelliJ IDEA 2026.1.1 (build prefix IU-261.*) — Linux tarball.
        // Bumping the patch number (e.g. to 2026.1.2) is fine; bumping to 2026.2
        // defeats the purpose of this sibling test. Add a 262 sibling instead.
        //
        // Pick the URL that matches the host architecture: Docker on Apple Silicon
        // runs Linux containers natively as arm64, and the x86-64 tarball fails to
        // start there with `rosetta error: failed to open elf at /lib64/ld-linux-x86-64.so.2`.
        private val PINNED_261_URL: String = when (resolveHostArchitecture()) {
            HostArchitecture.ARM64 -> "https://download.jetbrains.com/idea/ideaIU-2026.1.1-aarch64.tar.gz"
            HostArchitecture.X86_64 -> "https://download.jetbrains.com/idea/ideaIU-2026.1.1.tar.gz"
        }

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "ssr / youtrackdb-261",
                project = IntelliJProject.YouTrackDbProject,
                distribution = IdeDistribution.FromUrl(
                    product = IdeProduct.IntelliJIdea,
                    url = PINNED_261_URL,
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
