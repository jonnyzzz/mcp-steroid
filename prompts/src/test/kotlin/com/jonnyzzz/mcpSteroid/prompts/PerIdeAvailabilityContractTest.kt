/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Per-IDE availability matrix for the entry-point prompt corpus (GitHub issue #98).
 *
 * `steroid_fetch_resource` resolves an article only when `article.filter.matches(context)`
 * is true for the caller's IDE (see `FetchResourceToolHandler`). An article whose every
 * ` ```kotlin``` ` fence is gated (e.g. `[IU]`) is silently unavailable in every other IDE —
 * PyCharm/GoLand/WebStorm/Rider agents get "Resource not found" for a URI the index article
 * advertised (issue #81's failure mode, discovered by a customer instead of the build).
 *
 * This test enforces the matrix for the articles an agent is *steered to*:
 * - every article in the `skill/` root, and
 * - every article URI referenced from the `prompt/skill` index article and from the
 *   `mcp-steroid-info` server-instructions prompt,
 *
 * must be available (filter matches) for every supported product code at the current
 * baseline version. The only exceptions are the explicit [EXPECTED_UNAVAILABLE] allowlist
 * below — each entry carries a one-line justification, and a companion test fails when an
 * entry becomes stale, so the list can only shrink.
 */
class PerIdeAvailabilityContractTest {

    private companion object {
        /** Matches `PromptsContext.Generic` and the baseline used by sibling contract tests. */
        private const val BASELINE_VERSION = 253

        /** All product codes supported by the prompt pipeline (see prompts/AGENTS.md). */
        private val SUPPORTED_PRODUCT_CODES = listOf("IU", "PY", "GO", "WS", "RD", "CL", "RM", "DB")

        /**
         * Articles allowed to be unavailable for some product codes, keyed by the URI path
         * (the part after `://`). Every entry MUST have a one-line justification. Entries
         * marked TODO(#98) are accidentally-gated articles the issue-98 Fix phase must
         * restructure (multi-IDE body, per-IDE fences) — do NOT add new TODO entries to
         * silence this test; restructure the article instead.
         */
        private val EXPECTED_UNAVAILABLE: Map<String, String> = mapOf(
            // Genuinely plugin-bound: Spring framework support ships only in IntelliJ IDEA Ultimate;
            // the article-level [IU] filter is correct.
            "skill/coding-with-intellij-spring" to "Spring plugin is bundled only in IDEA Ultimate",
            // Genuinely plugin-bound: the Gradle integration plugin (org.jetbrains.plugins.gradle)
            // is bundled only in IDEA among the supported product codes; every fence needs it.
            "skill/execute-code-gradle" to "Gradle plugin is bundled only in IDEA",
            // Genuinely plugin-bound: the Maven integration plugin (org.jetbrains.idea.maven)
            // is bundled only in IDEA among the supported product codes; every fence needs it.
            "skill/execute-code-maven" to "Maven plugin is bundled only in IDEA",
            // TODO(#98): article-level [IU] gate hides a JUnit/Gradle-flavored walkthrough whose
            // debugger workflow is platform-generic — restructure with per-IDE branches like
            // test/demo-debug-test instead of gating the whole article.
            "ide/demo-debug-test" to "TODO(#98) accidentally gated: Java-flavored demo, generic debugger flow",
        )

        /** Same pattern as the generated `ResourceUriValidationTest`. */
        private val URI_PATTERN = Regex("""mcp-steroid://[\w-]+(?:/[\w-]+)*""")
    }

    private val articlesByUri: Map<String, ArticleBase> = ResourcesIndex().roots
        .flatMap { it.value.articles.values }
        .associateBy { it.uri }

    /**
     * The audited corpus: every `skill/` root article plus every existing article referenced
     * (by full URI) from the `prompt/skill` index article and from `mcp-steroid-info`.
     */
    private fun candidateArticles(): Map<String, ArticleBase> {
        val skillRoot = ResourcesIndex().roots["skill"]
            ?: error("ResourcesIndex has no 'skill' root — folder renamed?")

        val referencedText = buildString {
            val skillIndex = SkillPromptArticle()
            appendLine(skillIndex.description.readPrompt())
            // Read all parts unfiltered so references hidden behind IDE conditionals are audited too.
            for (part in skillIndex.parts) appendLine(part.readPrompt())
            for (item in skillIndex.seeAlsoItems) appendLine(item.text)
            appendLine(McpSteroidInfoPrompt().readPrompt())
        }

        val referenced = URI_PATTERN.findAll(referencedText)
            .map { it.value }
            // Folder-prefix mentions like `mcp-steroid://ide` (placeholders for `ide/<id>`) are
            // not articles; full-URI validity is enforced by the generated ResourceUriValidationTest.
            .mapNotNull { articlesByUri[it] }

        return (skillRoot.articles.values.asSequence() + referenced)
            .associateBy { it.uri }
    }

    private fun unavailableCodes(article: ArticleBase): List<String> =
        SUPPORTED_PRODUCT_CODES.filter { code ->
            !article.filter.matches(PromptsContext(code, BASELINE_VERSION))
        }

    private fun uriPath(article: ArticleBase): String = article.uri.substringAfter("://")

    @Test
    fun testEntryPointCorpusIsAvailableForEverySupportedIde() {
        val candidates = candidateArticles()
        assertTrue(candidates.isNotEmpty(), "Candidate set is empty — index parsing broke")

        val violations = mutableListOf<String>()
        for (article in candidates.values.sortedBy { it.uri }) {
            val path = uriPath(article)
            if (path in EXPECTED_UNAVAILABLE) continue
            val missing = unavailableCodes(article)
            if (missing.isNotEmpty()) {
                violations.add("${article.uri}: unavailable for ${missing.joinToString(", ")}")
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Articles advertised by the skill index / server instructions are filtered out for some IDEs " +
                "(steroid_fetch_resource will report them unavailable — issue #98 / #81 failure mode).\n" +
                "Fix the article (add non-gated fences or per-IDE branches); only genuinely " +
                "plugin-bound articles may be allowlisted in EXPECTED_UNAVAILABLE with a justification.\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * Keeps [EXPECTED_UNAVAILABLE] honest: every entry must still be part of the audited corpus
     * and still unavailable for at least one supported IDE. When the issue-98 Fix phase
     * un-gates an article, the corresponding entry must be deleted here.
     */
    @Test
    fun testExpectedUnavailableAllowlistIsCurrent() {
        val candidates = candidateArticles().values.associateBy { uriPath(it) }

        val stale = mutableListOf<String>()
        for ((path, justification) in EXPECTED_UNAVAILABLE) {
            val article = candidates[path]
            if (article == null) {
                stale.add("$path: not in the audited corpus (skill/ root + skill-index/info references) — remove the entry")
                continue
            }
            if (unavailableCodes(article).isEmpty()) {
                stale.add("$path: now available for every supported IDE — remove the entry ($justification)")
            }
        }

        assertTrue(
            stale.isEmpty(),
            "Stale EXPECTED_UNAVAILABLE entries:\n${stale.joinToString("\n")}",
        )
    }
}
