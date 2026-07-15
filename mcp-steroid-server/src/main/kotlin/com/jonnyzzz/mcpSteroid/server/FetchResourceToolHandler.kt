package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.prompts.ArticleBase
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.FindDuplicatesPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.InspectAndFixPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import com.jonnyzzz.mcpSteroid.thisLogger

/**
 * Fetches any MCP Steroid resource by URI and returns its Markdown content.
 *
 * This is the **only** discovery surface for `mcp-steroid://` articles — prompt
 * articles are no longer registered as MCP `resources/`, so `ListMcpResourcesTool`
 * and `ReadMcpResourceTool` cannot see them. Going through this tool is required
 * because [project_name] is needed to render IDE-conditional content correctly.
 */
class FetchResourceToolHandler(
    private val handler: () -> PromptsContextHandler,
) : McpToolBase() {

    private val log = thisLogger()

    override val name = "steroid_fetch_resource"

    override val description: String get() {
        val testSkillUri = TestSkillPromptArticle().uri
        val debuggerUri = DebuggerSkillPromptArticle().uri
        val skillUri = SkillPromptArticle().uri
        val codingGuideUri = CodingWithIntelliJPromptArticle().uri
        val findDuplicatesUri = FindDuplicatesPromptArticle().uri
        val inspectAndFixUri = InspectAndFixPromptArticle().uri
        return "Fetch a mcp-steroid:// skill guide by URI. Returns markdown with copy-paste Kotlin code recipes for steroid_execute_code. " +
                "Running tests? → $testSkillUri | " +
                "Debugging? → $debuggerUri | " +
                "Find duplicates / clones / copy-pasted code / DRY violations? → $findDuplicatesUri (copy ONLY the 'Primary recipe — PSI body comparison' block; the Cross-check inspection path silently returns 0 clusters in fresh sessions) | " +
                "Run a named inspection + quick fix? → $inspectAndFixUri | " +
                "Any IDE task? → $skillUri | " +
                "Full reference? → $codingGuideUri"
    }

    val uri = InputSchemaElement.param("uri")
        .description("The mcp-steroid:// URI to fetch (see the tool description for the canonical entry points, or fetch mcp-steroid://prompt/skill for the index)")
        .string()
        .required()
        .registerToSchema()

    val projectName = CommonToolParams.projectName().registerToSchema()

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val uri = context[uri]
        val projectName = context[projectName]

        log.info("steroid_fetch_resource: $uri")

        val promptsContext = handler().buildPromptsContext(projectName)
        val article = resolveResourceArticle(uri, promptsContext)
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Resource not found: $uri")),
                isError = true
            )

        return ToolCallResult(content = listOf(ContentItem.Text(text = article.readPayload(promptsContext))))
    }
}

/**
 * Resolves a `mcp-steroid://` resource URI to its matching [ArticleBase] for the given [context],
 * or `null` when no article matches the URI + IDE filter. Callers render the payload themselves via
 * [ArticleBase.readPayload] — this function hands back the article object, not pre-rendered text.
 *
 * The single source of truth for URI → article resolution: used by [FetchResourceToolHandler]
 * (the `steroid_fetch_resource` MCP tool) and by the `devrig fetch_resource` / `devrig prompt`
 * CLI commands, so both surfaces resolve identically.
 */
fun resolveResourceArticle(uri: String, context: PromptsContext): ArticleBase? =
    ResourcesIndex().roots.values
        .asSequence()
        .flatMap { it.articles.values.asSequence() }
        .firstOrNull { it.uri == uri && it.filter.matches(context) }

/**
 * Canonical entry-point articles to suggest when a fetch misses — built from the generated article
 * classes (never hardcoded `mcp-steroid://` literals, per NoHardcodedMcpSteroidUriUsageTest).
 * Reused by CLI error hints so the suggestions stay in sync with the tool description above.
 */
fun canonicalResourceEntryPoints(): List<ArticleBase> = listOf(
    SkillPromptArticle(),
    TestSkillPromptArticle(),
    DebuggerSkillPromptArticle(),
    FindDuplicatesPromptArticle(),
    InspectAndFixPromptArticle(),
    CodingWithIntelliJPromptArticle(),
)

/**
 * Layered "go deeper" guide URIs for the `devrig execute_code` CLI help — ordered shallow→deep so an
 * agent can fetch only as far as it needs (`devrig prompt <uri>`). Built from the generated article
 * classes (no hardcoded `mcp-steroid://` literals), so a renamed/removed article breaks the build.
 * Each pair is `uri to one-line-what-you-get`.
 */
fun executeCodeGuideUris(): List<Pair<String, String>> = listOf(
    ExecuteCodeToolDescriptionPromptArticle().uri to "full tool guide: decision tree, threading rules, multi-file edits, output rules",
    CodingWithIntelliJPromptArticle().uri to "IntelliJ API reference: PSI, VFS, refactorings, patterns, examples",
    TestSkillPromptArticle().uri to "running/inspecting tests via the IDE runner",
    DebuggerSkillPromptArticle().uri to "breakpoints, debug sessions, threads",
)
