/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * MCP-win experiment: **Kotlin↔Java cross-language find-usages** (jonnyzzz/mcp-steroid#169 family) —
 * the purest grep-killer. In OkHttp (pinned tag `parent-4.12.0`) the Kotlin property
 * `okhttp3.mockwebserver.RecordedRequest.requestLine` is consumed from the legacy Java test suite
 * through its GENERATED getter `getRequestLine()`. The declared name and the call-site spelling
 * differ in case (`requestLine` vs `getRequestLine`), so:
 *  - a case-sensitive search for the declared name finds ZERO of the 58 Java call sites,
 *  - a search for the bare identifier over-matches same-named LOCAL VARIABLES that are not
 *    property usages at all (`MockWebServer.kt`, `Http1ExchangeCodec.kt`, `RealConnection.kt`,
 *    and even `QueueDispatcher.kt:35-36`, two lines below a real usage),
 *  - okhttp core also has an unrelated `okhttp3.internal.http.RequestLine` CLASS that any
 *    case-insensitive sweep pulls in.
 *
 * With MCP the agent resolves the property via PSI and runs `ReferencesSearch` — one query returns
 * every Kotlin property read AND every Java getter call. Without MCP the agent must know the
 * Kotlin property↔getter mapping and run two disciplined searches, filtering the shadowing locals
 * by hand.
 *
 * Ground truth ([REQUIRED_USAGES], 60 usages) was hand-derived at the pinned tag: every
 * `getRequestLine()` line in `*.java` (58 — receiver verified `RecordedRequest`, no line has two
 * occurrences, no method references exist) plus the two Kotlin property reads outside the declaring
 * file. Reads inside the declaring file are [OPTIONAL_USAGES] — never required, never penalized.
 *
 * Verdict ([scoreInteropUsages]): every required usage reported AND no false positives — emitted
 * as an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard
 * metric, not a hard gate.
 */
class InteropFindUsagesTest {

    // 50 min: read-only task. OkHttp is mid-size (Gradle 7.5, ~20 JVM-only modules) — container
    // start + Gradle import + indexing fit well within, and the enumeration itself is a single
    // find-usages query (with MCP) or a couple of searches (without).

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "okhttp-interop-$agentName-$modeLabel",
                project = IntelliJProject.OkHttpPinnedProject,
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            )).waitForProjectReady(buildSystem = BuildSystem.GRADLE)

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                else -> error("Unknown agent: $agentName")
            }

            val startedAt = System.currentTimeMillis()
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 1800)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreInteropUsages(combined, REQUIRED_USAGES, OPTIONAL_USAGES)
            val requiredTotal = REQUIRED_USAGES.values.sumOf { it.size }
            val foundTotal = score.foundRequired.values.sumOf { it.size }
            println("[TEST] okhttp interop-usages [$agentName+$modeLabel] exact=${score.exact} " +
                    "complete=${score.complete} required=$foundTotal/$requiredTotal " +
                    "falsePositives=${score.falsePositives.size} reported=${score.reportedPairCount}")
            if (score.missedRequired.isNotEmpty()) {
                println("[TEST]   missed required: ${score.missedRequired}")
            }
            if (score.falsePositives.isNotEmpty()) {
                println("[TEST]   false positives: ${score.falsePositives}")
            }

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.exact,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "required=$foundTotal/$requiredTotal " +
                        "missedFiles=${score.missedRequired.keys.size} " +
                        "falsePositives=${score.falsePositives.size} complete=${score.complete}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: enumerate EVERY usage of the Kotlin property `$SYMBOL`")
        appendLine("across the WHOLE repository — BOTH languages:")
        appendLine("- Kotlin reads of the property (`.requestLine`),")
        appendLine("- Java calls of its generated getter (`getRequestLine()`).")
        appendLine()
        appendLine("Count only real references to THAT property in `.kt` and `.java` sources.")
        appendLine("Local variables that merely share the name `requestLine` are NOT usages.")
        appendLine("Do NOT include the property declaration line itself.")
        appendLine()
        appendLine("Output (markers on their own lines, one USAGE line per reference):")
        appendLine("USAGES_FOUND: <total count>")
        appendLine("USAGE: <path/relative/to/repo>:<1-based line>")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The OkHttp project is open in IntelliJ IDEA — a mixed Kotlin+Java Gradle project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's resolve-based search via `steroid_execute_code`: find the class")
        appendLine("`okhttp3.mockwebserver.RecordedRequest` (e.g. `JavaPsiFacade.findClass`), take the")
        appendLine("`requestLine` property (the light getter's `navigationElement` is the Kotlin parameter),")
        appendLine("and run `ReferencesSearch.search(...)` over the project scope — it returns Kotlin property")
        appendLine("reads AND Java getter calls in one query. Convert each reference to file + line via its")
        appendLine("PSI element's document. Do NOT rely on text search.")
        appendLine()
        appendLine("Additional output marker:")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The OkHttp project is checked out (a mixed Kotlin+Java Gradle project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find).")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Beware: Kotlin and Java spell member access differently, and unrelated identifiers")
        appendLine("share this name — a single naive text search will both miss usages and over-match.")
    }

    companion object {
        private const val SCENARIO = "okhttp__interop_usages"
        private const val SYMBOL = "okhttp3.mockwebserver.RecordedRequest.requestLine"

        // Ground truth hand-derived at the pinned `parent-4.12.0` tag (see class kdoc for the
        // derivation sweep). 58 Java getter call sites + 2 Kotlin property reads outside the
        // declaring file. The 58 Java sites are the CROSS-LANGUAGE ones a case-sensitive search
        // for the declared name misses entirely.
        private val REQUIRED_USAGES: Map<String, Set<Int>> = mapOf(
            "mockwebserver/src/test/java/okhttp3/mockwebserver/MockWebServerTest.java" to
                    setOf(152, 172, 174, 458),
            "okhttp/src/test/java/okhttp3/URLConnectionTest.java" to setOf(
                511, 619, 622, 732, 880, 924, 929, 964, 1041, 1046, 1052, 1838, 1879, 1914, 1987,
                2037, 2039, 2065, 2067, 2289, 2293, 2311, 2315, 2335, 2428, 2454, 2484, 2494, 2972,
            ),
            "okhttp/src/test/java/okhttp3/CallTest.java" to setOf(
                1855, 1859, 2085, 2089, 3131, 3135, 3139, 3163, 3167, 3583, 3587,
            ),
            "okhttp/src/test/java/okhttp3/CacheTest.java" to setOf(385, 392, 400),
            "okhttp/src/test/java/okhttp3/internal/http2/HttpOverHttp2Test.java" to setOf(
                185, 212, 235, 272, 303, 337, 513, 1210, 1216, 1238, 1244,
            ),
            // Kotlin property-syntax reads outside the declaring file. QueueDispatcher.kt:34 is a
            // real read (`request.requestLine`) — while lines 35-36 read a same-named LOCAL and
            // must NOT be reported. KotlinSourceModernTest.kt lives under `src/test/java`, a .kt
            // file in a java source root — a `--include=*.kt`-only sweep of kotlin dirs misses it.
            "mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt" to setOf(34),
            "okhttp/src/test/java/okhttp3/KotlinSourceModernTest.kt" to setOf(936),
        )

        // Reads inside the declaring file (init block, toString) plus the declaration line: agents
        // legitimately disagree on whether to list them — reported or not, they never count as
        // false positives, and they are never required.
        private val OPTIONAL_USAGES: Map<String, Set<Int>> = mapOf(
            "mockwebserver/src/main/kotlin/okhttp3/mockwebserver/RecordedRequest.kt" to
                    setOf(33, 96, 97, 98, 99, 100, 137),
        )
    }
}
