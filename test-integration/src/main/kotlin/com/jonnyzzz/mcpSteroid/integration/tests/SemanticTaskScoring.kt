/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Pure scorers for the "MCP wins on whole-project semantic queries" experiments (jonnyzzz/mcp-steroid#169
 * follow-up). Each takes the agent's textual answer and a known ground truth and returns a structured,
 * mode-independent verdict — so a with-MCP run and a without-MCP (grep/shell) run are scored identically
 * and the dashboard can show whether MCP's exact PSI answer beats grep's incomplete one. No IDE / Docker /
 * agent needed → unit-tested directly.
 */

/** A fully-qualified type name extractor: matches `com.foo.Bar`, nested `com.foo.Bar.Baz`, `Outer$Inner`. */
private val FQN = Regex("""\b([a-z][\w]*(?:\.[a-z][\w]*)*\.[A-Z][\w$]*)\b""")

data class TypeHierarchyScore(
    /** FQNs the agent reported as subtypes (from `SUBTYPE:` markers, else any FQN in the answer). */
    val reported: Set<String>,
    /** Required subtypes the agent FAILED to report — the transitive / cross-module ones grep misses. */
    val missingRequired: Set<String>,
    val reportedCount: Int,
    /** True when every required subtype was reported AND at least [minTotal] subtypes were listed. */
    val complete: Boolean,
)

/**
 * Score a transitive type-hierarchy answer for completeness.
 *
 * @param output       the agent's answer text
 * @param required     FQNs that MUST appear — pick the transitive (indirect) / cross-module implementors
 *                     a naive `grep "implements X"` would miss; finding them proves a real hierarchy walk.
 * @param minTotal     minimum number of distinct subtypes expected (guards against a near-empty answer).
 */
fun scoreTypeHierarchy(output: String, required: Set<String>, minTotal: Int): TypeHierarchyScore {
    // Prefer explicit `SUBTYPE: <fqn>` markers; if the agent used a different layout, fall back to every
    // FQN that appears in the answer body.
    val marked = Regex("""(?im)^\s*[*_`>#-]*\s*SUBTYPE\s*[*_`>#-]*\s*:\s*([\w.$]+)""")
        .findAll(output).map { it.groupValues[1].trim().trim('.') }.toSet()
    val reported = marked.ifEmpty { FQN.findAll(output).map { it.groupValues[1] }.toSet() }

    // A required subtype counts as found if it (or its simple name as a distinct token) was reported.
    val missing = required.filterNot { req ->
        req in reported || reported.any { it.endsWith(".${req.substringAfterLast('.')}") }
    }.toSet()

    return TypeHierarchyScore(
        reported = reported,
        missingRequired = missing,
        reportedCount = reported.size,
        complete = missing.isEmpty() && reported.size >= minTotal,
    )
}

data class RenameSafetyScore(
    val renameDone: Boolean,
    /** The post-rename build/compile result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** A safe rename = it was performed AND the project still compiles afterwards. */
    val safe: Boolean,
)

/**
 * Score a project-wide rename for SAFETY. With MCP the agent uses the IDE's rename refactoring (updates
 * every reference, build stays green); without MCP a sed/text rename over- or under-matches and breaks
 * compilation. The verdict is whether the rename was performed AND the project still builds.
 *
 * Expected markers (the prompt asks the agent to compile after renaming and report):
 *   RENAME_DONE: yes
 *   BUILD_AFTER_RENAME: SUCCESS | FAILURE
 */
fun scoreRenameSafety(output: String): RenameSafetyScore {
    val renameDone = findMarkerValue(output, "RENAME_DONE", "Rename done")?.equals("yes", ignoreCase = true) == true
    val build = findMarkerValue(output, "BUILD_AFTER_RENAME", "Build after rename")
    val buildGreen = build?.let {
        when {
            it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
            it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("broke", ignoreCase = true) -> false
            else -> null
        }
    }
    return RenameSafetyScore(renameDone = renameDone, buildGreen = buildGreen, safe = renameDone && buildGreen == true)
}

data class InspectionScore(
    /** The agent's self-reported `ISSUES_FOUND:` count (informational only — never trusted for the verdict). */
    val issuesFound: Int?,
    /** `file simple name → line numbers` parsed from the agent's `ISSUE: <path>:<line>` markers. */
    val reportedLines: Map<String, Set<Int>>,
    /** Total number of reported `file:line` pairs. */
    val reportedCount: Int,
    /** Ground-truth `file:line` pairs the agent actually hit (±1 line tolerance, each consumed once). */
    val matchedLines: Map<String, Set<Int>>,
    val matchedCount: Int,
    /** True when enough ground-truth pairs were hit AND the answer is not a shotgun spam of cast lines. */
    val detected: Boolean,
)

/**
 * Score a "run IDE inspections + report issues" answer against a known ground truth of `file:line`
 * pairs (for the Keycloak scenario: the genuinely redundant casts found by `javac -Xlint:cast` on
 * the pinned 26.6.4 tag). With MCP the agent runs IntelliJ's RedundantCast inspection (type
 * inference → exact findings); grep sees cast syntax but cannot determine redundancy.
 *
 * The verdict is evidence-based, NOT self-report-based: CI builds 991971406/991971408 showed the
 * old count-only scorer rewarding a hallucinated "ISSUES_FOUND: 17" (produced with zero tool calls)
 * and rejecting a truthful "ISSUES_FOUND: 0". Now:
 *  - each reported `ISSUE: <path>:<line>` is matched against [expected] by file simple name and
 *    line (±1 tolerance for multi-line expressions; each expected line consumes at most one
 *    reported line and vice versa);
 *  - `detected` requires at least [minMatches] true positives AND at most `3 × |expected|` total
 *    reported pairs — listing every cast in every candidate file cannot win by luck.
 *
 * Expected markers:
 *   ISSUES_FOUND: <count>
 *   ISSUE: <path>:<line> — <description>
 *
 * @param expected ground truth: file simple name → the line numbers of the real issues.
 * @param minMatches how many ground-truth pairs must be hit to count as detected.
 */
fun scoreInspections(output: String, expected: Map<String, Set<Int>>, minMatches: Int): InspectionScore {
    // Strip markdown code/bold marks so `path.java:112` and **path.java:112** parse the same.
    // Underscores are NOT stripped — they are significant in marker names (ISSUES_FOUND) and paths.
    val text = output.replace(Regex("[`*]"), "")
    val count = findMarkerValue(text, "ISSUES_FOUND", "Issues found")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    // Every `ISSUE:` marker line contributes one (file simple name, line) pair. The file-name
    // pattern has no `/`, so it naturally captures the last path segment.
    val reported = mutableMapOf<String, MutableList<Int>>()
    Regex("""(?im)^\s*[-•>#\s]*ISSUE\s*:\s*(.+)$""").findAll(text).forEach { issue ->
        val m = Regex("""([\w$.-]+\.java)\s*:\s*(\d+)""").find(issue.groupValues[1]) ?: return@forEach
        reported.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].toInt())
    }
    val reportedCount = reported.values.sumOf { it.size }

    // Greedy bipartite match per file: exact line first, then ±1; each reported line consumed once.
    val matched = mutableMapOf<String, MutableSet<Int>>()
    for ((file, expectedLines) in expected) {
        val available = reported[file]?.toMutableList() ?: continue
        for (tolerance in 0..1) {
            for (expectedLine in expectedLines.sorted()) {
                if (expectedLine in (matched[file] ?: emptySet<Int>())) continue
                val hit = available.firstOrNull { kotlin.math.abs(it - expectedLine) <= tolerance } ?: continue
                available.remove(hit)
                matched.getOrPut(file) { mutableSetOf() }.add(expectedLine)
            }
        }
    }
    val matchedCount = matched.values.sumOf { it.size }
    val expectedCount = expected.values.sumOf { it.size }

    return InspectionScore(
        issuesFound = count,
        reportedLines = reported.mapValues { it.value.toSet() },
        reportedCount = reportedCount,
        matchedLines = matched,
        matchedCount = matchedCount,
        detected = matchedCount >= minMatches && reportedCount <= 3 * expectedCount,
    )
}

data class RootCauseScore(
    val mentionsIgnoredReturn: Boolean,
    val mentionsNewList: Boolean,
) {
    val pass: Boolean get() = mentionsIgnoredReturn && mentionsNewList
}

/**
 * Score the ROOT_CAUSE explanation for the sortedByDescending debugger scenario: the agent must state
 * BOTH that `sortedByDescending` produces a new list (does not mutate) AND that its return value is
 * ignored / never assigned back.
 *
 * Matching runs on a markdown-normalized copy of the text — backticks/asterisks stripped, punctuation
 * collapsed to spaces — because agents format code identifiers ("returns a NEW sorted `List`", "the
 * original `players` list"), which broke raw substring patterns on two real CI runs (988635686,
 * 991971410) despite semantically perfect answers.
 */
fun scoreSortedByDescendingRootCause(rootCause: String): RootCauseScore {
    // Normalize: lowercase, drop markdown emphasis/code marks and punctuation, collapse whitespace.
    val text = rootCause.lowercase()
        .replace(Regex("[`*_,;:()\\[\\]{}\"']"), " ")
        .replace(Regex("\\s+"), " ")

    val ignoredReturnPatterns = listOf(
        "ignor", "unused", "discard", "return value", "not assigned", "never assigned",
        "not used", "isn't assigned", "not stored", "not captured", "thrown away", "result is lost",
    )
    val returnsNewListPatterns = listOf(
        "new list", "returns new", "returns a new", "does not modify", "doesn't modify",
        "not in place", "non-mutating", "non mutating", "immutable", "original list",
        "new sorted list", "a new sorted", "sorted copy", "leaves the original", "leaves the receiver",
    )
    return RootCauseScore(
        mentionsIgnoredReturn = ignoredReturnPatterns.any { text.contains(it) },
        mentionsNewList = returnsNewListPatterns.any { text.contains(it) },
    )
}
