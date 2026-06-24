/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Integration test bucket for the **structural-search skill articles** under
 * `prompts/src/main/prompts/skill/structural-search*.md`.
 *
 * Five scenarios spanning all six articles, each driven by an AI agent (Claude,
 * Codex) inside a shared IntelliJ container. Per-scenario seed fixtures live in
 * `src/test/docker/test-project/src/main/{kotlin,java}/com/jonnyzzz/mcpSteroid/demo/ssr/`.
 *
 * Scenario design follows the IntelliJ community SSR test corpus:
 *   - https://github.com/JetBrains/intellij-community/tree/master/java/structuralsearch-java/testSrc
 *   - https://github.com/JetBrains/intellij-community/tree/master/community/plugins/kotlin/code-insight/structural-search-k2/tests
 *
 * Each scenario asserts:
 *   1. The agent fetched at least one `mcp-steroid://skill/structural-search*`
 *      article (so the skill content drove the recipe).
 *   2. The agent ran `steroid_execute_code` (NDJSON has the entries).
 *   3. The agent did NOT use the known-broken pattern from
 *      `structural-search-api-recipe.md` §"What NOT to do" — calling
 *      `StringToConstraintsTransformer.transformCriteria(...)` a second time
 *      against the already-populated MatchOptions, which silently overwrites
 *      `searchPattern`.
 *   4. The agent's marker output matches the seeded fixture's expected count.
 *
 * Per-agent runs share a single IDE container — JUnit runs `@Test` methods
 * sequentially within this class, satisfying the "one Docker IDE at a time"
 * constraint.
 */
class StructuralSearchPromptTest {

    // -------- Scenario 1: Java Optional.get() audit with exprtype filter --------
    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `optional get audit claude`() = runOptionalGetAudit(session.aiAgents.claude)

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `optional get audit codex`() = runOptionalGetAudit(session.aiAgents.codex)

    private fun runOptionalGetAudit(agent: AiAgentSession) = runScenario(
        agent = agent,
        scenarioName = "optional-get-audit",
        prompt = OPTIONAL_GET_PROMPT,
        expectedFetched = listOf("structural-search"),
        markerName = "OPTIONAL_GET_MATCHES",
        expectedMatches = 4,
        fixtureFile = "SsrOptionalDemo.java",
    )

    // -------- Scenario 2: Kotlin runCatching{}.onFailure{} audit --------
    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `runCatching audit claude`() = runRunCatchingAudit(session.aiAgents.claude)

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `runCatching audit codex`() = runRunCatchingAudit(session.aiAgents.codex)

    private fun runRunCatchingAudit(agent: AiAgentSession) = runScenario(
        agent = agent,
        scenarioName = "runCatching-audit",
        prompt = RUNCATCHING_PROMPT,
        expectedFetched = listOf("structural-search-kotlin", "structural-search"),
        markerName = "RUNCATCHING_PAIRS",
        expectedMatches = 3,
        fixtureFile = "SsrRunCatchingDemo.kt",
    )

    // -------- Scenario 3: Java System.out.println audit --------
    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `sysout audit claude`() = runSysoutAudit(session.aiAgents.claude)

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `sysout audit codex`() = runSysoutAudit(session.aiAgents.codex)

    private fun runSysoutAudit(agent: AiAgentSession) = runScenario(
        agent = agent,
        scenarioName = "sysout-audit",
        prompt = SYSOUT_PROMPT,
        expectedFetched = listOf("structural-search"),
        markerName = "SYSOUT_CALLS",
        expectedMatches = 5,
        fixtureFile = "SsrSysoutDemo.java",
    )

    // -------- Scenario 4: Java hierarchy-constrained class search --------
    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `hierarchy implementors claude`() = runHierarchyAudit(session.aiAgents.claude)

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `hierarchy implementors codex`() = runHierarchyAudit(session.aiAgents.codex)

    private fun runHierarchyAudit(agent: AiAgentSession) = runScenario(
        agent = agent,
        scenarioName = "hierarchy-implementors",
        prompt = HIERARCHY_PROMPT,
        expectedFetched = listOf("structural-search"),
        markerName = "GREETING_IMPLEMENTORS",
        expectedMatches = 3,
        fixtureFile = "SsrHierarchyDemo.java",
    )

    // -------- Scenario 5: profile registry enumeration (no fixture; introspects the running IDE) --------
    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `profile registry claude`() = runProfileRegistry(session.aiAgents.claude)

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `profile registry codex`() = runProfileRegistry(session.aiAgents.codex)

    private fun runProfileRegistry(agent: AiAgentSession) = runScenario(
        agent = agent,
        scenarioName = "profile-registry",
        prompt = PROFILE_REGISTRY_PROMPT,
        expectedFetched = listOf("structural-search-coverage", "structural-search"),
        markerName = "SSR_PROFILES",
        expectedMatches = -1, // see customAssertions below — "≥ 5" not exact
        fixtureFile = null,
        customAssertions = { combined, output ->
            val n = findMarkerValue(output, "SSR_PROFILES")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
            check(n >= 5) { "Expected SSR_PROFILES >= 5 (Java, Kotlin, XML, Properties, …); got $n.\nOutput:\n$combined" }
            session.console.writeSuccess("SSR_PROFILES=$n (>= 5 expected)")

            val javaFound = findMarkerValue(output, "JAVA_PROFILE_FOUND") ?: ""
            check(javaFound.contains("yes", ignoreCase = true)) {
                "JAVA_PROFILE_FOUND must be 'yes'; got '$javaFound'.\nOutput:\n$combined"
            }
            val kotlinFound = findMarkerValue(output, "KOTLIN_PROFILE_FOUND") ?: ""
            check(kotlinFound.contains("yes", ignoreCase = true)) {
                "KOTLIN_PROFILE_FOUND must be 'yes'; got '$kotlinFound'.\nOutput:\n$combined"
            }
        },
    )

    // -------- shared scenario harness --------

    private fun runScenario(
        agent: AiAgentSession,
        scenarioName: String,
        prompt: String,
        expectedFetched: List<String>,
        markerName: String,
        expectedMatches: Int,
        fixtureFile: String?,
        customAssertions: ((combined: String, output: String) -> Unit)? = null,
    ) {
        val consoleAdapter = session.console

        consoleAdapter.writeStep(text = "[$scenarioName] ${agent.displayName}: running prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 900).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        val sawMarker = hasAnyMarkerLine(output, markerName)
        if (result.exitCode != 0 && !sawMarker) {
            consoleAdapter.writeError("[$scenarioName] agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "$scenarioName (${agent.displayName})")
        }
        consoleAdapter.writeInfo("[$scenarioName] agent exited with code ${result.exitCode ?: "?"}")

        // 1. Evidence of steroid_execute_code.
        consoleAdapter.writeInfo("[$scenarioName] checking steroid_execute_code evidence")
        assertUsedExecuteCodeEvidence(combined)
        consoleAdapter.writeSuccess("[$scenarioName] execute_code evidence found")

        // 2. The agent fetched at least one of the expected skill articles.
        consoleAdapter.writeInfo("[$scenarioName] checking skill-article fetch evidence")
        val fetchedUris = readAgentFetchedUris(agent)
        val fetchHits = fetchedUris.filter { uri ->
            expectedFetched.any { name -> uri.contains("/$name") }
        }
        check(fetchHits.isNotEmpty()) {
            buildString {
                appendLine("[$scenarioName/${agent.displayName}] agent did not fetch any of the expected SSR skill articles.")
                appendLine("Expected at least one of: ${expectedFetched.map { "mcp-steroid://skill/$it" }}")
                appendLine("Actually fetched: $fetchedUris")
                appendLine("Output:")
                appendLine(combined)
            }
        }
        consoleAdapter.writeSuccess("[$scenarioName] fetched: $fetchHits")

        // 3. No use of the known-broken recipe (transformCriteria called twice).
        consoleAdapter.writeInfo("[$scenarioName] checking for the broken-recipe anti-pattern")
        val execBodies = readAgentExecCodeBodies(agent)
        check(execBodies.isNotEmpty()) {
            "[$scenarioName/${agent.displayName}] no steroid_execute_code calls captured in NDJSON. The recipe was never run."
        }
        val brokenPatterns = listOf(
            // Calling transformCriteria a second time on the same MatchOptions silently
            // overwrites `searchPattern` — the bug structural-search-api-recipe §"What NOT
            // to do" warns about.
            Regex("""StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\(.*\)\s*[\s\S]*?StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\("""),
            // replaceAll(infos) from a coroutine deadlocks the EDT (replaceAll → DispatchThread).
            Regex("""\breplaceAll\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\.\s*matches\b"""),
        )
        val offending = execBodies.mapIndexedNotNull { i, body ->
            val hits = brokenPatterns.mapIndexedNotNull { idx, p -> if (p.containsMatchIn(body)) idx else null }
            if (hits.isEmpty()) null else (i + 1) to hits
        }
        check(offending.isEmpty()) {
            buildString {
                appendLine("[$scenarioName/${agent.displayName}] agent used the broken SSR recipe pattern:")
                offending.forEach { (idx, hits) -> appendLine("  exec_code #$idx -> broken-pattern indices $hits") }
                appendLine("See `mcp-steroid://skill/structural-search-api-recipe` §\"What NOT to do\".")
            }
        }
        consoleAdapter.writeSuccess("[$scenarioName] no broken patterns in any of ${execBodies.size} exec_code submission(s)")

        // 4. Marker count.
        if (expectedMatches >= 0) {
            consoleAdapter.writeInfo("[$scenarioName] checking $markerName marker")
            val raw = findMarkerValue(output, markerName)
            check(raw != null) {
                "[$scenarioName/${agent.displayName}] missing marker $markerName.\nOutput:\n$combined"
            }
            val n = raw.takeWhile { it.isDigit() }.toIntOrNull() ?: -1
            check(n == expectedMatches) {
                buildString {
                    appendLine("[$scenarioName/${agent.displayName}] $markerName mismatch.")
                    appendLine("Expected: $expectedMatches")
                    appendLine("Got: $raw")
                    if (fixtureFile != null) {
                        appendLine("Fixture: src/test/docker/test-project/src/main/.../ssr/$fixtureFile")
                    }
                    appendLine("Output:")
                    appendLine(combined)
                }
            }
            consoleAdapter.writeSuccess("[$scenarioName] $markerName=$n (expected $expectedMatches)")
        }

        // 5. Custom per-scenario assertions.
        customAssertions?.invoke(combined, output)

        // 6. IMPROVEMENTS reflection — mandatory. The whole point of this bucket is
        //    to surface prompt-only tweaks; without the reflection block the test
        //    has no signal to feed back into skill-article tuning.
        consoleAdapter.writeInfo("[$scenarioName] capturing IMPROVEMENTS reflection")
        val improvements = extractImprovementsBlock(output)
        check(improvements != null && improvements.isNotBlank()) {
            buildString {
                appendLine("[$scenarioName/${agent.displayName}] Task 2 (IMPROVEMENTS reflection) was not delivered.")
                appendLine("The agent must emit a block delimited by `<<<IMPROVEMENTS>>>` ... `<<<END_IMPROVEMENTS>>>`")
                appendLine("with notes on what was difficult, ambiguous, or missing in the structural-search")
                appendLine("skill articles. The constraint stated in the prompt is that suggestions must be")
                appendLine("PROMPT-ONLY — we cannot extend the MCP tool surface or add API methods, so the")
                appendLine("only knob we can turn is the skill-article content. Without the reflection,")
                appendLine("this test has no maintenance signal and the bucket has no purpose.")
                appendLine()
                appendLine("Got delimited block: ${improvements?.take(120)}")
                appendLine("Output:")
                appendLine(combined)
            }
        }
        val savedTo = saveImprovements(scenarioName, agent.displayName, improvements)
        consoleAdapter.writeSuccess("[$scenarioName] improvements -> $savedTo")

        consoleAdapter.writeHeader("[$scenarioName/${agent.displayName}] PASSED")
        println("[TEST/$scenarioName/${agent.displayName}] passed")
    }

    // -------- NDJSON readers --------

    /**
     * Read every `mcp-steroid://...` URI fetched via `mcp__mcp-steroid__steroid_fetch_resource`
     * during the most recent agent run (Claude or Codex format).
     */
    private fun readAgentFetchedUris(agent: AiAgentSession): List<String> {
        val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
        val uris = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                // Claude: assistant.message.content[*].type == "tool_use" with name endsWith fetch_resource
                val content = obj["message"]?.jsonObject?.get("content")?.let { runCatching { it.jsonArray }.getOrNull() }
                if (content != null) {
                    for (entry in content) {
                        val item = runCatching { entry.jsonObject }.getOrNull() ?: continue
                        if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!name.endsWith("steroid_fetch_resource")) continue
                        val uri = item["input"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                        if (!uri.isNullOrEmpty()) uris += uri
                    }
                }
                // Codex: item.completed mcp_tool_call with tool == steroid_fetch_resource
                val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (item != null && item["type"]?.jsonPrimitive?.contentOrNull == "mcp_tool_call") {
                    val tool = item["tool"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                    if (tool == "steroid_fetch_resource" || tool?.endsWith("__steroid_fetch_resource") == true) {
                        val args: JsonObject? = item["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: item["input"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val uri = args?.get("uri")?.jsonPrimitive?.contentOrNull
                        if (!uri.isNullOrEmpty()) uris += uri
                    }
                }
            }
        }
        return uris
    }

    private fun readAgentExecCodeBodies(agent: AiAgentSession): List<String> {
        val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
        val codes = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                val content = obj["message"]?.jsonObject?.get("content")?.let { runCatching { it.jsonArray }.getOrNull() }
                if (content != null) {
                    for (entry in content) {
                        val item = runCatching { entry.jsonObject }.getOrNull() ?: continue
                        if (item["type"]?.jsonPrimitive?.contentOrNull != "tool_use") continue
                        val name = item["name"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!name.endsWith("steroid_execute_code")) continue
                        val code = item["input"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
                val item = obj["item"]?.let { runCatching { it.jsonObject }.getOrNull() }
                if (item != null && item["type"]?.jsonPrimitive?.contentOrNull == "mcp_tool_call") {
                    val tool = item["tool"]?.jsonPrimitive?.contentOrNull
                        ?: item["name"]?.jsonPrimitive?.contentOrNull
                    if (tool == "steroid_execute_code" || tool?.endsWith("__steroid_execute_code") == true) {
                        val args: JsonObject? = item["arguments"]?.let { runCatching { it.jsonObject }.getOrNull() }
                            ?: item["input"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val code = args?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
            }
        }
        return codes
    }

    private fun locateLatestRawNdjson(agent: AiAgentSession): Path? {
        val logsRoot = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-integration/build/test-logs/test")
        if (!Files.isDirectory(logsRoot)) return null
        val agentSlug = agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val pattern = Regex("""agent-$agentSlug-\d+-raw\.ndjson""")
        return Files.walk(logsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }.toList()
        }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
    }

    // -------- IMPROVEMENTS extraction (mirrors FindDuplicatesPromptTest) --------

    private fun extractImprovementsBlock(output: String): String? {
        val regex = Regex(
            pattern = """<<<\s*IMPROVEMENTS\s*>>>\s*\n([\s\S]*?)\n\s*<<<\s*END_IMPROVEMENTS\s*>>>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        val candidates = regex.findAll(output).toList()
        val placeholderHint = "your reflection"
        val preferred = candidates.lastOrNull { match ->
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            content.isNotEmpty() && !content.startsWith("(") && placeholderHint !in content.lowercase()
        } ?: candidates.lastOrNull()
        return preferred?.groupValues?.getOrNull(1)?.trim()
    }

    private fun saveImprovements(scenarioName: String, agentName: String, content: String): Path {
        val safeAgent = agentName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
        val dir = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-integration/build/improvements")
        dir.createDirectories()
        val file = dir.resolve("IMPROVEMENTS-$scenarioName-$safeAgent.md")
        val header = buildString {
            appendLine("# $scenarioName: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by StructuralSearchPromptTest on ${java.time.Instant.now()}.")
            appendLine("Constraint enforced by the prompt: prompt-only tweaks; no new MCP tools / API methods.")
            appendLine()
            appendLine("---")
            appendLine()
        }
        file.writeText(header + content + "\n")
        return file
    }

    companion object {
        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "structural-search prompt bucket",
            )).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session
        }

        // -------- prompts --------

        private fun ssrTaskTemplate(taskBody: String): String = """
# Two tasks for this run

## Task 1 — do the SSR work

$taskBody

The skill articles you should consult **first** (use `steroid_fetch_resource` on
their URIs):

- `mcp-steroid://skill/structural-search` — overview + safety box
- `mcp-steroid://skill/structural-search-api-recipe` — canonical Kotlin recipe + threading rules
- `mcp-steroid://skill/structural-search-syntax` — template variable forms + 9 inline macros
- `mcp-steroid://skill/structural-search-coverage` — language profile matrix
- `mcp-steroid://skill/structural-search-use-cases` — recipe gallery
- `mcp-steroid://skill/structural-search-kotlin` — Kotlin-specific notes

Use `steroid_execute_code` to run the SSR query. Output the markers requested
above on their own lines.

**Hard rules** (the canonical recipe in `mcp-steroid://skill/structural-search-api-recipe`):
- Always call `Matcher.validate(project, options)` BEFORE constructing the `Matcher`.
- Do NOT wrap `Matcher.findMatches(...)` in an outer `readAction { }`.
- Do NOT call `StringToConstraintsTransformer.transformCriteria(...)` a second
  time on the same `MatchOptions` — it overwrites the search pattern.
- Use the apostrophe form (`'_x`, `'_x:[regex(...)]`, etc.) when authoring patterns.

## Task 2 — reflect on Task 1 (REQUIRED)

Now look back at how Task 1 actually went. What was difficult, slow, or
ambiguous? Which skill article passages were unclear, missing, or actively
misleading? What additional examples or warnings would have made you find
the right recipe faster — or kept you from going down a dead end?

**Hard constraint** — your suggestions must be about **prompts only**: skill
articles (`mcp-steroid://skill/structural-search*`), tool descriptions, system-
prompt text. We **cannot** add MCP tools or API methods as a fix path; the only
knob the maintainers can turn is the prompt content. Frame every suggestion in
those terms (e.g. "the `mcp-steroid://skill/structural-search-syntax` article
should mention X near the `:[exprtype(...)]` row so an agent finds it without
trial and error").

Print your reflection between these exact delimiters as part of your final
answer (the test will FAIL the run if this block is missing):

<<<IMPROVEMENTS>>>
(your reflection: bullet points are fine — what was hard, what was missing,
which skill article needs which prompt-only tweak)
<<<END_IMPROVEMENTS>>>
""".trimIndent()

        val OPTIONAL_GET_PROMPT: String = ssrTaskTemplate(
            """
The project contains a Java fixture at
`src/main/java/com/jonnyzzz/mcpSteroid/demo/ssr/SsrOptionalDemo.java` that has
several `Optional.get()` callsites mixed with bait (`OptionalInt.getAsInt()`,
`Map.get(...)`, a string literal containing the text ".get").

Use IntelliJ Structural Search to find every callsite where the receiver's
**resolved type** is `java.util.Optional<T>` and the called method is `get`.
The recipe must use the `:[exprtype(...)]` macro from
`mcp-steroid://skill/structural-search-syntax` so the bait callsites are
filtered out.

Print this marker on its own line:

OPTIONAL_GET_MATCHES: <integer count of matches>
""".trimIndent()
        )

        val RUNCATCHING_PROMPT: String = ssrTaskTemplate(
            """
The project contains a Kotlin fixture at
`src/main/kotlin/com/jonnyzzz/mcpSteroid/demo/ssr/SsrRunCatchingDemo.kt` with
several `runCatching { … }.onFailure { … }` chains.

Use IntelliJ Structural Search (Kotlin profile, K2) to find every such chain.
Use the apostrophe-form pattern documented in
`mcp-steroid://skill/structural-search-kotlin`. The pattern should match the
`runCatching { '_TRYBODY* }.onFailure { '_E -> '_HANDLER* }` shape.

Print this marker on its own line:

RUNCATCHING_PAIRS: <integer count of pairs>

The fixture also contains a `consumesResult()` method that uses
`runCatching { … }.getOrElse { … }` — note that this is **not** a
`runCatching { … }.onFailure { … }` chain, so the SSR pattern above must NOT
match it. (The skill article warns that rewriting `runCatching/onFailure` to
`try/catch` is unsafe when the result is consumed.)
""".trimIndent()
        )

        val SYSOUT_PROMPT: String = ssrTaskTemplate(
            """
The project contains a Java fixture at
`src/main/java/com/jonnyzzz/mcpSteroid/demo/ssr/SsrSysoutDemo.java` with
several `System.out.println(...)`, `System.out.print(...)`, and
`System.out.printf(...)` calls — plus `System.err.*` and a shadowed-name
`out.println(...)` callsite that should NOT match.

Use IntelliJ Structural Search to find every call where the receiver is
literally `System.out` (regardless of which method is called). Use the
apostrophe-form pattern `System.out.'_m('_args*);` per
`mcp-steroid://skill/structural-search-syntax`.

Print this marker on its own line:

SYSOUT_CALLS: <integer count of matches>
""".trimIndent()
        )

        val HIERARCHY_PROMPT: String = ssrTaskTemplate(
            """
The project contains a Java fixture at
`src/main/java/com/jonnyzzz/mcpSteroid/demo/ssr/SsrHierarchyDemo.java` with
an interface `Greeting` and several classes — some implement the interface
directly, one implements it transitively via a parent class.

Use IntelliJ Structural Search to find every class that implements `Greeting`,
**including via a parent class**. The recipe must use the `*<TypeName>`
"within hierarchy" modifier on the text constraint (or, equivalently, set
`MatchVariableConstraint.setWithinHierarchy(true)` programmatically) — see
`mcp-steroid://skill/structural-search-syntax` and the use-case at
`mcp-steroid://skill/structural-search-use-cases`.

Print this marker on its own line:

GREETING_IMPLEMENTORS: <integer count of classes>
""".trimIndent()
        )

        val PROFILE_REGISTRY_PROMPT: String = ssrTaskTemplate(
            """
Use the live IntelliJ runtime to enumerate every registered SSR language
profile in this IDE. The skill article
`mcp-steroid://skill/structural-search-coverage` describes the recipe:

    StructuralSearchProfile.EP_NAME.extensionList.forEach { ... }

Print exactly these markers on their own lines:

SSR_PROFILES: <integer count of registered profiles>
JAVA_PROFILE_FOUND: <yes if a JavaStructuralSearchProfile is registered, else no>
KOTLIN_PROFILE_FOUND: <yes if any KotlinStructuralSearchProfile is registered, else no>
""".trimIndent()
        )
    }
}
