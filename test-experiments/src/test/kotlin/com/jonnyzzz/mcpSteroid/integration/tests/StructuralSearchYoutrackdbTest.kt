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
 * MCP-win experiment: **type-exact structural search** on youtrackdb (jonnyzzz/mcp-steroid#169
 * follow-up). The agent must find EVERY callsite where a no-arg `get()` is invoked on an expression
 * whose type is `java.util.Optional<T>` — the classic "unsafe Optional.get()" audit.
 *
 * With MCP the agent runs IntelliJ Structural Search (`Matcher` over
 * `GlobalSearchScope.projectScope(project)`, apostrophe pattern with an `:[exprtype(...)]`
 * constraint per the `mcp-steroid://skill/structural-search*` articles) and gets the AST-exact,
 * type-resolved answer. Without MCP, `grep '.get()'` faces ~940 no-arg `get()` callsites where only
 * ~36 are Optional — every Atomic, ThreadLocal, Supplier, Future, WeakReference, ByteBuffer,
 * Netty-attr and gremlin-Traverser `.get()` is an over-match, and chained receivers (`stream.findFirst().get()`,
 * `reduce(...).get()`, `getDatabase().get()`) require type resolution grep does not have.
 *
 * Scored with the pure [scoreSsrOptionalGet] against [GROUND_TRUTH_FILES] — a hand-derived,
 * type-checked list valid ONLY at the revision [IntelliJProject.YouTrackDbPinnedProject] pins.
 * Verdict (all files found, none extra) is emitted as an `[ARENA]` block; correctness is a
 * dashboard metric, not a hard pass gate. A/B per agent; with-MCP legs assert exec_code evidence.
 *
 * The MCP-only SSR *skill audit* (profile registry, predefined templates, IMPROVEMENTS reflection)
 * that used to live here lives on unchanged in [StructuralSearchYoutrackdb261Test] /
 * `StructuralSearchYoutrackdbPromptShared.kt`.
 */
class StructuralSearchYoutrackdbTest {

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
                consoleTitle = "ssr-youtrackdb-$agentName-$modeLabel",
                project = IntelliJProject.YouTrackDbPinnedProject,
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            )).waitForProjectReady(buildSystem = BuildSystem.MAVEN)

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

            val score = scoreSsrOptionalGet(combined, GROUND_TRUTH_FILES)
            println("[TEST] ssr optional-get [$agentName+$modeLabel] exact=${score.exact} " +
                    "found=${score.foundFiles.size}/${GROUND_TRUTH_FILES.size} " +
                    "missed=${score.missedFiles} falsePos=${score.falsePositiveFiles} " +
                    "reportedCount=${score.reportedCount} (truth=$GROUND_TRUTH_MATCH_COUNT)")

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.exact,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "found=${score.foundFiles.size}/${GROUND_TRUTH_FILES.size} files " +
                        "missed=${score.missedFiles.size} falsePos=${score.falsePositiveFiles.size} " +
                        "count=${score.reportedCount}/$GROUND_TRUTH_MATCH_COUNT",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    /** The task text both legs share — only the tooling instructions differ. */
    private fun taskText(): String = buildString {
        appendLine("Task: find EVERY callsite in the project where the no-argument method `get()` is invoked")
        appendLine("on an expression whose type is `java.util.Optional<T>` — including callsites where the")
        appendLine("Optional comes from a method-call chain (e.g. `stream().findFirst().get()`,")
        appendLine("`somethingReturningOptional().get()`), not just from local variables typed `Optional<...>`.")
        appendLine()
        appendLine("Scope: main AND test sources of the Maven reactor modules only. The `lucene/` directory is")
        appendLine("NOT part of the root Maven build — exclude any results under `lucene/`.")
        appendLine("Do NOT count `get()` calls on other types (Atomic*, ThreadLocal, Supplier, Future,")
        appendLine("WeakReference, ByteBuffer, ...) or `get(...)` calls that take arguments.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("OPTIONAL_GET_MATCHES: <total count of matching callsites>")
        appendLine("MATCH: <path/relative/to/repo/root/File.java>:<line>   <- one line per callsite")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The youtrackdb project is open in IntelliJ IDEA — a multi-module Maven Java project.")
        appendLine()
        appendLine("Use IntelliJ Structural Search and Replace (SSR) via `steroid_execute_code`. The recipe")
        appendLine("lives in these skill articles — fetch them via `steroid_fetch_resource` before writing code:")
        appendLine()
        appendLine("- `mcp-steroid://skill/structural-search` — overview")
        appendLine("- `mcp-steroid://skill/structural-search-api-recipe` — canonical Kotlin recipe")
        appendLine("- `mcp-steroid://skill/structural-search-syntax` — template language")
        appendLine()
        appendLine(taskText())
        appendLine()
        appendLine("Hard rules (from `mcp-steroid://skill/structural-search-api-recipe`):")
        appendLine("- Use the apostrophe form with a `:[exprtype(...)]` constraint on the receiver.")
        appendLine("- Search over `GlobalSearchScope.projectScope(project)`; drop matches under `lucene/`")
        appendLine("  from the report afterwards.")
        appendLine("- Always call `Matcher.validate(project, options)` BEFORE constructing the `Matcher`.")
        appendLine("- Do NOT wrap `Matcher.findMatches(...)` in an outer `readAction { }`.")
        appendLine("- Do NOT call `StringToConstraintsTransformer.transformCriteria(...)` twice on the same")
        appendLine("  `MatchOptions` — it overwrites the search pattern.")
        appendLine()
        appendLine("Additional output marker:")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The youtrackdb project is checked out — a multi-module Maven Java project.")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find/awk).")
        appendLine()
        appendLine(taskText())
        appendLine()
        appendLine("Beware: many unrelated types also have a no-arg `get()`; a naive text search will both")
        appendLine("over-match them and miss Optionals produced by method-call chains.")
    }

    companion object {
        private const val SCENARIO = "youtrackdb__structural_search"

        /**
         * Hand-derived ground truth, valid ONLY at the revision
         * [IntelliJProject.YouTrackDbPinnedProject] pins (tag `0.5.0-20260428.132443-44cf982-SNAPSHOT`
         * == commit `44cf982894c37a62aba3319b5024f76f6ccae97c`). Every no-arg `get()` whose receiver
         * expression resolves to `java.util.Optional<T>`, per file (line numbers in the comments;
         * `lucene/` excluded — it is not a module of the root Maven reactor).
         *
         * The derivation swept ALL 942 no-arg `.get()` callsites at that revision: files declaring
         * `Optional<`, files assigning `var x = Optional.…`, same-line chains `…().get()`,
         * line-leading `.get()` continuations, and a receiver-token frequency sweep classifying every
         * remaining name (Atomic*, ThreadLocal, Supplier, Future, WeakReference, ByteBuffer, Netty
         * Attribute, gremlin Traverser, … = non-Optional).
         */
        private val GROUND_TRUTH_FILES: Set<String> = setOf(
            // 55, 117
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/VersionedIndexOps.java",
            // 358
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java",
            // 29
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionId.java",
            // 107
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/tx/FrontendTransactionIndexChangesPerKey.java",
            // 694, 783, 1738, 1889
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTree.java",
            // 998, 1547, 1579, 2553, 2638
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java",
            // 412 — authenticationInfo.getDatabase().get(), Optional via method return
            "core/src/main/java/com/jetbrains/youtrackdb/internal/core/db/YouTrackDBInternalEmbedded.java",
            // 30 — traversal.getGraph().get(), Optional via gremlin library API
            "core/src/main/java/com/jetbrains/youtrackdb/internal/common/profiler/monitoring/YTDBQueryMetricsStrategy.java",
            // 54 — conf.get(); the chained ).get() on line 55 is Supplier.get()
            "driver/src/main/java/com/jetbrains/youtrackdb/internal/driver/YTDBDriverRemoteTraversal.java",
            // 484 (x2), 494, 670, 838, 881, 956
            "server/src/main/java/com/jetbrains/youtrackdb/internal/server/plugin/gremlin/YTDBAbstractOpProcessor.java",
            // 226, 228, 240, 241, 243, 244 — `var methodAnnotation = Optional.ofNullable(...)`
            "gremlin-annotations/src/main/java/com/jetbrains/youtrackdb/internal/annotations/gremlin/dsl/GremlinDslProcessor.java",
            // 37, 48, 77, 79, 108 — toStream().findFirst().get()
            "core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/GraphCountStrategyTest.java",
            // 33 — stream()...reduce(Integer::sum).get()
            "core/src/test/java/com/jetbrains/youtrackdb/internal/core/sql/executor/ResultSetTest.java",
        )

        /** Total match-site count over [GROUND_TRUTH_FILES] (line 484 of YTDBAbstractOpProcessor has two). */
        private const val GROUND_TRUTH_MATCH_COUNT = 36
    }
}
