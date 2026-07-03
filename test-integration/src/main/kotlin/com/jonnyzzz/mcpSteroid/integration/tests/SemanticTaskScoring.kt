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
    val issuesFound: Int?,
    val mentionsRedundantCast: Boolean,
    val mentionsTargetFile: Boolean,
    /** True when the agent detected a meaningful number of the (semantic) redundant-cast issues. */
    val detected: Boolean,
)

/**
 * Score an "run IDE inspections + report issues" answer. The target is the redundant casts after
 * `instanceof` in Keycloak's `ValidatorConfig.java`. With MCP the agent runs IntelliJ's inspection
 * (semantic type-narrowing → finds them exactly); grep/shell cannot determine a cast is redundant.
 *
 * Expected markers:
 *   ISSUES_FOUND: <count>
 *   ISSUE: <description>            (the redundant-cast lines; ideally mentioning the file)
 *
 * @param minIssues the lower bound of redundant casts that must be reported to count as detected.
 */
fun scoreInspections(output: String, minIssues: Int, targetFile: String): InspectionScore {
    val count = findMarkerValue(output, "ISSUES_FOUND", "Issues found")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
    val mentionsCast = output.contains("redundant cast", ignoreCase = true) ||
        output.contains("redundant type cast", ignoreCase = true) ||
        output.contains("unnecessary cast", ignoreCase = true)
    val mentionsFile = output.contains(targetFile, ignoreCase = true)
    return InspectionScore(
        issuesFound = count,
        mentionsRedundantCast = mentionsCast,
        mentionsTargetFile = mentionsFile,
        detected = mentionsCast && mentionsFile && (count ?: 0) >= minIssues,
    )
}

data class SsrOptionalGetScore(
    /** The OPTIONAL_GET_MATCHES total the agent reported (null if it never reported one). */
    val reportedCount: Int?,
    /** Normalized (deduplicated) file paths extracted from the agent's `MATCH:` lines. */
    val reportedFiles: Set<String>,
    /** Ground-truth files the agent DID report (keyed by the ground-truth spelling). */
    val foundFiles: Set<String>,
    /** Ground-truth files the agent FAILED to report — e.g. chained `findFirst().get()` grep can't type. */
    val missedFiles: Set<String>,
    /** Reported files with NO true Optional.get() — e.g. ByteBuffer/AtomicLong/Future `.get()` over-matches. */
    val falsePositiveFiles: Set<String>,
    /** True when every ground-truth file was reported AND nothing extra was — the SSR-exact answer. */
    val exact: Boolean,
)

/**
 * Score an "audit every `Optional.get()` callsite" answer against a known ground-truth file list
 * (derived by hand from the audited repo at the revision the experiment pins). SSR with an
 * `exprtype(java.util.Optional…)` constraint answers this exactly; a text search over `.get()` both
 * over-matches (dozens of other no-arg `get()` receivers: Atomic*, ThreadLocal, Supplier, Future,
 * WeakReference, ByteBuffer, …) and under-matches (chained receivers like `stream.findFirst().get()`
 * whose Optional type only exists after resolution).
 *
 * Scored at FILE granularity: line numbers drift with formatting and agents report them
 * inconsistently, but the file set separates the two failure modes cleanly. Expected markers:
 *   OPTIONAL_GET_MATCHES: <total count>
 *   MATCH: <path/relative/to/repo/File.java>:<line>     (one line per callsite)
 *
 * Path matching is markdown-normalized and suffix-based, so absolute in-container paths
 * (`/home/agent/project/core/src/…`) and shorter-but-unambiguous relative spellings both count.
 */
fun scoreSsrOptionalGet(output: String, groundTruthFiles: Set<String>): SsrOptionalGetScore {
    val reportedCount = findMarkerValue(output, "OPTIONAL_GET_MATCHES", "Optional get matches")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    val matchLine = Regex("""(?im)^\s*[*_`>#|:-]*\s*MATCH\s*[*_`]*\s*:\s*(.+)$""")
    val reportedFiles = matchLine.findAll(output)
        .map { normalizeReportedPath(it.groupValues[1]) }
        .filter { it.isNotEmpty() }
        .toSet()

    val found = groundTruthFiles.filter { truth ->
        reportedFiles.any { pathsReferToSameFile(it, truth) }
    }.toSet()
    val falsePositives = reportedFiles.filterNot { reported ->
        groundTruthFiles.any { pathsReferToSameFile(reported, it) }
    }.toSet()
    val missed = groundTruthFiles - found

    return SsrOptionalGetScore(
        reportedCount = reportedCount,
        reportedFiles = reportedFiles,
        foundFiles = found,
        missedFiles = missed,
        falsePositiveFiles = falsePositives,
        exact = missed.isEmpty() && falsePositives.isEmpty(),
    )
}

/** Strip markdown/quoting, unify separators, drop the `:<line>` suffix — keep just the path. */
private fun normalizeReportedPath(raw: String): String {
    var p = raw.trim().trim('`', '*', '_', '"', '\'', '|')
    p = p.replace('\\', '/')
    // Drop a trailing :<line> or :<line>:<col> suffix (only numeric suffixes are stripped).
    p = p.replace(Regex("""(:\d+)+\s*$"""), "")
    p = p.trim().trim('`', '*', '_', '"', '\'')
    p = p.removePrefix("./")
    return p.trim('/')
}

/**
 * True when one normalized path is a whole-component suffix of the other. Handles the agent
 * reporting absolute in-container paths (longer than ground truth) or short relative spellings
 * (shorter than ground truth, e.g. starting below the module root).
 */
private fun pathsReferToSameFile(a: String, b: String): Boolean =
    a == b || a.endsWith("/$b") || b.endsWith("/$a")

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
