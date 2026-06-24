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
 * Integration test for [issue #33](https://github.com/jonnyzzz/mcp-steroid/issues/33):
 * the `mcp-steroid://ide/find-duplicates` recipe must let the agent enumerate
 * `DuplicatedCode` clone clusters via the typed `DuplicateProblemDescriptor.getTextClone()`
 * getter — without `Class.getDeclaredField` / `setAccessible(true)` on the private
 * `myTextClone` field that originally drove the agent into reflection.
 *
 * Per-agent runs (Claude, Codex, Gemini) share a single IDE container — JUnit runs
 * the `@Test` methods sequentially within this class, satisfying the "one Docker
 * IDE container at a time" constraint without paying the IDE startup cost three times.
 *
 * Reproduction fixture: `DemoDuplicates.kt` declares two methods with byte-identical
 * bodies, which the bundled `DuplicatedCode` inspection must flag as a clone cluster.
 */
class FindDuplicatesPromptTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `find-duplicates claude`() = findDuplicates(session.aiAgents.claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `find-duplicates codex`() = findDuplicates(session.aiAgents.codex)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `find-duplicates gemini`() = findDuplicates(session.aiAgents.gemini)

    private fun findDuplicates(agent: AiAgentSession) {
        val console = session.console
        console.writeStep(text = "Asking ${agent.displayName} to find duplicates (no recipe hints)")

        val result = agent.runPrompt(FIND_DUPLICATES_PROMPT, timeoutSeconds = 900).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating what ${agent.displayName} actually did")

        val hasDupesMarker = hasAnyMarkerLine(output, "DUPLICATES_FOUND", "Duplicates found")
        if (result.exitCode != 0 && !hasDupesMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "find-duplicates prompt test (${agent.displayName})")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Parse the agent's actual exec_code submissions from the raw NDJSON ONCE, up front.
        // `readAgentExecCodeBodies` returns the `code` field of every steroid_execute_code call
        // across all three agent shapes (Claude / Codex / Gemini). Checks #2 and #3 both scan
        // this — NOT the prose `combined` transcript. Gemini's output filter renders terse
        // one-line tool summaries, so the recipe signal strings never reach `combined` even
        // when Gemini ran the correct PSI exec_code; scanning the submitted Kotlin makes the
        // recipe check work uniformly across agents.
        val execCodeBodies = readAgentExecCodeBodies(agent)

        // 1) Agent actually ran steroid_execute_code (the IDE's scripted-automation entry point).
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        // 2) Agent used one of the two IDE-based recipes from mcp-steroid://ide/find-duplicates —
        //    NOT a grep/regex/Bash substitute (issue #33's whole point). Both recipes are valid:
        //
        //    - Cross-check recipe: bundled `DuplicatedCode` inspection (warm-index, broader clones).
        //      Signals: DuplicateInspection / DuplicateProblemDescriptor / DuplicatedCode / com.jetbrains.clones.
        //    - Primary recipe: PSI body comparison (fresh-session-safe, exact bodies).
        //      Signals: KtNamedFunction.bodyBlockExpression / PsiMethod.body / byBody.
        //
        //    Article's "Agent fast path" callout intentionally steers fresh sessions to the Primary
        //    recipe (S5 iter7 reorder, commit 34a36912) because the Cross-check silently returns 0
        //    clusters when the HashFragmentIndex isn't warm. Accepting either set of signals here
        //    matches the article's contract.
        console.writeInfo("Checking: agent used one of the IDE-based find-duplicates recipes")
        val crossCheckSignals = listOf(
            "DuplicateInspection",
            "DuplicateProblemDescriptor",
            "DuplicatedCode",
            "com.jetbrains.clones",
        )
        val primaryRecipeSignals = listOf(
            "KtNamedFunction",
            "bodyBlockExpression",
            "PsiMethod",
            "PsiTreeUtil.collectElementsOfType",
        )
        // Scan the actual submitted Kotlin (NDJSON-parsed), not the prose `combined`. The prose
        // transcript only carries these signals for Claude/Codex (their filter echoes the fetched
        // article body); Gemini's filter renders one-line summaries, so the signals never reach
        // `combined` even when Gemini ran the correct PSI exec_code. Joining the exec_code bodies
        // makes this check read the same source as Check #3, uniformly across all agents.
        val execCodeJoined = execCodeBodies.joinToString("\n")
        val crossCheckHits = crossCheckSignals.filter { execCodeJoined.contains(it) }
        val primaryHits = primaryRecipeSignals.filter { execCodeJoined.contains(it) }
        val featureHits = crossCheckHits + primaryHits
        check(featureHits.isNotEmpty()) {
            buildString {
                appendLine("[${agent.displayName}] Agent did not use either IDE-based recipe from mcp-steroid://ide/find-duplicates.")
                appendLine("Expected at least one of:")
                appendLine("  - Cross-check signals: $crossCheckSignals")
                appendLine("  - Primary recipe signals: $primaryRecipeSignals")
                appendLine()
                appendLine("Agents that 'find duplicates' by grep/regex/Bash without invoking the IDE's")
                appendLine("PSI APIs or DuplicatedCode inspection fail this test — that is the whole point of issue #33.")
                appendLine("The skill guides should steer them toward `mcp-steroid://ide/find-duplicates`.")
                appendLine("Submitted exec_code bodies (${execCodeBodies.size}):\n$execCodeJoined")
                appendLine("Prose output:\n$combined")
            }
        }
        val pathTaken = when {
            primaryHits.isNotEmpty() && crossCheckHits.isNotEmpty() -> "Primary + Cross-check"
            primaryHits.isNotEmpty() -> "Primary recipe (PSI body comparison)"
            else -> "Cross-check recipe (DuplicatedCode inspection)"
        }
        console.writeSuccess("IDE recipe used: $pathTaken (signals: $featureHits)")

        // 3) Agent's FINAL recipe call did not reach for private-field reflection.
        //    Probing reflection earlier (to learn the API) is allowed by policy; using it
        //    in the shipped exec_code is not. Earlier we grepped `combined` (full stdout)
        //    for the forbidden tokens, but our own skill articles describe these patterns
        //    in prose warnings — fetching the article echoed them into stdout and the
        //    grep fired falsely. Parse the agent's NDJSON instead and check ONLY the
        //    `code` field of every `mcp__mcp-steroid__steroid_execute_code` invocation.
        console.writeInfo("Checking: NO private-field reflection in the final exec_code")
        // Reuse the `execCodeBodies` parsed up front (same NDJSON source as Check #2).
        check(execCodeBodies.isNotEmpty()) {
            "[${agent.displayName}] No steroid_execute_code calls captured in NDJSON. The recipe was never run."
        }
        val reflectionPatterns = listOf(
            "setAccessible(true)",
            ".getDeclaredField(\"myTextClone\")",
            ".getDeclaredField(\"my_text_clone\")",
            "Class.forName(\"com.jetbrains.clones.DuplicateProblemDescriptor\"",
        )
        val offendingBodies = execCodeBodies.mapIndexedNotNull { i, body ->
            val hits = reflectionPatterns.filter { body.contains(it) }
            if (hits.isEmpty()) null else (i + 1) to hits
        }
        check(offendingBodies.isEmpty()) {
            buildString {
                appendLine("[${agent.displayName}] Agent's exec_code uses private-field reflection on")
                appendLine("DuplicateProblemDescriptor — exactly the regression issue #33 reports.")
                appendLine("Offending submissions:")
                offendingBodies.forEach { (idx, hits) -> appendLine("  exec_code #$idx -> $hits") }
                appendLine()
                appendLine("Recipe must use the public `getTextClone()` getter directly. See")
                appendLine("`mcp-steroid://ide/find-duplicates` for the typed pattern.")
            }
        }
        console.writeSuccess("No private-field reflection in any of ${execCodeBodies.size} exec_code submission(s)")

        // 4) Agent reported a non-zero count of clusters.
        console.writeInfo("Checking: DUPLICATES_FOUND marker")
        val dupesFound = findMarkerValue(output, "DUPLICATES_FOUND", "Duplicates found")
        check(dupesFound != null) {
            "[${agent.displayName}] Agent must output DUPLICATES_FOUND marker.\nOutput:\n$combined"
        }
        val dupesInt = dupesFound.takeWhile { it.isDigit() }.toIntOrNull() ?: -1
        check(dupesInt >= 1) {
            "[${agent.displayName}] Agent must find at least one clone cluster (DUPLICATES_FOUND >= 1).\nGot: $dupesFound\nOutput:\n$combined"
        }
        console.writeSuccess("DUPLICATES_FOUND: $dupesFound")

        // 5) The DemoDuplicates.kt clone (byte-identical methods) must be in the output.
        console.writeInfo("Checking: DEMO_DUPLICATES_HIT marker")
        val demoHit = findMarkerValue(output, "DEMO_DUPLICATES_HIT", "DemoDuplicates hit")
        check(demoHit != null && demoHit.contains("yes", ignoreCase = true)) {
            buildString {
                appendLine("[${agent.displayName}] Agent must report DEMO_DUPLICATES_HIT: yes — the IDE's")
                appendLine("DuplicatedCode inspection MUST flag DemoDuplicates.kt's two byte-identical methods.")
                appendLine("If this fails, the agent likely never ran the inspection (or ran it on a wrong scope).")
                appendLine("Got: $demoHit")
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("DEMO_DUPLICATES_HIT: $demoHit")

        // 6) Capture the agent's reflection on Task 2 — what could be improved.
        //    We persist this to a per-agent file under build/improvements/ so a maintainer
        //    can read all three side-by-side and tune the skill articles afterwards.
        console.writeInfo("Capturing IMPROVEMENTS reflection from ${agent.displayName}")
        val improvements = extractImprovementsBlock(output)
        check(improvements != null && improvements.isNotBlank()) {
            buildString {
                appendLine("[${agent.displayName}] Task 2 (reflection) was not delivered. The agent must")
                appendLine("emit a block delimited by `<<<IMPROVEMENTS>>>` ... `<<<END_IMPROVEMENTS>>>`")
                appendLine("with notes on what was difficult, what skill content was missing or unclear,")
                appendLine("and prompt-only tweaks to make the next run faster.")
                appendLine()
                appendLine("Got delimited block: ${improvements?.take(120)}")
                appendLine("Output:\n$combined")
            }
        }
        val savedTo = saveImprovements(agent.displayName, improvements)
        console.writeSuccess("Improvements written to $savedTo")

        console.writeSuccess("[${agent.displayName}] used IntelliJ's DuplicatedCode feature, no reflection in final call")
        console.writeHeader("PASSED (${agent.displayName})")

        println("[TEST/${agent.displayName}] discovered + used DuplicatedCode without private-field reflection")
        println("[TEST/${agent.displayName}] reflection saved -> $savedTo")
    }

    /**
     * Extract every `code` field from the agent's NDJSON for `mcp__mcp-steroid__steroid_execute_code`
     * invocations during the most recent run. Returns an empty list if the NDJSON is missing.
     *
     * Both Claude (new structured format: `assistant.message.content[].type == "tool_use"`) and
     * Codex (`type == "item.completed"` with `item.type == "mcp_tool_call"`) are supported.
     */
    private fun readAgentExecCodeBodies(agent: AiAgentSession): List<String> {
        val logsRoot = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-integration/build/test-logs/test")
        if (!Files.isDirectory(logsRoot)) return emptyList()
        val agentSlug = agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val pattern = Regex("""agent-$agentSlug-\d+-raw\.ndjson""")
        val ndjson = Files.walk(logsRoot).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }
                .toList()
        }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() } ?: return emptyList()

        val codes = mutableListOf<String>()
        Files.newBufferedReader(ndjson).useLines { lines ->
            for (raw in lines) {
                if ('{' !in raw) continue
                val obj = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
                // Claude format: assistant.message.content[*].type == "tool_use"
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
                // Codex format: item.completed with item.type == "mcp_tool_call"
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
                // Gemini format: ROOT object with type=tool_use, tool_name, parameters.
                // Tool name shape is `mcp_<server-slug>_<tool-name>` with a SINGLE
                // underscore between the prefix and the server slug (Claude uses
                // `mcp__<server>__<tool>`), so a simple `endsWith` check matches.
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    val toolName = obj["tool_name"]?.jsonPrimitive?.contentOrNull
                    if (toolName != null && toolName.endsWith("steroid_execute_code")) {
                        val code = obj["parameters"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
                        if (!code.isNullOrEmpty()) codes += code
                    }
                }
            }
        }
        return codes
    }

    private fun extractImprovementsBlock(output: String): String? {
        val regex = Regex(
            pattern = """<<<\s*IMPROVEMENTS\s*>>>\s*\n([\s\S]*?)\n\s*<<<\s*END_IMPROVEMENTS\s*>>>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )
        // Use the LAST match. Codex sometimes echoes the prompt template (including the
        // <<<IMPROVEMENTS>>> markers and our placeholder hint) before producing its real
        // answer, so the first match would be Codex's echo instead of the reflection.
        // Claude tends to produce one block, so last == first there. Take last for both.
        val candidates = regex.findAll(output).toList()
        // Prefer a non-placeholder match: skip a block whose content is just our
        // parenthetical instruction (the "(your reflection: ...)" hint).
        val placeholderHint = "your reflection"
        val preferred = candidates.lastOrNull { match ->
            val content = match.groupValues.getOrNull(1)?.trim().orEmpty()
            content.isNotEmpty() && !content.startsWith("(") && placeholderHint !in content.lowercase()
        } ?: candidates.lastOrNull()
        return preferred?.groupValues?.getOrNull(1)?.trim()
    }

    private fun saveImprovements(agentName: String, content: String): Path {
        val safeName = agentName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
        val dir = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-integration/build/improvements")
        dir.createDirectories()
        val file = dir.resolve("IMPROVEMENTS-find-duplicates-$safeName.md")
        val header = buildString {
            appendLine("# Find-duplicates: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by FindDuplicatesPromptTest on ${java.time.Instant.now()}.")
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
        val lifetime by lazy {
            CloseableStackHost()
        }

        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "find-duplicates prompt test",
            )).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            // Trigger IDE startup + MCP readiness once for the whole class.
            session
        }

        // The prompt is intentionally a NATURAL TASK with no implementation hints:
        // we want to test that the agent discovers and uses IntelliJ's bundled
        // DuplicatedCode feature on its own. The skill articles + tool descriptions
        // are what should steer it — not the test prompt. The assertions in
        // `findDuplicates()` then check WHAT the agent ended up doing:
        //   * did it use the IDE's duplicates feature, or fall back to grep/regex?
        //   * did its final exec_code reach for private-field reflection?
        //
        // Task 2 is the agent's own reflection on the process — that text becomes
        // the IMPROVEMENTS-<agent>.md artifact a maintainer reads to tune the skill
        // articles. The constraint "prompt-only tweaks" is stated explicitly in the
        // prompt because we cannot expand the MCP tool surface as a fix path.
        val FIND_DUPLICATES_PROMPT: String = """
# Two tasks for this run

## Task 1 — find duplicate code in this project

The IntelliJ IDE is loaded with a project. Find any duplicate code blocks
across the project's source files. Report what you find.

You decide which tools and which approach to use.

After Task 1, print these two markers on their own lines:

DUPLICATES_FOUND: <integer count of duplicate-code clusters you found>
DEMO_DUPLICATES_HIT: <yes if any cluster spans the file DemoDuplicates.kt, else no>

If you cannot find any duplicates, print `DUPLICATES_FOUND: 0` and
`DEMO_DUPLICATES_HIT: no` and explain why.

## Task 2 — reflect on how Task 1 could have been smoother

Now look back at how Task 1 actually went. What was difficult, slow, or
ambiguous? What documentation, examples, or hints would have made you find
the right approach faster — or kept you from going down a dead end?

**Hard constraint** — your suggestions must be about **prompts only**: skill
articles (`mcp-steroid://...`), tool descriptions, system-prompt text. We
**cannot** add MCP tools or API methods as a fix path; the only knob the
maintainers can turn is the prompt content. Frame every suggestion in those
terms (e.g. "the `mcp-steroid://ide/inspect-and-fix` article should mention
... so an agent finds it from the inspect-and-fix entry point").

Print your reflection between these exact delimiters in your final answer:

<<<IMPROVEMENTS>>>
(your reflection: bullet points are fine — what was hard, what was missing,
prompt-only tweaks that would help a future agent)
<<<END_IMPROVEMENTS>>>
""".trimIndent()
    }
}
