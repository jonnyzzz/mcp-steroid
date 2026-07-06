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
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.prompts.generated.ResourcesIndex
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.FindDuplicatesPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.InspectAndFixPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.DebuggerSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJPromptArticle
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
        val payload = resolveResourcePayload(uri, promptsContext)
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "ERROR: Resource not found: $uri")),
                isError = true
            )

        return ToolCallResult(content = listOf(ContentItem.Text(text = payload)))
    }
}

/**
 * Resolves a `mcp-steroid://` resource URI to its rendered Markdown payload for the given
 * [context], or `null` when no article matches the URI + IDE filter.
 *
 * The single source of truth for URI → payload resolution: used by [FetchResourceToolHandler]
 * (the `steroid_fetch_resource` MCP tool) and by the `devrig fetch_resource` / `devrig prompt`
 * CLI commands, so both surfaces resolve identically.
 */
fun resolveResourcePayload(uri: String, context: PromptsContext): String? {
    val article = ResourcesIndex().roots.values
        .asSequence()
        .flatMap { it.articles.values.asSequence() }
        .firstOrNull { it.uri == uri && it.filter.matches(context) }
        ?: return null
    return article.readPayload(context)
}

/**
 * Canonical entry-point URIs to suggest when a fetch misses — built from the generated article
 * classes (never hardcoded `mcp-steroid://` literals, per NoHardcodedMcpSteroidUriUsageTest).
 * Reused by CLI error hints so the suggestions stay in sync with the tool description above.
 */
fun canonicalResourceEntryPoints(): List<String> = listOf(
    SkillPromptArticle().uri,
    TestSkillPromptArticle().uri,
    DebuggerSkillPromptArticle().uri,
    FindDuplicatesPromptArticle().uri,
    InspectAndFixPromptArticle().uri,
    CodingWithIntelliJPromptArticle().uri,
)
