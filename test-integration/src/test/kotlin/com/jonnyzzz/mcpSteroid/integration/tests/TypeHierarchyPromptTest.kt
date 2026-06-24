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
 * IMPROVEMENTS.md harness for the `mcp-steroid://ide/type-hierarchy` recipe.
 *
 * Fixture: `SsrHierarchyDemo.java` has a 3-level hierarchy under the `Greeting`
 * interface (HelloGreeter, FormalGreeter, LoudGreeter — the last is transitive).
 * The agent must enumerate the full subtype tree using IntelliJ's PSI/index APIs
 * (`ClassInheritorsSearch`, `PsiClass.supers`) — not by grepping the source.
 *
 * Per-agent runs (Claude, Codex, Gemini) share a single IDE container — JUnit
 * runs `@Test` methods sequentially within this class, satisfying the
 * "one Docker IDE container at a time" constraint.
 */
class TypeHierarchyPromptTest {

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `type-hierarchy claude`() = typeHierarchy(session.aiAgents.claude)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `type-hierarchy codex`() = typeHierarchy(session.aiAgents.codex)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `type-hierarchy gemini`() = typeHierarchy(session.aiAgents.gemini)

    private fun typeHierarchy(agent: AiAgentSession) {
        val console = session.console
        console.writeStep(text = "Asking ${agent.displayName} to enumerate the Greeting type hierarchy (no recipe hints)")

        val result = agent.runPrompt(TYPE_HIERARCHY_PROMPT, timeoutSeconds = 900).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text =  "Validating what ${agent.displayName} actually did")

        val hasFoundMarker = hasAnyMarkerLine(output, "SUBTYPES_FOUND", "Subtypes found")
        if (result.exitCode != 0 && !hasFoundMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "type-hierarchy prompt test (${agent.displayName})")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // 1) Agent actually ran steroid_execute_code.
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        // 2) Agent used IntelliJ's PSI/index APIs to walk the hierarchy — not grep/regex/Bash.
        //    Check the SHIPPED exec_code bodies (NDJSON), not the full stdout: our own skill
        //    articles contain the API names in prose, and merely fetching one echoes them
        //    into stdout. We want evidence of an actual call.
        console.writeInfo("Checking: agent used PSI type-hierarchy APIs in exec_code")
        val execCodeBodies = readAgentExecCodeBodies(agent)
        check(execCodeBodies.isNotEmpty()) {
            "[${agent.displayName}] No steroid_execute_code calls captured in NDJSON. The recipe was never run."
        }
        val typeHierarchySignals = listOf(
            "ClassInheritorsSearch",
            ".supers",
            "JavaPsiFacade",
            "findClass",
        )
        val signalsByBody = execCodeBodies.map { body ->
            typeHierarchySignals.filter { body.contains(it) }
        }
        val anyBodyWithSignal = signalsByBody.any { it.isNotEmpty() }
        check(anyBodyWithSignal) {
            buildString {
                appendLine("[${agent.displayName}] Agent did not use IntelliJ's PSI type-hierarchy APIs.")
                appendLine("Expected at least one of $typeHierarchySignals in some exec_code body.")
                appendLine()
                appendLine("Agents that walk the hierarchy by grep / file reads / Bash fail this test —")
                appendLine("the skill guides should steer them toward `mcp-steroid://ide/type-hierarchy`.")
                appendLine("Submitted exec_code count: ${execCodeBodies.size}")
            }
        }
        val hitSignals = signalsByBody.flatten().toSet().toList()
        console.writeSuccess("PSI type-hierarchy signals: $hitSignals")

        // 3) Agent reported the count of transitive subtypes (expect >= 3).
        console.writeInfo("Checking: SUBTYPES_FOUND marker")
        val subtypesFound = findMarkerValue(output, "SUBTYPES_FOUND", "Subtypes found")
        check(subtypesFound != null) {
            "[${agent.displayName}] Agent must output SUBTYPES_FOUND marker.\nOutput:\n$combined"
        }
        val subtypesInt = subtypesFound.takeWhile { it.isDigit() }.toIntOrNull() ?: -1
        check(subtypesInt >= 3) {
            buildString {
                appendLine("[${agent.displayName}] Agent must find at least 3 transitive subtypes of Greeting.")
                appendLine("Expected: HelloGreeter, FormalGreeter, LoudGreeter (LoudGreeter is transitive).")
                appendLine("Got: $subtypesFound")
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("SUBTYPES_FOUND: $subtypesFound")

        // 4) LoudGreeter (transitive subtype) must be in the output — proves the recipe walked
        //    beyond direct children. Direct-only would miss it because LoudGreeter extends
        //    FormalGreeter, not Greeting directly.
        console.writeInfo("Checking: LOUD_GREETER_HIT marker")
        val loudHit = findMarkerValue(output, "LOUD_GREETER_HIT", "LoudGreeter hit")
        check(loudHit != null && loudHit.contains("yes", ignoreCase = true)) {
            buildString {
                appendLine("[${agent.displayName}] Agent must report LOUD_GREETER_HIT: yes — LoudGreeter")
                appendLine("is a transitive subtype of Greeting (via FormalGreeter). Missing it indicates")
                appendLine("the agent only searched direct children, not the transitive closure.")
                appendLine("Got: $loudHit")
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("LOUD_GREETER_HIT: $loudHit")

        // 5) Capture the agent's reflection on Task 2 — saved per-agent for prompt tuning.
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

        console.writeHeader("PASSED (${agent.displayName})")

        println("[TEST/${agent.displayName}] enumerated Greeting hierarchy via PSI APIs, transitive subtypes found")
        println("[TEST/${agent.displayName}] reflection saved -> $savedTo")
    }

    /**
     * Extract every `code` field from the agent's NDJSON for `steroid_execute_code`
     * invocations during the most recent run. Returns an empty list if the NDJSON is missing.
     *
     * Both Claude (new structured format) and Codex (`mcp_tool_call`) are supported.
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
                // Gemini format: flat {"type":"tool_use", "tool_name":"...steroid_execute_code", "parameters":{"code":"..."}}
                if (obj["type"]?.jsonPrimitive?.contentOrNull == "tool_use") {
                    val toolName = obj["tool_name"]?.jsonPrimitive?.contentOrNull
                    if (toolName != null && toolName.contains("steroid_execute_code")) {
                        val params = obj["parameters"]?.let { runCatching { it.jsonObject }.getOrNull() }
                        val code = params?.get("code")?.jsonPrimitive?.contentOrNull
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
        val candidates = regex.findAll(output).toList()
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
        val file = dir.resolve("IMPROVEMENTS-type-hierarchy-$safeName.md")
        val header = buildString {
            appendLine("# Type-hierarchy: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by TypeHierarchyPromptTest on ${java.time.Instant.now()}.")
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
                consoleTitle = "type-hierarchy prompt test",
            )).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session
        }

        // Natural-task prompt — no recipe hints. The skill articles + tool descriptions
        // are what should steer the agent toward `mcp-steroid://ide/type-hierarchy`.
        // Task 2 captures the agent's own reflection for prompt tuning.
        val TYPE_HIERARCHY_PROMPT: String = """
# Two tasks for this run

## Task 1 — enumerate the type hierarchy of a class

The IntelliJ IDE is loaded with a project. Inside this project there is a Java
file `SsrHierarchyDemo.java` that declares an interface `Greeting`. Your job is
to list every class in the project that implements `Greeting` — directly OR
transitively (i.e. classes that extend an implementor are also subtypes of
`Greeting`).

You decide which tools and which approach to use. The fully-qualified name of
the interface is `com.jonnyzzz.mcpSteroid.demo.ssr.SsrHierarchyDemo.Greeting`.

After Task 1, print these two markers on their own lines:

SUBTYPES_FOUND: <integer count of TRANSITIVE subtypes of Greeting>
LOUD_GREETER_HIT: <yes if your subtype list contains a class named LoudGreeter, else no>

If you cannot find any subtypes, print `SUBTYPES_FOUND: 0` and
`LOUD_GREETER_HIT: no` and explain why.

## Task 2 — reflect on how Task 1 could have been smoother

Now look back at how Task 1 actually went. What was difficult, slow, or
ambiguous? What documentation, examples, or hints would have made you find
the right approach faster — or kept you from going down a dead end?

**Hard constraint** — your suggestions must be about **prompts only**: skill
articles (`mcp-steroid://...`), tool descriptions, system-prompt text. We
**cannot** add MCP tools or API methods as a fix path; the only knob the
maintainers can turn is the prompt content. Frame every suggestion in those
terms (e.g. "the `mcp-steroid://ide/type-hierarchy` article should mention
... so an agent finds it from the hierarchy-search entry point").

Print your reflection between these exact delimiters in your final answer:

<<<IMPROVEMENTS>>>
(your reflection: bullet points are fine — what was hard, what was missing,
prompt-only tweaks that would help a future agent)
<<<END_IMPROVEMENTS>>>
""".trimIndent()
    }
}
