/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.server.DevrigPromptsContextHandler
import com.jonnyzzz.mcpSteroid.prompts.ArticleBase
import com.jonnyzzz.mcpSteroid.prompts.ArticlePart
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.IdeFilter
import com.jonnyzzz.mcpSteroid.prompts.PromptRootBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import java.io.PrintStream

/** Exit code of `devrig prompt <uri>` when the resource does not resolve (unknown or ambiguous). */
const val PROMPT_NOT_FOUND_EXIT_CODE = 64

/**
 * `devrig prompt [uri]` — browse the `mcp-steroid://` prompt corpus from the CLI (issue #99).
 *
 * Prompt resources are the project's primary capability surface (Tenet 2), but without this command
 * they are only reachable through a live IDE via `steroid_fetch_resource`. Humans writing or
 * reviewing recipes — and users debugging "why did my agent do X" — need to read them without
 * wiring an agent. Rendering goes straight through the generated [ResourcesIndex]; no IDE, no MCP
 * tool, no state written anywhere (Tenets 1 and 3).
 *
 * Output routing: the rendered article goes to **stdout** (this is a human-facing CLI command, not
 * the MCP stdio path — `devrig prompt … | less` must work); diagnostics stay on stderr.
 */
fun DevrigServices.runPromptCommand(command: DevrigCommand.DevrigCommandPrompt): Int {
    val contexts = discoverPromptsContextsFromMarkers()
    if (contexts.size > 1) {
        System.err.println(
            "devrig prompt: ${contexts.size} different running IDEs discovered " +
                "(${contexts.joinToString(", ") { "${it.productCode}-${it.baselineVersion}" }}) — " +
                "cannot pick one to filter by.",
        )
    }
    return runPromptCommand(arg = command.uri, out = mcpStdout, err = System.err, context = contexts.singleOrNull())
}

/**
 * One-shot, read-only probe for the IDE context to render IDE-conditional content with: scans the
 * `~/.mcp-steroid/markers` directory (the same marker discovery `devrig backend` and the MCP-mode
 * monitor use, no HTTP round-trips) and derives a [PromptsContext] from each running IDE's build.
 *
 * The caller uses the SINGLE discovered context, and falls back to neutral rendering (gates
 * annotated instead of applied) when no IDE is running, the build strings cannot be parsed, or
 * several IDEs with conflicting contexts are up — picking one of two different products would
 * silently hide the other product's content.
 */
internal fun DevrigServices.discoverPromptsContextsFromMarkers(): List<PromptsContext> {
    ideDiscovery.scanOnce()
    return ideDiscovery.ides.value
        .map { DevrigPromptsContextHandler.promptsContextFromBuild(it.marker.ide.build) }
        .filter { it != PromptsContext.Generic } // Generic = unparsable build; never silently filter with it
        .distinct()
        .sortedBy { it.productCode }
}

/**
 * Core of `devrig prompt`, separated from [DevrigServices] for direct unit testing.
 *
 * - `arg == null` → renders the root prompts: the `mcp-steroid-info` server instructions, the
 *   `prompt/skill` index article, and a catalog of every resource URI. The root index IS the list.
 * - otherwise → resolves [arg] (full URI / scheme-less path / bare stem) and prints that resource;
 *   unknown or ambiguous input reports candidates on stderr and exits non-zero.
 *
 * @param context the routed IDE's context, or `null` for neutral rendering. With `null`, content
 *   gated to specific IDEs is INCLUDED with a visible `[gated: …]` annotation rather than silently
 *   hidden — [ArticleBase.readPayload] needs a concrete IDE and drops non-matching parts, which is
 *   exactly what a human reviewing the corpus must not get by default.
 */
fun runPromptCommand(
    arg: String?,
    out: PrintStream,
    err: PrintStream,
    context: PromptsContext?,
    index: PromptRootBase = ResourcesIndex(),
): Int {
    describeRenderContext(err, context)

    if (arg.isNullOrBlank()) {
        renderRootPrompts(out, context, index)
        return 0
    }

    return when (val resolution = resolvePromptResource(arg, index)) {
        is PromptResourceResolution.Found -> {
            val article = resolution.article
            if (context != null && !article.filter.matches(context)) {
                // The agent would get "resource not found" here (FetchResourceToolHandler gates on
                // the filter). A human asked for it by name — render it neutrally and say so.
                err.println(
                    "devrig prompt: note — ${article.uri} is gated to " +
                        "[${article.filter.gateLabel() ?: "all IDEs"}] and is NOT served to agents " +
                        "on the discovered IDE; rendering it with gates annotated instead.",
                )
                out.println(renderArticleAnnotated(article))
            } else {
                out.println(renderArticle(article, context))
            }
            0
        }
        is PromptResourceResolution.Ambiguous -> {
            err.println("Ambiguous resource '$arg' — it matches:")
            resolution.matches.forEach { err.println("  ${it.uri}") }
            err.println("Re-run with one of the full URIs above.")
            PROMPT_NOT_FOUND_EXIT_CODE
        }
        is PromptResourceResolution.NotFound -> {
            err.println("Unknown resource: $arg")
            if (resolution.candidates.isNotEmpty()) {
                err.println("Did you mean:")
                resolution.candidates.forEach { err.println("  ${it.uri}") }
            }
            err.println("Run 'devrig prompt' to see the full index.")
            PROMPT_NOT_FOUND_EXIT_CODE
        }
    }
}

private fun describeRenderContext(err: PrintStream, context: PromptsContext?) {
    if (context != null) {
        err.println(
            "devrig prompt: rendering for ${context.productCode}-${context.baselineVersion} " +
                "(discovered running IDE) — same content an agent gets via steroid_fetch_resource.",
        )
    } else {
        err.println(
            "devrig prompt: no single routed IDE — rendering ALL content; " +
                "IDE-gated sections are annotated inline instead of hidden.",
        )
    }
}

private fun renderRootPrompts(out: PrintStream, context: PromptsContext?, index: PromptRootBase) {
    val skill = SkillPromptArticle()

    out.println(sectionHeader("MCP Steroid server instructions (mcp-steroid-info)"))
    out.println(McpSteroidInfoPrompt().readPrompt())
    out.println()
    out.println(sectionHeader(skill.uri))
    out.println(renderArticle(skill, context))
    out.println()
    out.println(sectionHeader("All resources — fetch any URI below with 'devrig prompt <uri>'"))
    out.println(renderResourceCatalog(index))
}

private fun sectionHeader(label: String): String = buildString {
    appendLine("=".repeat(72))
    appendLine("== $label")
    append("=".repeat(72))
}

/** How the user addressed a resource resolves against the generated index. */
sealed interface PromptResourceResolution {
    data class Found(val article: ArticleBase) : PromptResourceResolution

    /** A bare stem matched several folders — the caller must qualify. */
    data class Ambiguous(val matches: List<ArticleBase>) : PromptResourceResolution

    /** Nothing matched; [candidates] are the near-misses (same-folder articles first). */
    data class NotFound(val candidates: List<ArticleBase>) : PromptResourceResolution
}

/**
 * Resolves user input to an article. Accepted spellings, tried in order:
 *  1. the full URI exactly as the index publishes it (`mcp-steroid://ide/apply-patch`);
 *  2. the scheme-less path (`ide/apply-patch`);
 *  3. a bare stem (`apply-patch`) when it is unambiguous across all folders.
 *
 * All matching is driven by `article.uri` from the generated index — no URI literals are
 * constructed here (the `NoHardcodedMcpSteroidUriUsageTest` rule, followed in devrig too).
 */
fun resolvePromptResource(arg: String, index: PromptRootBase = ResourcesIndex()): PromptResourceResolution {
    val articles = index.roots.values
        .flatMap { it.articles.values }
        .distinctBy { it.uri }
        .sortedBy { it.uri }
    val requested = arg.trim().trimEnd('/')

    articles.firstOrNull { it.uri == requested }?.let { return PromptResourceResolution.Found(it) }
    articles.firstOrNull { it.uriPath() == requested }?.let { return PromptResourceResolution.Found(it) }

    if (!requested.contains("://") && !requested.contains('/')) {
        val stemMatches = articles.filter { it.uriPath().substringAfterLast('/') == requested }
        when {
            stemMatches.size == 1 -> return PromptResourceResolution.Found(stemMatches.single())
            stemMatches.size > 1 -> return PromptResourceResolution.Ambiguous(stemMatches)
        }
    }

    // Near-misses: when a folder was named, list that folder's articles; otherwise fall back to a
    // case-insensitive substring scan over the paths.
    val requestedPath = requested.substringAfter("://")
    val folder = requestedPath.substringBeforeLast('/', missingDelimiterValue = "")
    val candidates = if (folder.isNotEmpty()) {
        articles.filter { it.uriPath().startsWith("$folder/") }
    } else {
        articles.filter { it.uriPath().contains(requestedPath, ignoreCase = true) }
    }
    return PromptResourceResolution.NotFound(candidates)
}

private fun ArticleBase.uriPath(): String = uri.substringAfter("://")

/** Renders with the IDE's own filtering when [context] is known, annotated-and-unfiltered otherwise. */
fun renderArticle(article: ArticleBase, context: PromptsContext?): String =
    if (context != null) article.readPayload(context) else renderArticleAnnotated(article)

/**
 * Neutral-context twin of [ArticleBase.readPayload]: includes EVERY part and annotates gated ones
 * with their [IdeFilter] instead of dropping them. Mirrors `readPayload`'s layout (title, blank
 * line, description, blank line, parts, `# See also`) so the only difference a reader sees is the
 * `> [gated: …]` marker lines.
 */
fun renderArticleAnnotated(article: ArticleBase): String = buildString {
    article.filter.gateLabel()?.let { gate ->
        appendLine("> [gated: $gate] — this entire article is served only to matching IDEs")
        appendLine()
    }
    appendLine(article.title.readPrompt())
    appendLine()
    appendLine(article.description.readPrompt())
    appendLine()
    for (part in article.parts) {
        part.filter.gateLabel()?.let { appendLine("> [gated: $it]") }
        when (part) {
            is ArticlePart.KotlinCode -> {
                appendLine("```kotlin")
                append(part.readPrompt())
                appendLine("```")
            }
            is ArticlePart.Markdown -> appendLine(part.readPrompt())
        }
    }
    if (article.seeAlsoItems.isNotEmpty()) {
        append("\n\n# See also\n\n")
        article.seeAlsoItems.forEach { item ->
            val gate = item.filter.gateLabel()
            appendLine(if (gate == null) item.text else "${item.text} [gated: $gate]")
        }
    }
}

/**
 * The flat catalog appended to the no-arg output: every article URI in the generated index, grouped
 * by folder, each line copy-pasteable into `devrig prompt <uri>` or `steroid_fetch_resource`. Gated
 * entries carry their gate label regardless of context, so the catalog never hides anything.
 */
fun renderResourceCatalog(index: PromptRootBase = ResourcesIndex()): String = buildString {
    for ((folder, folderIndex) in index.roots.toSortedMap()) {
        val articles = folderIndex.articles.values.distinctBy { it.uri }.sortedBy { it.uri }
        if (articles.isEmpty()) continue
        appendLine()
        appendLine("## ${folder.ifEmpty { "(root)" }}")
        for (article in articles) {
            val gate = article.filter.gateLabel()?.let { " [gated: $it]" } ?: ""
            val description = article.description.readPrompt().lineSequence().first().trim()
            appendLine("- ${article.uri}$gate — $description")
        }
    }
}

/**
 * Human-readable label for an [IdeFilter] gate, or `null` when the filter matches everything and
 * needs no annotation. Used by the neutral renderer and the catalog.
 */
internal fun IdeFilter.gateLabel(): String? = when (this) {
    IdeFilter.All -> null
    is IdeFilter.Ide -> buildString {
        append(if (productCodes.isEmpty()) "any IDE" else productCodes.sorted().joinToString(", "))
        minVersion?.let { append(", version >= $it") }
        maxVersion?.let { append(", version <= $it") }
    }
    is IdeFilter.Not -> "not (${inner.gateLabel() ?: "all IDEs"})"
    // `distinct()` collapses the common generated shape And(Ide(IU), Or(Ide(IU))) into a single
    // "IU" instead of the noisy "IU and IU".
    is IdeFilter.And -> operands.mapNotNull { it.gateLabel() }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" and ")
    is IdeFilter.Or -> {
        // An Or with an ungated operand matches everything — no annotation needed.
        if (operands.any { it.gateLabel() == null }) null
        else operands.mapNotNull { it.gateLabel() }.distinct().joinToString(" or ")
    }
}
