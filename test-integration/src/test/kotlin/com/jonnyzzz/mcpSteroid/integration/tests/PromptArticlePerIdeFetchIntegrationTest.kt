/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.McpResourceUris
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * End-to-end proof that the prompt articles un-gated / made multi-IDE in the #81
 * (`ide/inspect-and-fix`) and #98 (un-gate test-debugging entry points) batch actually
 * resolve through the real plugin in a **non-IDEA** IDE.
 *
 * The `:prompts` `PerIdeAvailabilityContractTest` asserts the same availability statically
 * (it evaluates `IdeFilter.matches` directly), and the per-article `KtBlock` compilation
 * matrix proves the fences compile per IDE. This test closes the remaining gap: it boots a
 * real PyCharm / Rider / CLion and calls `steroid_fetch_resource`, where the product code
 * comes from the running IDE's `ApplicationInfo` — so a payload coming back (rather than
 * `ERROR: Resource not found`) is the runtime confirmation that the un-gating reaches the
 * shipped plugin and that the article's IDE-conditional sections render for that product.
 *
 * One IDE container per test (heavy; never run in parallel — see test-integration/AGENTS.md).
 */
class PromptArticlePerIdeFetchIntegrationTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `multi-IDE prompt articles resolve in PyCharm`() = assertMultiIdeArticlesResolveIn(IdeProduct.PyCharm)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `multi-IDE prompt articles resolve in Rider`() = assertMultiIdeArticlesResolveIn(IdeProduct.Rider)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `multi-IDE prompt articles resolve in CLion`() = assertMultiIdeArticlesResolveIn(IdeProduct.CLion)

    private fun assertMultiIdeArticlesResolveIn(product: IdeProduct) = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            IntelliJContainerOpts(
                dockerFileBase = product.dockerImageBase,
                consoleTitle = "prompt-fetch-${product.id}",
                distribution = IdeDistribution.Latest(product),
            ),
        )

        val failures = buildList {
            for (uri in McpResourceUris.multiIdePromptBatch) {
                val result = session.mcpSteroid.mcpFetchResource(uri = uri)
                val payload = result.stdout
                val resolved = result.exitCode == 0 &&
                    payload.isNotBlank() &&
                    !payload.contains("ERROR: Resource not found")
                if (!resolved) {
                    add("  $uri -> exit=${result.exitCode}, head='${payload.take(160).replace("\n", " ")}'")
                }
            }
        }

        check(failures.isEmpty()) {
            "These multi-IDE prompt articles did not resolve through steroid_fetch_resource in " +
                "${product.displayName} (${product.jetbrainsProductCode}) — the #81/#98 un-gating " +
                "did not reach the running plugin:\n" + failures.joinToString("\n")
        }
    }
}
