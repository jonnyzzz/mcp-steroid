/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * #213 / #179 regression guard: `EmptyProgressIndicator` must not come back.
 *
 * `EmptyProgressIndicator` is a cancellation black hole — nobody ever cancels a freshly
 * constructed one, and its `start()` even CLEARS the cancelled flag. Handing it to a blocking
 * API (`InspectionEngine.inspectEx` was the offender, McpScriptContextImpl.kt) severs the
 * execution's timeout/cancel from the operation: the rest-24/service-45 evals wedged whole
 * runs on it. The execution-scoped replacement is [com.jonnyzzz.mcpSteroid.execution.McpExecutionProgressIndicator]
 * behind `McpScriptContext.progressIndicator`.
 *
 * Guarded surfaces:
 *  - production Kotlin under `ij-plugin/src/main/kotlin` (non-comment lines), and
 *  - every ` ```kotlin ` fence in the prompt corpus (`prompts/src/main/prompts`) — recipes
 *    teach agents patterns, so a fence constructing `EmptyProgressIndicator()` re-teaches the
 *    bug (Tenet 2: recipes ship transferable skills).
 *
 * `EmptyProgressIndicatorBase` (the public base of the sticky-cancel indicator) is allowed —
 * the pattern matches the exact class name only. Comment/KDoc mentions in production code are
 * allowed too: the `progressIndicator` KDoc explicitly warns "never construct
 * EmptyProgressIndicator()".
 */
class NoEmptyProgressIndicatorUsageTest : BasePlatformTestCase() {

    companion object {
        /** Matches the class name but not `EmptyProgressIndicatorBase`. */
        private val FORBIDDEN = Regex("""EmptyProgressIndicator(?!Base)""")

        private const val PRODUCTION_SOURCE_ROOT = "ij-plugin/src/main/kotlin"
        private const val PROMPTS_ROOT = "prompts/src/main/prompts"
    }

    fun testNoEmptyProgressIndicatorInProductionKotlin() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val root = projectHome.resolve(PRODUCTION_SOURCE_ROOT)
        assertTrue("production source root must exist: $root", Files.isDirectory(root))

        val violations = mutableListOf<String>()
        for (file in collectFiles(root, ".kt")) {
            Files.readAllLines(file).forEachIndexed { index, line ->
                val trimmed = line.trim()
                // Comment/KDoc lines may mention the class to warn against it.
                val isComment = trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
                if (!isComment && FORBIDDEN.containsMatchIn(line)) {
                    violations.add("${projectHome.relativize(file)}:${index + 1}: $trimmed")
                }
            }
        }

        assertTrue(
            "EmptyProgressIndicator found in production code — it severs the execution's " +
                "cancellation (#179/#213). Use McpScriptContext.progressIndicator (scripts) or " +
                "an execution-scoped McpExecutionProgressIndicator instead.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    fun testNoEmptyProgressIndicatorInPromptKotlinFences() {
        val projectHome = ProjectHomeDirectory.requireProjectHomeDirectory()
        val root = projectHome.resolve(PROMPTS_ROOT)
        assertTrue("prompts root must exist: $root", Files.isDirectory(root))

        val violations = mutableListOf<String>()
        for (file in collectFiles(root, ".md")) {
            var insideKotlinFence = false
            Files.readAllLines(file).forEachIndexed { index, line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("```")) {
                    // ```kotlin (with optional [FILTER] annotations) opens a compiled fence;
                    // any other ``` line either opens a bare fence or closes the current one.
                    insideKotlinFence = !insideKotlinFence && trimmed.removePrefix("```").startsWith("kotlin")
                    return@forEachIndexed
                }
                if (insideKotlinFence && FORBIDDEN.containsMatchIn(line)) {
                    violations.add("${projectHome.relativize(file)}:${index + 1}: $trimmed")
                }
            }
        }

        assertTrue(
            "EmptyProgressIndicator found in a prompt kotlin fence — recipes must pass the " +
                "script context's `progressIndicator` instead (#179/#213), or the taught pattern " +
                "re-severs script timeouts from blocking IDE calls.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    private fun collectFiles(root: Path, extension: String): List<Path> {
        return Files.walk(root).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(extension) }
                .collect(Collectors.toList())
        }
    }
}
