/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
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
 * - every article in the `skill/` root,
 * - every article in the `prompt/` root (the MCP prompt entry points: skill, test-skill,
 *   debugger-skill, ...), and
 * - every article URI referenced from those `prompt/` guides and from the
 *   `mcp-steroid-info` server-instructions prompt — including articles advertised by bare id
 *   in shorthand lists under a `mcp-steroid://<folder>/<id>` placeholder,
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
         * (the part after `://`). Every entry MUST have a one-line justification naming the
         * plugin-bound API that makes the article genuinely unavailable — do NOT add entries
         * to silence this test for an accidentally-gated article; restructure the article
         * instead (platform-generic unannotated fence + per-IDE conditional sections, see
         * `ide/inspect-and-fix` for the established pattern).
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
            // The entries below entered the corpus when the `mcp-steroid://ide/<id>` shorthand
            // list in prompt/skill became part of the audit (#98). Every fence in each of them
            // drives a Java-plugin refactoring processor (com.intellij.refactoring.* java-impl
            // classes) that ships only in IDEA among the supported product codes — the recipes
            // are genuinely IDEA-bound as written. Issue #98 tracks lower-priority rewrites
            // around language-agnostic refactoring surfaces where they exist.
            "ide/extract-method" to "Java-only ExtractMethodProcessor/ExtractMethodHandler (java-impl)",
            "ide/introduce-variable" to "Java-only IntroduceVariableBase/IntroduceVariableHandler (java-impl)",
            "ide/change-signature" to "Java-only ChangeSignatureProcessor/ParameterInfoImpl (java-impl)",
            "ide/pull-up-members" to "Java-only PullUpProcessor/MemberInfo (java-impl)",
            "ide/push-down-members" to "Java-only PushDownProcessor/MemberInfo (java-impl)",
            "ide/extract-interface" to "Java-only ExtractInterfaceProcessor/MemberInfo (java-impl)",
            "ide/move-class" to "Java-only MoveClassesOrPackagesProcessor/PackageWrapper (java-impl)",
            "ide/generate-constructor" to "Java-only GenerateMembersUtil constructor generation (java-impl)",
        )

        /** Same pattern as the generated `ResourceUriValidationTest`. */
        private val URI_PATTERN = Regex("""mcp-steroid://[\w-]+(?:/[\w-]+)*""")

        /**
         * Shorthand index lists: lines like
         * `- mcp-steroid://ide/<id> - Runnable Kotlin scripts (e.g., extract-method, safe-delete, ...)`
         * advertise articles without spelling full URIs and would otherwise escape [URI_PATTERN].
         * Group 1 is the folder, group 2 the rest of the line carrying backticked article ids.
         */
        private val SHORTHAND_LIST_PATTERN = Regex("""mcp-steroid://([\w-]+)/<id>`([^\n]*)""")

        /** A backticked simple id token inside a shorthand list line. */
        private val SHORTHAND_ID_PATTERN = Regex("""`([\w-]+)`""")

        /** Number of ids in the `mcp-steroid://ide/<id>` shorthand list of prompt/skill. */
        private const val KNOWN_IDE_SHORTHAND_IDS = 17
    }

    private val articlesByUri: Map<String, ArticleBase> = ResourcesIndex().roots
        .flatMap { it.value.articles.values }
        .associateBy { it.uri }

    /**
     * The audited corpus: every `skill/` root article, every `prompt/` root guide, plus every
     * existing article referenced (by full URI or shorthand id) from those guides and from
     * `mcp-steroid-info`.
     */
    private fun candidateArticles(): Map<String, ArticleBase> {
        val skillRoot = ResourcesIndex().roots["skill"]
            ?: error("ResourcesIndex has no 'skill' root — folder renamed?")
        val promptRoot = ResourcesIndex().roots["prompt"]
            ?: error("ResourcesIndex has no 'prompt' root — folder renamed?")

        val referencedText = buildString {
            for (guide in promptRoot.articles.values) {
                appendLine(guide.description.readPrompt())
                // Read all parts unfiltered so references hidden behind IDE conditionals are audited too.
                for (part in guide.parts) appendLine(part.readPrompt())
                for (item in guide.seeAlsoItems) appendLine(item.text)
            }
            appendLine(McpSteroidInfoPrompt().readPrompt())
        }

        val referenced = URI_PATTERN.findAll(referencedText)
            .map { it.value }
            // Folder-prefix mentions like `mcp-steroid://ide` (placeholders for `ide/<id>`) are
            // not articles; full-URI validity is enforced by the generated ResourceUriValidationTest.
            .mapNotNull { articlesByUri[it] }

        // Shorthand lists advertise articles by bare id under a `<folder>/<id>` placeholder —
        // resolve every id so an advertised article can never silently escape the audit.
        // An id that resolves to nothing is a broken advertisement and fails right here
        // (the generated ResourceUriValidationTest only checks full URIs).
        val shorthandReferenced = SHORTHAND_LIST_PATTERN.findAll(referencedText).flatMap { listMatch ->
            val folder = listMatch.groupValues[1]
            SHORTHAND_ID_PATTERN.findAll(listMatch.groupValues[2]).map { idMatch ->
                val uri = "mcp-steroid://$folder/${idMatch.groupValues[1]}"
                articlesByUri[uri]
                    ?: error("Shorthand id `${idMatch.groupValues[1]}` in the `mcp-steroid://$folder/<id>` list does not resolve to an article ($uri)")
            }
        }

        return (skillRoot.articles.values.asSequence() +
            promptRoot.articles.values.asSequence() +
            referenced + shorthandReferenced)
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
     * Keeps the shorthand-list extraction honest: `prompt/skill` advertises ~17 `ide/` recipes
     * via the `mcp-steroid://ide/<id>` shorthand list. If the extraction regresses (list format
     * drifts, regex stops matching), the audited corpus silently shrinks and gated articles
     * escape the matrix again — this guard pins the minimum expected `ide/` coverage.
     */
    @Test
    fun testShorthandIndexListsAreAudited() {
        val ideArticles = candidateArticles().keys.count { it.startsWith("mcp-steroid://ide/") }
        assertTrue(
            ideArticles >= KNOWN_IDE_SHORTHAND_IDS,
            "Audited corpus contains only $ideArticles ide/ articles, expected at least " +
                "$KNOWN_IDE_SHORTHAND_IDS (the prompt/skill `mcp-steroid://ide/<id>` shorthand list) — " +
                "shorthand extraction in candidateArticles() regressed?",
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
