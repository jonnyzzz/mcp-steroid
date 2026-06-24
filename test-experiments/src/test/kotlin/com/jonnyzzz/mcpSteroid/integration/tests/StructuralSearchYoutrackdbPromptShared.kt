/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Shared helpers used by every `StructuralSearchYoutrackdb*Test` class — kept in
 * one place so the default-IDE and pinned (253/261/...) variants stay in sync.
 *
 * Mirror of `YouTrackDbMavenPromptShared.kt` for the SSR scenario.
 */

const val SSR_YOUTRACKDB_LABEL = "ssr-youtrackdb"

/** The single prompt every variant uses. Produces three markers: SSR_PROFILES,
 *  OPTIONAL_GET_MATCHES, JAVA_PREDEFINED_COUNT, plus the IMPROVEMENTS block. */
val SSR_AUDIT_PROMPT: String = """
# SSR audit on a real Maven Java codebase (youtrackdb)

The IntelliJ IDE has loaded https://github.com/JetBrains/youtrackdb (Java, Apache
Maven, multi-module). Use IntelliJ Structural Search and Replace to do the
following audits and report the results.

## Read first

The recipe lives in these skill articles — fetch them via `steroid_fetch_resource`
before writing any code:

- `mcp-steroid://skill/structural-search` — overview
- `mcp-steroid://skill/structural-search-api-recipe` — canonical Kotlin recipe
- `mcp-steroid://skill/structural-search-syntax` — template language
- `mcp-steroid://skill/structural-search-coverage` — language profile matrix

## Task

In a single (or small set of) `steroid_execute_code` invocation(s), do all three:

1. **Profile registry**. Enumerate every registered SSR profile and report:
       SSR_PROFILES: <integer count of registered profiles>
       JAVA_PROFILE_FOUND: <yes if a JavaStructuralSearchProfile is registered, else no>

2. **Optional.get() audit**. Find every callsite where the resolved expression
   type is `java.util.Optional<T>` and the called method is `get`, scoped to
   `GlobalSearchScope.projectScope(project)`. Use the apostrophe-form pattern
   with a `:[exprtype(...)]` constraint per `structural-search-syntax`. Report:
       OPTIONAL_GET_MATCHES: <integer count of matches; 0 is acceptable>

3. **Predefined-templates count**. Resolve the Java profile via
   `StructuralSearchUtil.getProfileByFileType(JavaFileType.INSTANCE)` and count
   `profile.predefinedTemplates.size`. Report:
       JAVA_PREDEFINED_COUNT: <integer>

## Hard rules

These come from `mcp-steroid://skill/structural-search-api-recipe`:

- Always call `Matcher.validate(project, options)` BEFORE constructing the `Matcher`.
- Do NOT wrap `Matcher.findMatches(...)` in an outer `readAction { }`.
- Do NOT call `StringToConstraintsTransformer.transformCriteria(...)` a second
  time on the same `MatchOptions` — it overwrites the search pattern.
- Use the apostrophe form (`'_x`, `'_x:[exprtype(...)]`) for the Optional.get audit.

## Reflection — REQUIRED

Now reflect on Task 1: what was difficult, slow, or ambiguous in the skill
articles? Which passages were unclear, missing, or actively misleading?
What additional examples or warnings would have made you find the right
recipe faster on a real-world Maven multi-module project like this one?

**Hard constraint** — suggestions must be about **prompts only**
(`mcp-steroid://skill/structural-search*`, tool descriptions, system-prompt
text). We **cannot** add MCP tools or API methods; the only knob the
maintainers can turn is the prompt content.

Print your reflection between these exact delimiters as part of your final
answer (the test will FAIL the run if this block is missing):

<<<IMPROVEMENTS>>>
(your reflection: bullet points; what was hard, what was missing, which
skill article needs which prompt-only tweak)
<<<END_IMPROVEMENTS>>>
""".trimIndent()

/** Run the audit, validate every signal, save IMPROVEMENTS. Identical body for
 *  every variant — only the IDE container differs. */
fun runStructuralSearchYoutrackdbAudit(
    session: IntelliJContainer,
    agent: AiAgentSession,
    label: String,
) {
    val console = session.console
    console.writeStep(text = "[$label] ${agent.displayName}: running prompt")

    val result = agent.runPrompt(SSR_AUDIT_PROMPT, timeoutSeconds = 1500).awaitForProcessFinish()
    val output = result.stdout
    val combined = result.stdout + "\n" + result.stderr

    val allMarkersPresent = listOf("SSR_PROFILES", "OPTIONAL_GET_MATCHES", "JAVA_PREDEFINED_COUNT")
        .all { hasAnyMarkerLine(output, it) }
    if (result.exitCode != 0 && !allMarkersPresent) {
        console.writeError("[$label] agent exited with code ${result.exitCode}")
        result.assertExitCode(0, message = "$label (${agent.displayName})")
    }
    console.writeInfo("[$label] agent exited with code ${result.exitCode ?: "?"}")

    console.writeInfo("[$label] checking execute_code evidence")
    assertUsedExecuteCodeEvidence(combined)

    console.writeInfo("[$label] checking skill-article fetches")
    val fetched = readAgentFetchedUris(agent)
    val ssrFetches = fetched.filter { it.contains("/structural-search") }
    check(ssrFetches.isNotEmpty()) {
        buildString {
            appendLine("[$label/${agent.displayName}] agent did not fetch any structural-search skill article.")
            appendLine("Fetched URIs: $fetched")
            appendLine("Output:")
            appendLine(combined)
        }
    }
    console.writeSuccess("[$label] fetched: $ssrFetches")

    console.writeInfo("[$label] checking for broken-recipe anti-patterns")
    val execBodies = readAgentExecCodeBodies(agent)
    check(execBodies.isNotEmpty()) {
        "[$label/${agent.displayName}] no steroid_execute_code calls captured."
    }
    val brokenPatterns = listOf(
        Regex("""StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\(.*\)\s*[\s\S]*?StringToConstraintsTransformer\s*\.\s*transformCriteria\s*\("""),
        Regex("""\breplaceAll\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\.\s*matches\b"""),
    )
    val offending = execBodies.mapIndexedNotNull { i, body ->
        val hits = brokenPatterns.mapIndexedNotNull { idx, p -> if (p.containsMatchIn(body)) idx else null }
        if (hits.isEmpty()) null else (i + 1) to hits
    }
    check(offending.isEmpty()) {
        "[$label/${agent.displayName}] broken SSR recipe pattern in exec_code: $offending"
    }
    console.writeSuccess("[$label] no broken patterns in any of ${execBodies.size} exec_code submission(s)")

    console.writeInfo("[$label] SSR_PROFILES marker")
    val nProfiles = findMarkerValue(output, "SSR_PROFILES")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
    check(nProfiles >= 5) {
        "[$label/${agent.displayName}] SSR_PROFILES expected >= 5; got $nProfiles.\nOutput:\n$combined"
    }
    console.writeSuccess("[$label] SSR_PROFILES=$nProfiles")

    val javaFound = findMarkerValue(output, "JAVA_PROFILE_FOUND") ?: ""
    check(javaFound.contains("yes", ignoreCase = true)) {
        "[$label/${agent.displayName}] JAVA_PROFILE_FOUND must be 'yes' on a Maven Java project; got '$javaFound'."
    }
    console.writeSuccess("[$label] JAVA_PROFILE_FOUND=$javaFound")

    console.writeInfo("[$label] OPTIONAL_GET_MATCHES marker")
    val nOptGet = findMarkerValue(output, "OPTIONAL_GET_MATCHES")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
    check(nOptGet >= 0) {
        "[$label/${agent.displayName}] OPTIONAL_GET_MATCHES must be a non-negative integer; got '$nOptGet' (raw: '${findMarkerValue(output, "OPTIONAL_GET_MATCHES")}').\nOutput:\n$combined"
    }
    console.writeSuccess("[$label] OPTIONAL_GET_MATCHES=$nOptGet (no exact-count assertion against an evolving real-world repo)")

    console.writeInfo("[$label] JAVA_PREDEFINED_COUNT marker")
    val nTpl = findMarkerValue(output, "JAVA_PREDEFINED_COUNT")?.takeWhile { it.isDigit() }?.toIntOrNull() ?: -1
    check(nTpl >= 50) {
        "[$label/${agent.displayName}] JAVA_PREDEFINED_COUNT expected >= 50 (current IntelliJ ships ~98); got $nTpl.\nOutput:\n$combined"
    }
    console.writeSuccess("[$label] JAVA_PREDEFINED_COUNT=$nTpl")

    console.writeInfo("[$label] capturing IMPROVEMENTS reflection")
    val improvements = extractImprovementsBlockSsr(output)
    check(improvements != null && improvements.isNotBlank()) {
        buildString {
            appendLine("[$label/${agent.displayName}] Task 2 (IMPROVEMENTS reflection) was not delivered.")
            appendLine("The agent must emit a block delimited by `<<<IMPROVEMENTS>>>` ... `<<<END_IMPROVEMENTS>>>`")
            appendLine("with notes on what was difficult or unclear. Constraint: prompt-only tweaks")
            appendLine("(no new MCP tools / API methods).")
            appendLine()
            appendLine("Got delimited block: ${improvements?.take(120)}")
            appendLine("Output:")
            appendLine(combined)
        }
    }
    val savedTo = saveSsrYoutrackdbImprovements(label, agent.displayName, improvements)
    console.writeSuccess("[$label] improvements -> $savedTo")

    console.writeHeader("[$label/${agent.displayName}] PASSED")
    println("[TEST/$label/${agent.displayName}] passed (profiles=$nProfiles optionalGet=$nOptGet predefined=$nTpl)")
}

private fun readAgentFetchedUris(agent: AiAgentSession): List<String> {
    val ndjson = locateLatestRawNdjson(agent) ?: return emptyList()
    val uris = mutableListOf<String>()
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
                    if (!name.endsWith("steroid_fetch_resource")) continue
                    val uri = item["input"]?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
                    if (!uri.isNullOrEmpty()) uris += uri
                }
            }
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
        .resolve("test-experiments/build/test-logs/test")
    if (!Files.isDirectory(logsRoot)) return null
    val agentSlug = agent.displayName.lowercase().replace(Regex("[^a-z0-9]+"), "-")
    val pattern = Regex("""agent-$agentSlug-\d+-raw\.ndjson""")
    return Files.walk(logsRoot).use { stream ->
        stream.filter { Files.isRegularFile(it) && pattern.matches(it.fileName.toString()) }.toList()
    }.maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
}

private fun extractImprovementsBlockSsr(output: String): String? {
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

private fun saveSsrYoutrackdbImprovements(label: String, agentName: String, content: String): Path {
    val safeAgent = agentName.lowercase().replace(Regex("[^a-z0-9_-]+"), "-")
    val dir = ProjectHomeDirectory.requireProjectHomeDirectory()
        .resolve("test-experiments/build/improvements")
    dir.createDirectories()
    val file = dir.resolve("IMPROVEMENTS-$label-$safeAgent.md")
    val header = buildString {
        appendLine("# $label: agent reflection ($agentName)")
        appendLine()
        appendLine("Generated by structural-search youtrackdb test on ${java.time.Instant.now()}.")
        appendLine("Constraint enforced by the prompt: prompt-only tweaks; no new MCP tools / API methods.")
        appendLine()
        appendLine("---")
        appendLine()
    }
    file.writeText(header + content + "\n")
    return file
}
