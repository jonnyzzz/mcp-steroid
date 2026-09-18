/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Regression contract for issue #464: index-dependent PSI search recipes must not run
 * under a plain `readAction { }`.
 *
 * The runtime guidance (execute-code tool description, threading skill) is explicit that the
 * automatic smart-mode wait is only a point-in-time check — IntelliJ can re-enter dumb mode
 * at any moment afterwards, so an index-backed query inside `readAction { }` fails
 * nondeterministically with `IndexNotReadyException` (the race issue #29 fixed at the
 * runtime level). Every prompt example that runs an index-backed search must wrap the whole
 * query and its result snapshot in `smartReadAction { }`.
 *
 * Two rules, both over `prompts/src/main/prompts/{@literal **}/{@literal *}.md`:
 * 1. Inside ` ```kotlin ``` ` fences, an index-dependent search call must be lexically
 *    enclosed in a `smartReadAction { }` block (at any nesting depth).
 * 2. Outside kotlin fences (prose, decision tables, bare fences), a line must not recommend
 *    a plain `readAction {` together with an index-dependent search API.
 */
class IndexDependentSearchContractTest {

    /** Search entry points that read stub/reference indexes and throw `IndexNotReadyException` in dumb mode. */
    private val indexDependentSearches = listOf(
        "ReferencesSearch",
        "MethodReferencesSearch",
        "ClassInheritorsSearch",
        "OverridingMethodsSearch",
        "AnnotatedElementsSearch",
        "DefinitionsScopedSearch",
        "PsiSearchHelper",
    )

    /** A member access on one of the search classes, e.g. `ReferencesSearch.search(...)` or `ReferencesSearch.*` in a table. */
    private val searchCallPattern = Regex("""\b(${indexDependentSearches.joinToString("|")})\s*\.""")

    /** A plain `readAction {` (optionally with call args) — not `smartReadAction {`, not `return@readAction`. */
    private val plainReadActionPattern = Regex("""(?<![A-Za-z@$])readAction\s*(?:\([^()]*\)\s*)?\{""")

    /** `identifier [(args)] {` — associates a `{` with the call that owns the block. */
    private val blockOpenerPattern = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*(?:\([^()]*\)\s*)?\{""")

    private val promptsRoot: Path =
        ProjectHomeDirectory.requireProjectHomeDirectory().resolve("prompts/src/main/prompts")

    private fun promptFiles(): List<Path> {
        check(Files.isDirectory(promptsRoot)) { "prompts root not found: $promptsRoot" }
        return Files.walk(promptsRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
                .collect(Collectors.toList())
        }
    }

    @Test
    fun testKotlinFenceSearchCallsRunUnderSmartReadAction() {
        val violations = mutableListOf<String>()

        for (file in promptFiles()) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)
            var inKotlinFence = false
            var inOtherFence = false
            // One entry per currently-open `{`; the value is the identifier owning the block (null = anonymous).
            val blockStack = ArrayDeque<String?>()

            lines.forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```")) {
                    when {
                        inKotlinFence -> inKotlinFence = false
                        inOtherFence -> inOtherFence = false
                        trimmed.startsWith("```kotlin") -> {
                            inKotlinFence = true
                            blockStack.clear()
                        }
                        else -> inOtherFence = true
                    }
                    return@forEachIndexed
                }
                if (!inKotlinFence) return@forEachIndexed
                if (trimmed.startsWith("import ")) return@forEachIndexed

                scanFenceLine(line, blockStack) { searchName ->
                    if (blockStack.none { it == "smartReadAction" }) {
                        violations.add(
                            "$relPath:${index + 1}: $searchName runs outside smartReadAction { } — " +
                                "wrap the whole query and its result snapshot in smartReadAction { } " +
                                "(plain readAction races dumb-mode re-entry: IndexNotReadyException, issue #464)"
                        )
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Index-dependent search calls outside smartReadAction in prompt kotlin fences:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun testProseNeverPairsPlainReadActionWithIndexDependentSearch() {
        val violations = mutableListOf<String>()

        for (file in promptFiles()) {
            val lines = Files.readAllLines(file)
            val relPath = promptsRoot.relativize(file)
            var inKotlinFence = false
            var inOtherFence = false

            lines.forEachIndexed { index, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("```")) {
                    when {
                        inKotlinFence -> inKotlinFence = false
                        inOtherFence -> inOtherFence = false
                        trimmed.startsWith("```kotlin") -> inKotlinFence = true
                        else -> inOtherFence = true
                    }
                    return@forEachIndexed
                }
                // Kotlin fences are covered (with block-structure awareness) by the fence test above.
                if (inKotlinFence) return@forEachIndexed

                if (plainReadActionPattern.containsMatchIn(line) && searchCallPattern.containsMatchIn(line)) {
                    violations.add(
                        "$relPath:${index + 1}: recommends plain readAction { } together with an index-dependent " +
                            "search API — recommend smartReadAction { } instead (issue #464): $trimmed"
                    )
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Prose/table lines pairing plain readAction with index-dependent searches:\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * Walks one fence line character by character, keeping [blockStack] in sync with `{`/`}`
     * nesting, and reports every index-dependent search call at the stack state where it occurs.
     *
     * Braces inside string templates (`${'$'}{...}`) are balanced, so they do not corrupt the
     * depth tracking; the identifier-ownership heuristic only needs to recognize the
     * `smartReadAction {` / `smartReadAction(project) {` openers.
     */
    private fun scanFenceLine(line: String, blockStack: ArrayDeque<String?>, onSearchCall: (String) -> Unit) {
        val openerAtBrace = mutableMapOf<Int, String>()
        for (match in blockOpenerPattern.findAll(line)) {
            openerAtBrace[match.range.last] = match.groupValues[1]
        }
        val searchAt = mutableMapOf<Int, String>()
        for (match in searchCallPattern.findAll(line)) {
            searchAt[match.range.first] = match.groupValues[1]
        }
        for (i in line.indices) {
            searchAt[i]?.let(onSearchCall)
            when (line[i]) {
                '{' -> blockStack.addLast(openerAtBrace[i])
                '}' -> if (blockStack.isNotEmpty()) blockStack.removeLast()
            }
        }
    }
}
