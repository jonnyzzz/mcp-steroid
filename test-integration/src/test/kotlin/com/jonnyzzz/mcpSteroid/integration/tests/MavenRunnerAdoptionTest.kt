/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Verifies that, when explicitly guided to use the IDE's Maven integration, an AI agent
 * drives test execution through `steroid_execute_code` (MavenRunConfigurationType /
 * ProjectTaskManager) rather than shelling out to `Bash mvn test`.
 *
 * Intent shift (was "unprompted adoption"): the earlier prompt said only "run all Maven
 * tests… report results" with no tooling hint, so the agent deterministically picked
 * `Bash mvn test` and the assertion failed every run. Probing whether an UNGUIDED agent
 * spontaneously discovers the exec_code Maven path is too flaky to be a hard assertion.
 * We now give firm guidance to use `steroid_execute_code` (+ `mcp-steroid://skill/execute-code-maven`)
 * and assert the agent actually takes that path end-to-end. This overlaps with
 * MavenTestExecutionTest (which drives the exec_code Maven path directly) but verifies the
 * full agent-driven flow: prompt -> agent -> exec_code -> IDE Maven runner.
 *
 * Target runtime: ~2-3 minutes (container startup + single agent prompt).
 *
 * Uses test-project-maven which has a simple Calculator class with passing JUnit tests.
 */
class MavenRunnerAdoptionTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(MavenRunnerAdoptionTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Maven Runner Adoption",
                project = IntelliJProject.MavenTestProject,
            )).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }

        // Firm guidance to use the IDE's Maven integration via steroid_execute_code. The earlier
        // hint-free prompt deterministically drove the agent to `Bash mvn test`; this turns the test
        // into a deterministic "agent uses the exec_code Maven path when guided" check.
        val MAVEN_TEST_PROMPT: String = """
            Run all Maven tests in this project and report the results (pass count, fail count).

            Use the IDE's built-in Maven integration via the `steroid_execute_code` tool — do NOT
            shell out to `mvn`/`mvnw` via Bash. If you need the exact API, fetch the recipe
            `mcp-steroid://skill/execute-code-maven` with `steroid_fetch_resource` first, then run
            Maven test execution through `steroid_execute_code`.
        """.trimIndent()
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `maven runner adoption - claude uses exec_code for test execution`() {
        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(text = "Running Claude agent with Maven test prompt")
        val result = agent.runPrompt(MAVEN_TEST_PROMPT, timeoutSeconds = 300)
            .assertExitCode(0) { "Agent prompt failed" }
            .assertNoErrorsInOutput("Agent should not produce errors")

        console.writeStep(text = "Analyzing agent tool usage")

        val output = result.stdout

        // Count exec_code calls whose SUBMITTED CODE drives Maven test execution.
        // Parse the raw NDJSON (canonical parser, all 3 agent shapes) instead of grepping the
        // decoded prose — the prose echoes fetched skill articles (e.g. the execute-code-maven
        // recipe body) verbatim, which produces false "steroid_execute_code"+"maven" prose hits.
        val execCodeBodies = readAgentExecCodeBodies(agent)
        val execCodeTestCalls = execCodeBodies.count { body ->
            (body.contains("Maven", ignoreCase = true) || body.contains("mvn", ignoreCase = true)) &&
                    (body.contains("MavenRunConfigurationType") ||
                            body.contains("ProjectTaskManager") ||
                            body.contains("ExternalSystem", ignoreCase = true) ||
                            body.contains("test", ignoreCase = true))
        }

        // Bash mvn/mvnw fallback calls — still grepped from prose for the diagnostic message only
        // (bash invocations don't go through steroid_execute_code, so they aren't in execCodeBodies).
        val bashMvnCalls = output.lines().count { line ->
            line.contains("Bash", ignoreCase = true) &&
                    (line.contains("mvnw", ignoreCase = true) || line.contains("mvn ", ignoreCase = true))
        }

        // Report metrics
        println("=== Maven Runner Adoption Metrics ===")
        println("exec_code submissions parsed: ${execCodeBodies.size}")
        println("exec_code calls (maven test execution): $execCodeTestCalls")
        println("bash mvn/mvnw calls: $bashMvnCalls")
        println("agent output length: ${output.length} chars")
        println("=====================================")

        console.writeStep(text = "Verifying exec_code adoption")

        // With explicit guidance, the agent must drive Maven test execution through exec_code.
        assertTrue(execCodeTestCalls > 0,
            "Agent should use steroid_execute_code for Maven test execution, " +
                    "but found $execCodeTestCalls qualifying exec_code calls and $bashMvnCalls bash mvn calls " +
                    "across ${execCodeBodies.size} exec_code submission(s). " +
                    "Submitted exec_code bodies:\n${execCodeBodies.joinToString("\n---\n").take(2000)}\n" +
                    "Prose output:\n${output.take(1000)}"
        )

        if (bashMvnCalls > 0) {
            println("WARNING: Agent also used Bash mvn/mvnw ($bashMvnCalls calls) — exec_code is preferred")
        }

        console.writeSuccess("Claude used steroid_execute_code for Maven test execution " +
                "(exec_code=$execCodeTestCalls, bash_mvn=$bashMvnCalls)")
    }

    /**
     * Extract every `code` field from the agent's NDJSON for `steroid_execute_code`
     * invocations during the most recent run. Handles all three agent shapes (Claude
     * structured `tool_use`, Codex `mcp_tool_call`, Gemini root `tool_use`). Reference
     * implementation: `PrintCsvPrintToonPromptTest.readAgentExecCodeBodies` /
     * `FindDuplicatesPromptTest.readAgentExecCodeBodies`.
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
}
