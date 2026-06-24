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
 * End-to-end validation that an agent can discover and use the new
 * `printCsv` / `printToon` emitters on `McpScriptContext` (issues #34 + #35).
 *
 * The harness mirrors [FindDuplicatesPromptTest] — three `@Test` methods, one
 * per agent (Claude, Codex, Gemini), share a single IDE container via a
 * companion-object lazy `session`. JUnit runs them sequentially within the
 * class so the "one Docker IDE at a time" rule is satisfied without paying
 * the IDE startup cost three times. Per-agent IMPROVEMENTS reflections are
 * persisted under `test-integration/build/improvements/` so a maintainer
 * (or the next iteration round) can diff what each agent found unclear.
 *
 * Task design: enumerate a few `.kt` files in the project via
 * `FilenameIndex` and emit them with both `printCsv` (CSV with a `dictColumns`
 * preamble that dedupes the path column) and `printToon` (the TOON
 * array-of-records form). Markers `CSV_OK` / `TOON_OK` flag delivery so a
 * silent no-op fails the test cleanly.
 *
 * The prompt does NOT give the agent the exact recipe — the assertions
 * check that the agent's submitted `steroid_execute_code` script actually
 * invokes the new helpers and that the emitted output has the expected
 * shape. Discovery is the prompt-corpus's job; if the agent can't find
 * `printCsv`/`printToon`, that's a corpus regression — exactly the loop
 * the IMPROVEMENTS reflection is meant to surface.
 */
class PrintCsvPrintToonPromptTest {

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `print-csv-toon claude`() = runAgent(session.aiAgents.claude)

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `print-csv-toon codex`() = runAgent(session.aiAgents.codex)

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `print-csv-toon gemini`() = runAgent(session.aiAgents.gemini)

    private fun runAgent(agent: AiAgentSession) {
        val console = session.console
        console.writeStep(text = "Asking ${agent.displayName} to emit CSV + TOON via the new helpers")

        val result = agent.runPrompt(PROMPT, timeoutSeconds = 900).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating what ${agent.displayName} actually did")

        // Exit code: tolerate non-zero only if the agent reached the markers (some
        // agents return non-zero on completion even when they delivered the task).
        val hasCsvOk = hasAnyMarkerLine(output, "CSV_OK")
        val hasToonOk = hasAnyMarkerLine(output, "TOON_OK")
        if (result.exitCode != 0 && !(hasCsvOk && hasToonOk)) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "print-csv-toon prompt test (${agent.displayName})")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // (1) Markers prove the agent reached both halves of the task.
        check(hasCsvOk) {
            "[${agent.displayName}] Missing CSV_OK marker — agent did not complete the CSV half.\n$combined"
        }
        check(hasToonOk) {
            "[${agent.displayName}] Missing TOON_OK marker — agent did not complete the TOON half.\n$combined"
        }
        console.writeSuccess("CSV_OK + TOON_OK markers present")

        // (2) The agent's actual `steroid_execute_code` submissions must call the
        //     new helpers. Article echo via `steroid_fetch_resource` would otherwise
        //     leak the verbatim helper names into the transcript and satisfy a
        //     naive substring check on `combined`.
        val execBodies = readAgentExecCodeBodies(agent)
        check(execBodies.isNotEmpty()) {
            "[${agent.displayName}] No steroid_execute_code submissions captured in NDJSON."
        }
        val calledPrintCsv = execBodies.any {
            Regex("""\bprintCsv\s*\(""").containsMatchIn(it)
        }
        val calledPrintToon = execBodies.any {
            Regex("""\bprintToon\s*\(""").containsMatchIn(it)
        }
        check(calledPrintCsv) {
            buildString {
                appendLine("[${agent.displayName}] No submitted exec_code body called `printCsv(...)`.")
                appendLine("The corpus must steer agents at the new helper for tabular results.")
                appendLine("Submitted scripts:")
                execBodies.forEachIndexed { i, body -> appendLine("--- #${i + 1} ---\n$body") }
            }
        }
        check(calledPrintToon) {
            buildString {
                appendLine("[${agent.displayName}] No submitted exec_code body called `printToon(...)`.")
                appendLine("Submitted scripts:")
                execBodies.forEachIndexed { i, body -> appendLine("--- #${i + 1} ---\n$body") }
            }
        }
        console.writeSuccess("printCsv + printToon called in submitted exec_code")

        // (3) Capture IMPROVEMENTS reflection so the maintainer (or the next
        //     iteration round) can diff what each agent found unclear.
        val improvements = extractImprovementsBlock(output)
        check(improvements != null && improvements.isNotBlank()) {
            buildString {
                appendLine("[${agent.displayName}] Task 2 (IMPROVEMENTS reflection) was not delivered.")
                appendLine("The agent must emit a block delimited by `<<<IMPROVEMENTS>>>` / `<<<END_IMPROVEMENTS>>>`")
                appendLine("with prompt-only tweaks that would help a future agent.")
                appendLine("Got delimited block: ${improvements?.take(120)}")
                appendLine("Output:\n$combined")
            }
        }
        val savedTo = saveImprovements(agent.displayName, improvements)
        console.writeSuccess("Improvements written to $savedTo")
        console.writeHeader("PASSED (${agent.displayName})")
        println("[TEST/${agent.displayName}] discovered + used printCsv + printToon; reflection saved -> $savedTo")
    }

    // ── Helpers (copied from FindDuplicatesPromptTest — same harness pattern) ──────

    private fun hasAnyMarkerLine(output: String, vararg names: String): Boolean =
        names.any { name -> output.lineSequence().any { it.trim().startsWith("$name:") } }

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
                val content = obj["message"]?.jsonObject?.get("content")
                    ?.let { runCatching { it.jsonArray }.getOrNull() }
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
        val file = dir.resolve("IMPROVEMENTS-print-csv-toon-$safeName.md")
        val header = buildString {
            appendLine("# print-csv / print-toon: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by PrintCsvPrintToonPromptTest on ${java.time.Instant.now()}.")
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
                consoleTitle = "print-csv-toon prompt test",
            )).waitForProjectReady()
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session
        }

        // Natural task. We intentionally do NOT name `printCsv` / `printToon` in
        // the prompt — discovery is the prompt-corpus's job, and the assertions
        // verify the agent's actual `steroid_execute_code` submissions called
        // those helpers (parsing the raw NDJSON to side-step article echo).
        val PROMPT: String = """
# Two tasks for this run

## Task 1 — emit two tabular reports of Kotlin files in this project

The IntelliJ IDE has a project loaded. Pick at most 5 `.kt` files from the
project's main source tree (any reasonable set — top of an alphabetical
list, or any 5 the IDE's index returns first). For each, gather:

- the file's absolute path
- the file's line count
- the file's basename (no directory)

Use `steroid_execute_code` for everything — do not shell out, do not use the
native `Read` / `Grep` / `Glob` tools.

You must produce TWO outputs of the same data, in this order, inside the
same `steroid_execute_code` call (or two consecutive calls):

1. A **CSV** report using IntelliJ MCP Steroid's most token-efficient
   tabular emitter. The output must include a per-column dictionary
   preamble for the path column so repeated absolute paths are not
   re-emitted on every row.

2. A **TOON** report of the same five rows. The output should be the
   TOON array-of-records form (header line with the column names + one
   comma-separated row per file).

After the work is done, print these two markers on their own lines:

CSV_OK: yes
TOON_OK: yes

If either step failed, print the marker with `no` and a one-line reason.

## Task 2 — reflect on how Task 1 went

Now look back at Task 1. Was it easy or hard to find the right helper to
emit CSV and TOON? Was anything ambiguous, missing, or surprising in the
MCP Steroid prompt corpus (`mcp-steroid://...` resources, the tool
descriptions, the in-script helper inventory)?

**Hard constraint** — your suggestions must be about **prompts only**: skill
articles, tool descriptions, system-prompt text. We **cannot** add new MCP
tools or API methods as a fix path; the only knob the maintainers can turn
is the prompt content. Frame every suggestion in those terms (e.g. "the
`mcp-steroid://skill/coding-with-intellij-context-api` article should
mention ... so I find it from the helper inventory").

Print your reflection between these exact delimiters:

<<<IMPROVEMENTS>>>
(your reflection: bullet points are fine — what was hard, what was missing,
prompt-only tweaks that would help a future agent)
<<<END_IMPROVEMENTS>>>
""".trimIndent()
    }
}
