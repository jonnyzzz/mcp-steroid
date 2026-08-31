/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.arena.AgentToolCall
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentFinalResponse
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentToolCalls
import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleAwareAgentSession
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

fun normalizeRawDevrigCommand(commandText: String, devrig: String): String? {
    val raw = commandText.trim()
    val transportCommand = when {
        raw == "/bin/bash -lc $devrig" -> devrig
        else -> raw.unwrapCodexShellTransport() ?: raw
    }
    val normalized = transportCommand
        .replace("\"$devrig\"", devrig)
        .replace("'$devrig'", devrig)
    if (!Regex("^\\Q$devrig\\E(?:\\s|$)").containsMatchIn(normalized)) return null
    if (normalized.hasUnquotedShellControlSyntax()) return null
    return normalized
}

fun invokesJsonOnlyDevrigAction(commandText: String, devrig: String, command: String): Boolean =
    normalizeRawDevrigCommand(commandText, devrig) in setOf(
        "$devrig $command --json",
        "$devrig --json $command",
    )

fun invokesDevrigCommand(commandText: String, devrig: String, command: String): Boolean {
    val normalized = normalizeRawDevrigCommand(commandText, devrig) ?: return false
    return Regex("^\\Q$devrig\\E(?:\\s+--json)?\\s+\\Q$command\\E(?:\\s|$)").containsMatchIn(normalized)
}

fun devrigCommandHasFlag(commandText: String, devrig: String, flag: String): Boolean {
    val normalized = normalizeRawDevrigCommand(commandText, devrig) ?: return false
    return Regex("(?:^|\\s)\\Q$flag\\E(?:=|\\s|$)").containsMatchIn(normalized)
}

fun devrigCommandHasFlagValue(commandText: String, devrig: String, flag: String, value: String): Boolean {
    val normalized = normalizeRawDevrigCommand(commandText, devrig) ?: return false
    val escapedFlag = Regex.escape(flag)
    val escapedValue = Regex.escape(value)
    return Regex(
        "(?:^|\\s)$escapedFlag(?:=|\\s+)(?:\"$escapedValue\"|'$escapedValue'|$escapedValue)(?:\\s|$)",
    ).containsMatchIn(normalized)
}

fun devrigCommandHasArgumentValue(commandText: String, devrig: String, value: String): Boolean {
    val normalized = normalizeRawDevrigCommand(commandText, devrig) ?: return false
    val escapedValue = Regex.escape(value)
    return Regex("(?:^|\\s)(?:\"$escapedValue\"|'$escapedValue'|$escapedValue)(?:\\s|$)")
        .containsMatchIn(normalized)
}

fun devrigCommandHasShellSafeInlineCode(commandText: String, devrig: String, code: String): Boolean {
    val normalized = normalizeRawDevrigCommand(commandText, devrig) ?: return false
    val singleQuoted = Regex(
        "(?:^|\\s)--code(?:=|\\s+)'${Regex.escape(code)}'(?:\\s|$)",
    )
    val escapedForDoubleQuotes = code.replace("\"", "\\\"")
    val doubleQuoted = Regex(
        "(?:^|\\s)--code(?:=|\\s+)\"${Regex.escape(escapedForDoubleQuotes)}\"(?:\\s|$)",
    )
    return singleQuoted.containsMatchIn(normalized) || doubleQuoted.containsMatchIn(normalized)
}

private fun String.unwrapCodexShellTransport(): String? {
    val prefix = "/bin/bash -lc "
    if (!startsWith(prefix)) return null
    val encoded = removePrefix(prefix)
    return when (encoded.firstOrNull()) {
        '\'' -> encoded.decodeSingleQuotedShellWord()
        '"' -> encoded.decodeDoubleQuotedShellWord()
        else -> null
    }
}

private fun String.decodeSingleQuotedShellWord(): String? {
    val decoded = StringBuilder()
    var index = 0
    while (index < length) {
        when (this[index]) {
            '\'' -> {
                val end = indexOf('\'', startIndex = index + 1)
                if (end < 0) return null
                decoded.append(this, index + 1, end)
                index = end + 1
            }
            '\\' -> {
                if (index + 1 >= length) return null
                decoded.append(this[index + 1])
                index += 2
            }
            else -> return null
        }
    }
    return decoded.toString()
}

private fun String.decodeDoubleQuotedShellWord(): String? {
    if (length < 2 || first() != '"' || last() != '"') return null
    val decoded = StringBuilder()
    var index = 1
    while (index < lastIndex) {
        when (val ch = this[index]) {
            '$', '`', '"' -> return null
            '\\' -> {
                if (index + 1 >= lastIndex) return null
                val next = this[index + 1]
                if (next in listOf('$', '`', '"', '\\')) {
                    decoded.append(next)
                } else {
                    decoded.append('\\').append(next)
                }
                index += 2
            }
            else -> {
                decoded.append(ch)
                index++
            }
        }
    }
    return decoded.toString()
}

private fun String.hasUnquotedShellControlSyntax(): Boolean {
    var quote: Char? = null
    var escaped = false
    for (ch in this) {
        if (ch == '\n' || ch == '\r') return true
        if (escaped) {
            escaped = false
            continue
        }
        if (ch == '\\' && quote != '\'') {
            escaped = true
            continue
        }
        if (quote == '\'') {
            if (ch == '\'') quote = null
            continue
        }
        if (quote == '"') {
            if (ch == '"') {
                quote = null
            } else if (ch == '`' || ch == '$') {
                return true
            }
            continue
        }
        when (ch) {
            '\'', '"' -> quote = ch
            ';', '|', '&', '<', '>', '`', '$' -> return true
        }
    }
    return false
}

/**
 * Agent-facing usability experiments for devrig's packaged command line.
 *
 * Claude and Codex each take four independent routes through the same clean, preinstalled IDE:
 *  1. a strict task-first route that demonstrates the minimum six-call discovery and execution path;
 *  2. a help-first route that must read root help, use the discoverable `devrig help <command>` route,
 *     and execute every generated tool command with values learned from earlier responses.
 *  3. an outcome-only route that must discover the canonical project lister, every compatibility alias,
 *     and code execution without being told any command names, help route, flags, or call sequence;
 *  4. a lifecycle route that walks focused help immediately into every finite, safe devrig lifecycle
 *     action; `mcp` help is included while its long-lived stdio action stays in the protocol integration test.
 *
 * The agents are deliberately created outside [IntelliJContainer.aiAgents]. The container uses
 * [AiMode.AI_DEVRIG] only to deploy `/home/agent/devrig`; these sessions receive no MCP registration, so
 * every successful IDE action below proves the packaged CLI path rather than a direct MCP-tool shortcut.
 * Assertions decode raw agent NDJSON and correlate native shell calls with their results. The decoded prose
 * transcript is presentation only and is never accepted as execution evidence.
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DevrigCliAgentUsabilityExperimentTest {

    @Test
    @Order(1)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude discovers and uses the CLI from a task`() = taskFirstExperiment("claude", claude)

    @Test
    @Order(2)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex discovers and uses the CLI from a task`() = taskFirstExperiment("codex", codex)

    @Test
    @Order(3)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude follows help through missing values to an action`() = helpFirstExperiment("claude", claude)

    @Test
    @Order(4)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex follows help through missing values to an action`() = helpFirstExperiment("codex", codex)

    @Test
    @Order(5)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude discovers the CLI from outcomes only`() = outcomeOnlyExperiment("claude", claude)

    @Test
    @Order(6)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex discovers the CLI from outcomes only`() = outcomeOnlyExperiment("codex", codex)

    @Test
    @Order(7)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude follows lifecycle help into every safe action`() = lifecycleHelpExperiment("claude", claude)

    @Test
    @Order(8)
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `codex follows lifecycle help into every safe action`() = lifecycleHelpExperiment("codex", codex)

    private fun taskFirstExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_TASK_FIRST_${agentName.uppercase()}_OK"
        val prompt = """
            # Task: inspect and exercise an unfamiliar JetBrains IDE command line

            The packaged launcher is `$DEVRIG`. Use only your native shell tool to interact with it.
            Do not use any other tool at all, including todo/planning or file tools.
            Do not call MCP tools directly, and do not inspect repository source code or tests.

            Treat the command syntax as unknown. Start by reading the launcher's root help as a separate
            raw command. From that help, discover the canonical command that lists open IDE projects, its
            plural compatibility alias, and the legacy singular alias retained for older users.

            Run the canonical project-list action, the plural alias, and the legacy singular alias as three
            separate shell commands with machine-readable output. Do not pipe, redirect, combine, or
            transform any command: the raw JSON responses are audit evidence. Confirm that all three
            responses carry the same project/backend data and that both aliases report the canonical command
            identity.

            Then discover the CLI action that executes Kotlin in the IDE. Read that action's focused help
            through the root help's `devrig help <command>` route before running it with a project routing
            key from the canonical response. Execute this Kotlin script:

                println("$sentinel")

            Use machine-readable output and supply every required audit value the launcher asks for. Run
            every CLI step as a separate shell command; do not use `&&`, `;`, pipelines, redirections, or
            shell wrappers around the launcher.

            Your entire final response must be exactly these five lines, with no heading or explanation:
            CANONICAL_PROJECT_COMMAND: <the canonical command name>
            PROJECT_ALIAS: <the plural compatibility alias>
            LEGACY_PROJECT_ALIAS: <the singular legacy alias>
            ALIAS_EQUIVALENT: yes
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }
        assertRawDevrigOnly(shellCalls)

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val canonicalIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val aliasIndex = shellCalls.indexOfFirst {
            it.invokes("projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val legacyAliasIndex = shellCalls.indexOfFirst {
            it.invokes("project") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val executeHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("execute_code") && it.succeeded()
        }
        val executeIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasFlag("--json") && !it.hasHelpFlag() &&
                it.succeeded() && sentinel in it.resultText()
        }
        assertEquals(
            EXPECTED_TASK_FIRST_SHELL_CALLS,
            shellCalls.size,
            "Task-first must use exactly the required raw devrig calls. ${summarizeCalls(shellCalls)}",
        )
        assertOrdered(
            listOf(rootHelpIndex, canonicalIndex, aliasIndex, legacyAliasIndex, executeHelpIndex, executeIndex),
            "root help -> canonical list -> plural alias -> legacy alias -> execute_code help -> action",
            shellCalls,
        )

        assertEquals(0, rootHelpIndex, "Root help must be the first task-first shell call. ${summarizeCalls(shellCalls)}")
        val rootHelp = shellCalls[rootHelpIndex].resultText()
        assertTrue("devrig list_projects" in rootHelp) { "Root help did not advertise list_projects:\n$rootHelp" }
        assertTrue("aliases: projects, project" in rootHelp) {
            "Root help did not advertise the plural and legacy project aliases:\n$rootHelp"
        }
        assertImmediatelyBefore(executeHelpIndex, executeIndex, "execute_code help -> action", shellCalls)
        assertCommandHelp(
            shellCalls[executeHelpIndex],
            "execute_code",
            "--project_name",
            "--code",
            "--code-file",
            "--task_id",
            "--reason",
            "--json",
        )

        val canonicalEnvelope = shellCalls[canonicalIndex].successfulEnvelope("list_projects")
        val aliasEnvelope = shellCalls[aliasIndex].successfulEnvelope("list_projects")
        val legacyAliasEnvelope = shellCalls[legacyAliasIndex].successfulEnvelope("list_projects")
        for ((aliasName, envelope) in listOf("projects" to aliasEnvelope, "project" to legacyAliasEnvelope)) {
            assertEquals(
                canonicalEnvelope.getValue("data"),
                envelope.getValue("data"),
                "$aliasName alias must return exactly the canonical list_projects data",
            )
        }
        val projectName = canonicalEnvelope.firstProjectName()
        val executeCall = shellCalls[executeIndex]
        assertFlagValue(executeCall, "execute_code", "--project_name", projectName)
        assertShellSafeInlineCode(executeCall, sentinel)
        executeCall.successfulEnvelope("execute_code")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertExactMarkerLines(
            finalResponse,
            listOf(
                "CANONICAL_PROJECT_COMMAND: list_projects",
                "PROJECT_ALIAS: projects",
                "LEGACY_PROJECT_ALIAS: project",
                "ALIAS_EQUIVALENT: yes",
                "EXECUTION_MARKER: $sentinel",
            ),
        )

        result.assertExitCode(0) { "$agentName task-first CLI experiment failed" }
    }

    private fun outcomeOnlyExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_OUTCOME_ONLY_${agentName.uppercase()}_OK"
        val prompt = """
            # Task: prove that an unfamiliar JetBrains IDE command line is usable

            The packaged launcher is `$DEVRIG`. Use only your native shell tool to interact with it.
            Do not use any other tool at all, including todo/planning or file tools.
            Do not call MCP tools directly, and do not inspect repository source code or tests.

            Treat every command name, help route, option, required value, and call sequence as unknown.
            Learn them only from the launcher's own interface. Prove which action is the canonical way to
            list open IDE projects, identify every compatibility spelling the launcher exposes for that
            action, and demonstrate that all spellings return identical machine-readable project/backend
            data with the canonical command identity.

            Also prove that the launcher can run Kotlin in the currently open IDE by printing exactly
            `$sentinel`. Choose the necessary project routing and audit values from what the launcher tells
            you. Every shell call must be one separate raw launcher invocation; do not use pipes,
            redirections, shell variables, command substitution, aliases, functions, `&&`, or `;`.

            End your final response with exactly these marker lines:
            CANONICAL_PROJECT_COMMAND: <the canonical command name>
            PROJECT_ALIAS: <the plural compatibility alias>
            LEGACY_PROJECT_ALIAS: <the singular legacy alias>
            ALIAS_EQUIVALENT: yes
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }
        assertRawDevrigOnly(shellCalls)
        assertTrue(shellCalls.size in MIN_OUTCOME_ONLY_SHELL_CALLS..MAX_OUTCOME_ONLY_SHELL_CALLS) {
            "Outcome-only discovery took ${shellCalls.size} calls; expected a short, bounded route. " +
                summarizeCalls(shellCalls)
        }

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val canonicalIndex = shellCalls.indexOfFirst {
            it.invokes("list_projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val aliasIndex = shellCalls.indexOfFirst {
            it.invokes("projects") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val legacyAliasIndex = shellCalls.indexOfFirst {
            it.invokes("project") && it.hasFlag("--json") && !it.hasHelpFlag() && it.succeeded()
        }
        val executeHelpIndex = shellCalls.indexOfFirst { it.invokesCommandHelp("execute_code") && it.succeeded() }
        val executeIndex = shellCalls.indexOfFirst {
            it.invokes("execute_code") && it.hasFlag("--json") && !it.hasHelpFlag() &&
                it.succeeded() && sentinel in it.resultText()
        }
        assertEquals(0, rootHelpIndex, "Root help must be the first discovery call. ${summarizeCalls(shellCalls)}")
        assertTrue(listOf(canonicalIndex, aliasIndex, legacyAliasIndex, executeIndex).all { it > 0 }) {
            "Outcome-only route did not discover every required command/action. ${summarizeCalls(shellCalls)}"
        }
        assertTrue(executeHelpIndex < 0 || executeHelpIndex < executeIndex) {
            "Outcome-only route must inspect execute_code help before its successful action. ${summarizeCalls(shellCalls)}"
        }

        val rootHelp = shellCalls[rootHelpIndex].resultText()
        assertTrue("devrig list_projects" in rootHelp && "aliases: projects, project" in rootHelp) {
            "Root help did not expose the canonical project command and aliases:\n$rootHelp"
        }
        assertTrue("quote --code or prefer --code-file" in rootHelp) {
            "Root help did not promote the execute_code shell rule:\n$rootHelp"
        }

        val canonicalEnvelope = shellCalls[canonicalIndex].successfulEnvelope("list_projects")
        val aliasEnvelope = shellCalls[aliasIndex].successfulEnvelope("list_projects")
        val legacyAliasEnvelope = shellCalls[legacyAliasIndex].successfulEnvelope("list_projects")
        assertEquals(canonicalEnvelope.getValue("data"), aliasEnvelope.getValue("data"))
        assertEquals(canonicalEnvelope.getValue("data"), legacyAliasEnvelope.getValue("data"))

        val executeCall = shellCalls[executeIndex]
        assertFlagValue(executeCall, "execute_code", "--project_name", canonicalEnvelope.firstProjectName())
        assertShellSafeInlineCode(executeCall, sentinel)
        executeCall.successfulEnvelope("execute_code")

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertTrailingMarkerLines(
            finalResponse,
            listOf(
                "CANONICAL_PROJECT_COMMAND: list_projects",
                "PROJECT_ALIAS: projects",
                "LEGACY_PROJECT_ALIAS: project",
                "ALIAS_EQUIVALENT: yes",
                "EXECUTION_MARKER: $sentinel",
            ),
        )

        result.assertExitCode(0) { "$agentName outcome-only CLI experiment failed" }
    }

    private fun helpFirstExperiment(agentName: String, agent: AiAgentSession) {
        val sentinel = "DEVRIG_HELP_FIRST_${agentName.uppercase()}_OK"
        val taskId = "devrig-help-first-$agentName"
        val prompt = """
            # Task: audit the complete devrig help-to-action route

            The packaged launcher is `$DEVRIG`. Use only your native shell tool. Do not use any other tool
            at all, including todo/planning or file tools. Do not call MCP tools directly, inspect repository
            source code, or inspect tests. Treat all command syntax as unknown.

            First read the launcher root help. It advertises eight generated IDE commands. For each command
            below, read its focused help through the exact `devrig help <command>` route immediately before
            executing its action. Every action must use `--json`, and every help/action must be a separate,
            raw launcher command with no pipes, redirects, variables, aliases, functions, `&&`, or `;`.

            Follow this exact dependency order:

            1. `list_projects`: read help, run it, and retain one real `project_name` plus that project's
               absolute `path` from the raw JSON response.
            2. `list_windows`: read help, run it, and retain the real window id associated with that project.
            3. `execute_code`: read help. Deliberately run it once with only `--json` and no action parameters.
               The non-zero exit is expected; do not hide it. Verify that the structured command-scoped error
               explains how to obtain or choose every missing value. Then immediately recover with the real
               project routing key and run this Kotlin script:

                   println("$sentinel")

               Supply every required audit value, use task id `$taskId`, and retain the returned
               `execution_id`. Reuse that task id in every later command that asks for one.
            4. `execute_feedback`: read help, then rate that exact execution id as successful. Reuse the same
               project and task id; provide a concrete explanation. Before the successful action, run the
               command once with only `--json` and verify that every missing value is explained.
            5. `take_screenshot`: read help, then target the retained project/window and save the image with
               `--out=/tmp/devrig-help-first-$agentName.png`. First run it once with only `--json` and verify
               that every missing value is explained.
            6. `input`: read help, then target the same project/window with the safe, delay-only sequence
               `delay:25`; do not type, press, or click anything. First run it once with only `--json` and
               verify that every missing value is explained.
            7. `fetch_resource`: read help, then pass `mcp-steroid://prompt/skill` as its positional URI for
               the retained project.
                First run it once with only `--json` and verify that every missing value is explained.
            8. `open_project`: read help, then safely call it for the already-open absolute project path from
               step 1 with `--wait`. Verify that the success returns the same opaque `project_name`,
               `backend_name`, and canonical path retained in step 1. First run it once with only `--json`
               and verify that every missing value is explained. Do not open a different path.

            Copy values directly into later raw commands; do not use shell variables or command substitution.
            Do not repeat an action or insert unrelated shell commands between a command's help and action.

            Your entire final response must be exactly these four lines, with no heading or explanation:
            HELP_ROUTE: root -> all generated commands
            HELP_COMMAND_ROUTE: complete
            MISSING_PARAMETER_HELP: complete
            EXECUTION_MARKER: $sentinel
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }
        assertRawDevrigOnly(shellCalls)

        val rootHelpIndex = shellCalls.indexOfFirst { it.invokesRootHelp() && it.succeeded() }
        val listHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("list_projects") && it.succeeded() }
        val listActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("list_projects") && it.hasFlag("--json") && it.succeeded()
        }
        val windowsHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("list_windows") && it.succeeded() }
        val windowsActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("list_windows") && it.hasFlag("--json") && it.succeeded()
        }
        val executeHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("execute_code") && it.succeeded() }
        val missingExecuteIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("execute_code") && it.failed()
        }
        val executeActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("execute_code") && it.hasFlag("--json") &&
                it.succeeded() && sentinel in it.resultText()
        }
        val feedbackHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("execute_feedback") && it.succeeded()
        }
        val missingFeedbackIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("execute_feedback") && it.failed()
        }
        val feedbackActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("execute_feedback") && it.hasFlag("--json") && it.succeeded()
        }
        val screenshotHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("take_screenshot") && it.succeeded()
        }
        val missingScreenshotIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("take_screenshot") && it.failed()
        }
        val screenshotActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("take_screenshot") && it.hasFlag("--json") && it.succeeded()
        }
        val inputHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("input") && it.succeeded() }
        val missingInputIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("input") && it.failed()
        }
        val inputActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("input") && it.hasFlag("--json") && it.succeeded()
        }
        val fetchHelpIndex = shellCalls.indexOfFirst {
            it.invokesHelpCommandRoute("fetch_resource") && it.succeeded()
        }
        val missingFetchIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("fetch_resource") && it.failed()
        }
        val fetchActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("fetch_resource") && it.hasFlag("--json") && it.succeeded()
        }
        val openHelpIndex = shellCalls.indexOfFirst { it.invokesHelpCommandRoute("open_project") && it.succeeded() }
        val missingOpenIndex = shellCalls.indexOfFirst {
            it.invokesJsonOnlyAction("open_project") && it.failed()
        }
        val openActionIndex = shellCalls.indexOfFirst {
            it.invokesAction("open_project") && it.hasFlag("--json") && it.succeeded()
        }
        assertEquals(
            EXPECTED_HELP_FIRST_SHELL_CALLS,
            shellCalls.size,
            "Help-first must use exactly the required raw shell calls, with no retries or unrelated commands. " +
                summarizeCalls(shellCalls),
        )
        assertEquals(0, rootHelpIndex, "Root help must be the first shell call. ${summarizeCalls(shellCalls)}")
        assertOrdered(
            listOf(
                rootHelpIndex,
                listHelpIndex,
                listActionIndex,
                windowsHelpIndex,
                windowsActionIndex,
                executeHelpIndex,
                missingExecuteIndex,
                executeActionIndex,
                feedbackHelpIndex,
                missingFeedbackIndex,
                feedbackActionIndex,
                screenshotHelpIndex,
                missingScreenshotIndex,
                screenshotActionIndex,
                inputHelpIndex,
                missingInputIndex,
                inputActionIndex,
                fetchHelpIndex,
                missingFetchIndex,
                fetchActionIndex,
                openHelpIndex,
                missingOpenIndex,
                openActionIndex,
            ),
            "root help -> eight generated command help/action routes",
            shellCalls,
        )

        assertImmediatelyBefore(listHelpIndex, listActionIndex, "list_projects", shellCalls)
        assertImmediatelyBefore(windowsHelpIndex, windowsActionIndex, "list_windows", shellCalls)
        assertImmediatelyBefore(executeHelpIndex, missingExecuteIndex, "execute_code missing-value check", shellCalls)
        assertImmediatelyBefore(missingExecuteIndex, executeActionIndex, "execute_code recovery", shellCalls)
        assertRecoverySequence(feedbackHelpIndex, missingFeedbackIndex, feedbackActionIndex, "execute_feedback", shellCalls)
        assertRecoverySequence(
            screenshotHelpIndex,
            missingScreenshotIndex,
            screenshotActionIndex,
            "take_screenshot",
            shellCalls,
        )
        assertRecoverySequence(inputHelpIndex, missingInputIndex, inputActionIndex, "input", shellCalls)
        assertRecoverySequence(fetchHelpIndex, missingFetchIndex, fetchActionIndex, "fetch_resource", shellCalls)
        assertRecoverySequence(openHelpIndex, missingOpenIndex, openActionIndex, "open_project", shellCalls)

        val rootHelp = shellCalls[rootHelpIndex].resultText()
        for (command in GENERATED_COMMANDS) {
            assertTrue("devrig $command" in rootHelp) { "Root help did not advertise $command:\n$rootHelp" }
        }
        for (aliasNote in listOf("aliases: projects, project", "alias: prompt")) {
            assertTrue(aliasNote in rootHelp) { "Root help did not advertise '$aliasNote':\n$rootHelp" }
        }
        assertTrue("devrig help <command>" in rootHelp) {
            "Root help did not advertise the discoverable focused-help route:\n$rootHelp"
        }
        assertCommandHelp(shellCalls[listHelpIndex], "list_projects", "--json")
        assertCommandHelp(shellCalls[windowsHelpIndex], "list_windows", "--json")
        assertCommandHelp(
            shellCalls[executeHelpIndex],
            "execute_code",
            "--project_name",
            "--code",
            "--code-file",
            "--task_id",
            "--reason",
            "--json",
        )
        assertCommandHelp(
            shellCalls[feedbackHelpIndex],
            "execute_feedback",
            "--project_name",
            "--task_id",
            "--execution_id",
            "--success_rating",
            "--explanation",
            "--json",
        )
        assertCommandHelp(
            shellCalls[screenshotHelpIndex],
            "take_screenshot",
            "--project_name",
            "--task_id",
            "--reason",
            "--window_id",
            "--out",
            "--json",
        )
        assertCommandHelp(
            shellCalls[inputHelpIndex],
            "input",
            "--project_name",
            "--task_id",
            "--reason",
            "--window_id",
            "--sequence",
            "--json",
        )
        assertCommandHelp(
            shellCalls[fetchHelpIndex],
            "fetch_resource",
            "<uri>",
            "--project_name",
            "--json",
        )
        assertCommandHelp(
            shellCalls[openHelpIndex],
            "open_project",
            "--project_path",
            "--task_id",
            "--reason",
            "--trust_project",
            "--backend_name",
            "--wait",
            "--json",
        )

        val listEnvelope = shellCalls[listActionIndex].successfulEnvelope("list_projects")
        val project = listEnvelope.firstProject()
        val projectName = project.getValue("project_name").jsonPrimitive.content
        val projectPath = project.getValue("path").jsonPrimitive.content
        val backendName = project.getValue("backend_name").jsonPrimitive.content
        val windowsEnvelope = shellCalls[windowsActionIndex].successfulEnvelope("list_windows")
        val windowId = windowsEnvelope.windowIdFor(projectName)
        val missingEnvelope = shellCalls[missingExecuteIndex].jsonEnvelope("execute_code", expectedError = true)
        val missingHelp = missingEnvelope.getValue("data").jsonObject
            .getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content.lowercase()
        for (requiredText in listOf(
            "usage: devrig execute_code",
            "missing --project_name",
            "devrig list_projects",
            "not the folder name",
            "missing code",
            "--code-file=<path>",
            "--code='println(\"hello\")'",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )) {
            assertTrue(requiredText in missingHelp) {
                "Missing execute_code guidance '$requiredText' in expected failure:\n${shellCalls[missingExecuteIndex].resultText()}"
            }
        }
        assertMissingGuidance(
            shellCalls[missingFeedbackIndex],
            "execute_feedback",
            "missing --project_name",
            "devrig list_projects",
            "not the folder name",
            "missing --task_id",
            "any string works",
            "missing --success_rating",
            "0.00..1.00",
            "--success_rating=0.9",
            "missing --explanation",
            "what worked",
            "what didn't",
            "what you'll try next",
        )
        assertMissingGuidance(
            shellCalls[missingScreenshotIndex],
            "take_screenshot",
            "missing --project_name",
            "devrig list_projects",
            "not the folder name",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )
        assertMissingGuidance(
            shellCalls[missingInputIndex],
            "input",
            "missing --project_name",
            "devrig list_projects",
            "not the folder name",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
            "missing required --window_id",
            "devrig list_windows",
            "missing --sequence",
            "press:ctrl+p",
            "delay:200",
        )
        assertMissingGuidance(
            shellCalls[missingFetchIndex],
            "fetch_resource",
            "missing uri",
            "mcp-steroid://prompt/skill",
            "missing --project_name",
            "devrig list_projects",
            "not the folder name",
        )
        assertMissingGuidance(
            shellCalls[missingOpenIndex],
            "open_project",
            "missing --project_path",
            "absolute directory path",
            "missing --task_id",
            "any string works",
            "missing --reason",
            "intent and expected outcome",
        )

        val executeCall = shellCalls[executeActionIndex]
        assertFlagValue(executeCall, "execute_code", "--project_name", projectName)
        assertFlagValue(executeCall, "execute_code", "--task_id", taskId)
        assertShellSafeInlineCode(executeCall, sentinel)
        val executionEnvelope = executeCall.successfulEnvelope("execute_code")
        val executionId = executionEnvelope.executionId()

        val feedbackCall = shellCalls[feedbackActionIndex]
        assertFlagValue(feedbackCall, "execute_feedback", "--project_name", projectName)
        assertFlagValue(feedbackCall, "execute_feedback", "--task_id", taskId)
        assertFlagValue(feedbackCall, "execute_feedback", "--execution_id", executionId)
        assertTrue(feedbackCall.hasFlag("--success_rating") && feedbackCall.hasFlag("--explanation")) {
            "execute_feedback did not carry a rating and explanation. ${summarizeCalls(shellCalls)}"
        }
        feedbackCall.successfulEnvelope("execute_feedback")

        val screenshotCall = shellCalls[screenshotActionIndex]
        val screenshotPath = "/tmp/devrig-help-first-$agentName.png"
        assertFlagValue(screenshotCall, "take_screenshot", "--project_name", projectName)
        assertFlagValue(screenshotCall, "take_screenshot", "--task_id", taskId)
        assertFlagValue(screenshotCall, "take_screenshot", "--window_id", windowId)
        assertFlagValue(screenshotCall, "take_screenshot", "--out", screenshotPath)
        val screenshotEnvelope = screenshotCall.successfulEnvelope("take_screenshot")
        assertEquals(
            screenshotPath,
            screenshotEnvelope.getValue("data").jsonObject.getValue("savedOut").jsonPrimitive.content,
            "take_screenshot did not report the requested savedOut path",
        )

        val inputCall = shellCalls[inputActionIndex]
        assertFlagValue(inputCall, "input", "--project_name", projectName)
        assertFlagValue(inputCall, "input", "--task_id", taskId)
        assertFlagValue(inputCall, "input", "--window_id", windowId)
        assertTrue(Regex("--sequence(?:=|\\s+)(?:['\"])?delay:25(?:['\"])?(?:\\s|$)").containsMatchIn(inputCall.commandText())) {
            "input was not the safe delay-only sequence. ${summarizeCalls(shellCalls)}"
        }
        inputCall.successfulEnvelope("input")

        val fetchCall = shellCalls[fetchActionIndex]
        assertFlagValue(fetchCall, "fetch_resource", "--project_name", projectName)
        assertTrue(devrigCommandHasArgumentValue(fetchCall.commandText(), DEVRIG, "mcp-steroid://prompt/skill")) {
            "fetch_resource did not pass the guide URI positionally. ${summarizeCalls(listOf(fetchCall))}"
        }
        assertTrue(!fetchCall.hasFlag("--uri")) {
            "fetch_resource did not follow help's advertised positional URI form. ${summarizeCalls(listOf(fetchCall))}"
        }
        fetchCall.successfulEnvelope("fetch_resource")

        val openCall = shellCalls[openActionIndex]
        assertFlagValue(openCall, "open_project", "--project_path", projectPath)
        assertFlagValue(openCall, "open_project", "--task_id", taskId)
        assertTrue(openCall.hasFlag("--wait")) {
            "open_project did not exercise the wait route. ${summarizeCalls(shellCalls)}"
        }
        for (optionalFlag in listOf("--trust_project", "--no-trust_project")) {
            assertTrue(!openCall.hasFlag(optionalFlag)) {
                "open_project unexpectedly used optional lifecycle flag $optionalFlag. ${summarizeCalls(shellCalls)}"
            }
        }
        if (openCall.hasFlag("--backend_name")) {
            assertFlagValue(openCall, "open_project", "--backend_name", backendName)
        }
        val opened = openCall.successfulEnvelope("open_project").toolJson()
        assertEquals(projectName, opened.getValue("project_name").jsonPrimitive.content)
        assertEquals(backendName, opened.getValue("backend_name").jsonPrimitive.content)
        assertEquals(projectPath, opened.getValue("path").jsonPrimitive.content)

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertExactMarkerLines(
            finalResponse,
            listOf(
                "HELP_ROUTE: root -> all generated commands",
                "HELP_COMMAND_ROUTE: complete",
                "MISSING_PARAMETER_HELP: complete",
                "EXECUTION_MARKER: $sentinel",
            ),
        )

        result.assertExitCode(0) { "$agentName help-first CLI experiment failed" }
    }

    private fun lifecycleHelpExperiment(agentName: String, agent: AiAgentSession) {
        val prompt = """
            # Task: audit devrig lifecycle help-to-action routes

            The packaged launcher is `$DEVRIG`. Use only your native shell tool. Do not use any other tool
            at all, including todo/planning or file tools. Do not call MCP tools, inspect repository source
            code, or inspect tests. Every shell call must be one raw launcher
            invocation with no pipes, redirects, shell variables, command substitution, aliases, functions,
            `&&`, or `;`.

            Run the exact command sequence below. Each focused help call must be immediately followed by
            the action it explains. The no-id backend actions are intentional read-only list operations.
            The agent and plugin `--check` actions are intentional read-only diagnoses and may exit non-zero
            when registration is absent; record that result and continue without retrying. `install devrig`
            is safe in this isolated container and must be the final action.

              $DEVRIG --help
              $DEVRIG help mcp
              $DEVRIG help version
              $DEVRIG version --json
              $DEVRIG help backend
              $DEVRIG backend --json
              $DEVRIG help backend download
              $DEVRIG backend download --json
              $DEVRIG help backend start
              $DEVRIG backend start --json
              $DEVRIG help backend stop
              $DEVRIG backend stop --json
              $DEVRIG help backend provision
              $DEVRIG backend provision --json
              $DEVRIG help install
              $DEVRIG install --json
              $DEVRIG help install config
              $DEVRIG install config --json
              $DEVRIG help install claude
              $DEVRIG install claude --check
              $DEVRIG help install codex
              $DEVRIG install codex --check
              $DEVRIG help install gemini
              $DEVRIG install gemini --check
              $DEVRIG help install plugin
              $DEVRIG install plugin --check
              $DEVRIG help install devrig
              $DEVRIG install devrig

            Do not start `$DEVRIG mcp`: it is a long-lived stdio JSON-RPC server whose action is exercised
            by the dedicated protocol integration test. Its focused help is the correct finite audit here.

            Your entire final response must be exactly these three lines, with no heading or explanation:
            LIFECYCLE_HELP_ROUTE: complete
            LIFECYCLE_ACTIONS: version, backend, download, start, stop, provision, install, config, claude-check, codex-check, gemini-check, plugin-check, devrig
            MCP_STDIO_ROUTE: help verified; protocol action covered by integration test
        """.trimIndent()

        val result = agent.runPrompt(prompt, timeoutSeconds = 10 * 60L).awaitForProcessFinish()
        val calls = decodeAgentToolCalls(result.rawStdout)
        assertCliOnly(calls)
        val shellCalls = calls.filter { it.isNativeShellCall() }
        assertRawDevrigOnly(shellCalls)

        val expected = lifecycleHelpCommands()
        assertEquals(
            expected,
            shellCalls.map { it.normalizedRawDevrigCommand() },
            "Lifecycle help-to-action route must be exact, consecutive, and retry-free. ${summarizeCalls(shellCalls)}",
        )

        for ((index, command) in expected.withIndex()) {
            val call = shellCalls[index]
            when {
                command == "$DEVRIG --help" -> {
                    assertTrue(call.succeeded()) { "Root help failed: ${call.resultText()}" }
                    assertTrue("Usage: devrig" in call.resultText()) { "Root help was not returned: ${call.resultText()}" }
                }
                command.startsWith("$DEVRIG help ") -> {
                    assertTrue(call.succeeded()) { "Focused help failed for '$command': ${call.resultText()}" }
                    val path = command.removePrefix("$DEVRIG help ")
                    assertTrue("Usage: devrig $path" in call.resultText()) {
                        "Focused help for '$path' returned the wrong scope: ${call.resultText()}"
                    }
                }
                command.endsWith(" --json") -> {
                    assertTrue(call.succeeded()) { "JSON lifecycle action failed for '$command': ${call.resultText()}" }
                    Json.parseToJsonElement(call.resultText()).jsonObject
                    assertTrue('\u001B' !in call.resultText()) { "JSON lifecycle action emitted ANSI: ${call.resultText()}" }
                }
                command == "$DEVRIG install plugin --check" ->
                    assertTrue(call.succeeded()) { "Plugin diagnostic failed: ${call.resultText()}" }
                command.endsWith(" --check") -> assertCompletedAgentCheck(call, command)
                else -> assertTrue(call.succeeded()) { "Lifecycle action failed for '$command': ${call.resultText()}" }
            }
        }

        val finalResponse = decodeAgentFinalResponse(result.rawStdout).orEmpty()
        assertExactMarkerLines(
            finalResponse,
            listOf(
                "LIFECYCLE_HELP_ROUTE: complete",
                "LIFECYCLE_ACTIONS: version, backend, download, start, stop, provision, install, config, " +
                    "claude-check, codex-check, gemini-check, plugin-check, devrig",
                "MCP_STDIO_ROUTE: help verified; protocol action covered by integration test",
            ),
        )

        result.assertExitCode(0) { "$agentName lifecycle CLI experiment failed" }
    }

    private fun assertCliOnly(calls: List<AgentToolCall>) {
        assertTrue(calls.isNotEmpty()) { "No Claude/Codex tool calls were decoded from raw NDJSON." }
        val nonShellCalls = calls.filterNot { it.isNativeShellCall() }
        assertTrue(nonShellCalls.isEmpty()) {
            "The CLI-only agent used a non-shell tool. ${summarizeCalls(nonShellCalls)}"
        }
    }

    private fun assertRawDevrigOnly(calls: List<AgentToolCall>) {
        val invalid = calls.filter { it.normalizedRawDevrigCommand() == null }
        assertTrue(invalid.isEmpty()) {
            "Every shell call must be one raw devrig invocation with no wrappers or shell control syntax. " +
                summarizeCalls(invalid)
        }
    }

    private fun assertExactMarkerLines(finalResponse: String, expected: List<String>) {
        val actual = finalResponse.trim().lineSequence().map(String::trimEnd).toList()
        assertEquals(expected, actual, "The agent final response must contain exactly the requested marker lines")
    }

    private fun assertTrailingMarkerLines(finalResponse: String, expected: List<String>) {
        val actual = finalResponse.trim().lineSequence().map(String::trimEnd).toList()
        assertEquals(expected, actual.takeLast(expected.size), "The agent final response must end with the markers")
    }

    private fun assertOrdered(
        indices: List<Int>,
        expectedRoute: String,
        calls: List<AgentToolCall>,
    ) {
        assertTrue(indices.all { it >= 0 } && indices.zipWithNext().all { (left, right) -> left < right }) {
            "Agent did not follow $expectedRoute; indices=$indices. ${summarizeCalls(calls)}"
        }
    }

    private fun assertImmediatelyBefore(
        firstIndex: Int,
        secondIndex: Int,
        route: String,
        calls: List<AgentToolCall>,
    ) {
        assertEquals(
            firstIndex + 1,
            secondIndex,
            "$route was not performed in consecutive shell calls. ${summarizeCalls(calls)}",
        )
    }

    private fun assertRecoverySequence(
        helpIndex: Int,
        missingIndex: Int,
        actionIndex: Int,
        command: String,
        calls: List<AgentToolCall>,
    ) {
        assertImmediatelyBefore(helpIndex, missingIndex, "$command help -> missing-value check", calls)
        assertImmediatelyBefore(missingIndex, actionIndex, "$command missing-value check -> recovery", calls)
    }

    private fun assertCommandHelp(call: AgentToolCall, command: String, vararg requiredTokens: String) {
        assertTrue(call.invokesHelpCommandRoute(command)) {
            "$command help did not use the exact `devrig help $command` route: ${call.commandText()}"
        }
        assertTrue(call.succeeded()) { "$command help failed: ${call.resultText()}" }
        val help = call.resultText()
        assertTrue("Usage: devrig $command" in help) { "$command returned unfocused help:\n$help" }
        for (token in requiredTokens) {
            assertTrue(token in help) { "$command help did not explain '$token':\n$help" }
        }
    }

    private fun assertFlagValue(call: AgentToolCall, command: String, flag: String, expectedValue: String) {
        assertTrue(call.hasFlagValue(flag, expectedValue)) {
            "$command did not pass $flag with '$expectedValue'. ${summarizeCalls(listOf(call))}"
        }
    }

    private fun assertShellSafeInlineCode(call: AgentToolCall, sentinel: String) {
        val code = "println(\"$sentinel\")"
        assertTrue(devrigCommandHasShellSafeInlineCode(call.commandText(), DEVRIG, code)) {
            "execute_code must use a shell-safe quoted inline value. " +
                summarizeCalls(listOf(call))
        }
    }

    private fun assertCompletedAgentCheck(call: AgentToolCall, command: String) {
        val text = call.resultText()
        assertTrue(call.result != null && text.isNotBlank()) {
            "Diagnostic action '$command' returned no inspectable result"
        }
        assertTrue("Current registration state:" in text) {
            "Diagnostic action '$command' did not reach the registration report: $text"
        }
        assertTrue("No drift" in text || "Drift detected" in text) {
            "Diagnostic action '$command' did not complete with a recognized diagnosis: $text"
        }
        for (crashMarker in listOf("Unexpected error", "Exception:", "Traceback", "\tat ")) {
            assertTrue(crashMarker !in text) { "Diagnostic action '$command' crashed: $text" }
        }
    }

    private fun assertMissingGuidance(call: AgentToolCall, command: String, vararg requiredText: String) {
        val envelope = call.jsonEnvelope(command, expectedError = true)
        val guidance = envelope.getValue("data").jsonObject
            .getValue("content").jsonArray.single().jsonObject
            .getValue("text").jsonPrimitive.content.lowercase()
        assertTrue("usage: devrig $command" in guidance) {
            "$command missing-value response did not include focused usage:\n$guidance"
        }
        for (expected in requiredText) {
            assertTrue(expected.lowercase() in guidance) {
                "$command missing-value response did not explain '$expected':\n$guidance"
            }
        }
    }

    private fun AgentToolCall.isNativeShellCall(): Boolean =
        toolName.equals("Bash", ignoreCase = true) || toolName == "command_execution"

    private fun AgentToolCall.commandText(): String = when (val command = arguments["command"]) {
        is JsonPrimitive -> command.content
        null -> ""
        else -> command.toString()
    }

    private fun AgentToolCall.normalizedRawDevrigCommand(): String? {
        return normalizeRawDevrigCommand(commandText(), DEVRIG)
    }

    private fun AgentToolCall.invokesRootHelp(): Boolean = normalizedRawDevrigCommand() in setOf(
        DEVRIG,
        "$DEVRIG --help",
        "$DEVRIG -h",
        "$DEVRIG help",
    )

    private fun AgentToolCall.invokesCommandHelp(command: String): Boolean =
        normalizedRawDevrigCommand() in setOf(
            "$DEVRIG $command --help",
            "$DEVRIG $command -h",
            "$DEVRIG help $command",
        )

    private fun AgentToolCall.invokesHelpCommandRoute(command: String): Boolean =
        normalizedRawDevrigCommand() == "$DEVRIG help $command"

    private fun AgentToolCall.invokesAction(command: String): Boolean = invokes(command) && !hasHelpFlag()

    private fun AgentToolCall.invokesJsonOnlyAction(command: String): Boolean =
        invokesJsonOnlyDevrigAction(commandText(), DEVRIG, command)

    private fun AgentToolCall.invokes(subcommand: String): Boolean =
        invokesDevrigCommand(commandText(), DEVRIG, subcommand)

    private fun AgentToolCall.hasFlag(flag: String): Boolean =
        devrigCommandHasFlag(commandText(), DEVRIG, flag)

    private fun AgentToolCall.hasFlagValue(flag: String, value: String): Boolean =
        devrigCommandHasFlagValue(commandText(), DEVRIG, flag, value)

    private fun AgentToolCall.hasHelpFlag(): Boolean = hasFlag("--help") || hasFlag("-h")

    private fun AgentToolCall.succeeded(): Boolean = result?.isError == false

    private fun AgentToolCall.failed(): Boolean = result?.isError == true

    private fun AgentToolCall.resultText(): String = result?.text.orEmpty()

    private fun AgentToolCall.successfulEnvelope(expectedCommand: String): JsonObject {
        assertTrue(succeeded()) { "$expectedCommand did not succeed: ${resultText()}" }
        return jsonEnvelope(expectedCommand, expectedError = false)
    }

    private fun AgentToolCall.jsonEnvelope(expectedCommand: String, expectedError: Boolean): JsonObject {
        val rawText = resultText().trim()
        val nativeFailurePrefix = Regex("^Exit code \\d+\\r?\\n").find(rawText)
        if (nativeFailurePrefix != null) {
            assertTrue(expectedError) {
                "$expectedCommand succeeded but its native tool result carried an exit-code prefix: $rawText"
            }
        }
        val text = nativeFailurePrefix
            ?.let { rawText.substring(it.range.last + 1).trim() }
            ?: rawText
        val envelope = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("tool", "command", "isError", "data"), envelope.keys, envelope.toString())
        val tool = envelope.getValue("tool").jsonObject
        assertEquals(setOf("name", "version"), tool.keys, envelope.toString())
        assertEquals("devrig", tool.getValue("name").jsonPrimitive.content, envelope.toString())
        assertTrue(tool.getValue("version").jsonPrimitive.content.isNotBlank(), "missing devrig version: $envelope")
        assertEquals(expectedCommand, envelope.getValue("command").jsonPrimitive.content, envelope.toString())
        val isError = envelope.getValue("isError").jsonPrimitive
        assertTrue(!isError.isString, "isError must be a JSON boolean: $envelope")
        assertEquals(expectedError.toString(), isError.content, envelope.toString())
        envelope.getValue("data").jsonObject
        return envelope
    }

    private fun JsonObject.toolJson(): JsonObject = getValue("data").jsonObject
            .getValue("content").jsonArray
            .single().jsonObject
            .getValue("json").jsonObject

    private fun JsonObject.firstProject(): JsonObject {
        val projects = toolJson()
            .getValue("projects").jsonArray
        assertTrue(projects.isNotEmpty()) { "list_projects returned no project in the preinstalled IDE: $this" }
        return projects.first().jsonObject
    }

    private fun JsonObject.firstProjectName(): String =
        firstProject().getValue("project_name").jsonPrimitive.content

    private fun JsonObject.windowIdFor(projectName: String): String {
        val windows = toolJson().getValue("windows").jsonArray.map { it.jsonObject }
        val window = windows.firstOrNull {
            it["project_name"]?.jsonPrimitive?.content == projectName
        }
        assertTrue(window != null) {
            "list_windows returned no window for project_name '$projectName': $this"
        }
        val selectedWindow = requireNotNull(window)
        // #456: the output key is camelCase windowId — pinned by ListWindowsToolSpecSchemaTest.
        // No window_id fallback here: hedging both spellings would imply the contract is ambiguous.
        val id = selectedWindow["windowId"]
        assertTrue(id != null) { "list_windows returned a project window without a windowId: $selectedWindow" }
        return requireNotNull(id).jsonPrimitive.content
    }

    private fun JsonObject.executionId(): String {
        val text = getValue("data").jsonObject
            .getValue("content").jsonArray
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
            .joinToString("\n")
        val match = Regex("(?m)^execution_id:\\s*(\\S+)").find(text)
        assertTrue(match != null) { "execute_code returned no execution_id in its canonical envelope: $this" }
        return requireNotNull(match).groupValues[1]
    }

    private fun summarizeCalls(calls: List<AgentToolCall>): String = calls.joinToString(
        prefix = "Calls: ",
        limit = 30,
    ) { call -> "${call.toolName}(${call.commandText()}) result=${call.result?.isError}" }

    companion object {
        private const val DEVRIG = "/home/agent/devrig"
        private val GENERATED_COMMANDS = listOf(
            "list_projects",
            "list_windows",
            "execute_code",
            "execute_feedback",
            "take_screenshot",
            "input",
            "fetch_resource",
            "open_project",
        )
        private const val EXPECTED_TASK_FIRST_SHELL_CALLS = 6
        private const val EXPECTED_HELP_FIRST_SHELL_CALLS = 23
        private const val MIN_OUTCOME_ONLY_SHELL_CALLS = 5
        private const val MAX_OUTCOME_ONLY_SHELL_CALLS = 10

        private fun lifecycleHelpCommands(): List<String> = listOf(
            "$DEVRIG --help",
            "$DEVRIG help mcp",
            "$DEVRIG help version",
            "$DEVRIG version --json",
            "$DEVRIG help backend",
            "$DEVRIG backend --json",
            "$DEVRIG help backend download",
            "$DEVRIG backend download --json",
            "$DEVRIG help backend start",
            "$DEVRIG backend start --json",
            "$DEVRIG help backend stop",
            "$DEVRIG backend stop --json",
            "$DEVRIG help backend provision",
            "$DEVRIG backend provision --json",
            "$DEVRIG help install",
            "$DEVRIG install --json",
            "$DEVRIG help install config",
            "$DEVRIG install config --json",
            "$DEVRIG help install claude",
            "$DEVRIG install claude --check",
            "$DEVRIG help install codex",
            "$DEVRIG install codex --check",
            "$DEVRIG help install gemini",
            "$DEVRIG install gemini --check",
            "$DEVRIG help install plugin",
            "$DEVRIG install plugin --check",
            "$DEVRIG help install devrig",
            "$DEVRIG install devrig",
        )

        @JvmStatic
        val lifetime by lazy { CloseableStackHost() }

        val session by lazy {
            IntelliJContainer.create(
                lifetime,
                IntelliJContainerOpts(
                    consoleTitle = "devrig-cli-agent-usability",
                    project = IntelliJProject.EmptyProject,
                    aiMode = AiMode.AI_DEVRIG,
                ),
            ).waitForProjectReady()
        }

        val claude by lazy {
            ConsoleAwareAgentSession(
                delegate = DockerClaudeSession.create(session.scope),
                console = session.console,
                agentName = "claude-cli-usability",
                logDir = session.runDirInContainer,
            )
        }

        val codex by lazy {
            ConsoleAwareAgentSession(
                delegate = DockerCodexSession.create(session.scope),
                console = session.console,
                agentName = "codex-cli-usability",
                logDir = session.runDirInContainer,
            )
        }

        @JvmStatic
        @BeforeAll
        fun beforeAll() {
            session.toString()
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            lifetime.closeAllStacks()
        }
    }
}
