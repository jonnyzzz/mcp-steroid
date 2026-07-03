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

data class CallHierarchyScore(
    /** Endpoints the agent reported (normalized `pkg.Class.method` strings from `ENDPOINT:` markers). */
    val reported: Set<String>,
    /** Required endpoints the agent FAILED to report — the interface-dispatch / DI-lookup ones grep misses. */
    val missingRequired: Set<String>,
    val reportedCount: Int,
    /** True when every required endpoint was reported AND at least [minTotal] endpoints were listed. */
    val complete: Boolean,
)

/**
 * Score a caller-hierarchy (endpoint reachability) answer for completeness — the CALLERS dual of
 * [scoreTypeHierarchy]. The question is "which REST endpoints can transitively reach method X?"; the
 * required endpoints are the ones whose call chain crosses an interface dispatch, a lambda, or a DI
 * provider lookup — links `grep` cannot follow but the IDE's caller hierarchy walks exactly.
 *
 * @param output   the agent's answer text (markdown tolerated — backticks/emphasis are stripped first,
 *                 and `#`, `.`, `$`, `::` are all accepted as class/method separators).
 * @param required each entry is one required endpoint given as a set of ACCEPTABLE SPELLINGS
 *                 (`pkg.Class#method`) — agents legitimately name a nested JAX-RS resource by its outer
 *                 class, the nested class, or an inheriting subclass. An endpoint counts as found when
 *                 any spelling's simple class name appears ADJACENT to its method name (separators only
 *                 in between); a class mentioned in prose without its method does not count.
 * @param minTotal minimum number of distinct endpoints expected (guards against a near-empty answer).
 */
fun scoreCallHierarchy(output: String, required: List<Set<String>>, minTotal: Int): CallHierarchyScore {
    // Strip markdown emphasis/code marks so `Class.method()` and **Class#method** match plain patterns.
    val text = output.replace(Regex("[`*_]"), "")

    // Reported endpoints: prefer explicit `ENDPOINT: <class-and-method>` markers; if the agent used a
    // different layout, fall back to every `Class.method` / `Class#method`-shaped token in the answer.
    val marked = Regex("""(?im)^\s*[>#\-]*\s*ENDPOINT\s*:\s*(\S.*)$""")
        .findAll(text).map { normalizeEndpointSpec(it.groupValues[1]) }.filter { it.isNotEmpty() }.toSet()
    val reported = marked.ifEmpty {
        Regex("""\b[A-Z]\w*(?:\s*[.#$]\s*|\s*::\s*)[a-z]\w*\b""")
            .findAll(text).map { normalizeEndpointSpec(it.value) }.toSet()
    }

    val missing = required
        .filter { alternatives -> alternatives.none { spec -> endpointMentioned(text, spec) } }
        .map { it.first() }
        .toSet()

    return CallHierarchyScore(
        reported = reported,
        missingRequired = missing,
        reportedCount = reported.size,
        complete = missing.isEmpty() && reported.size >= minTotal,
    )
}

/** Canonicalize one endpoint mention: unify separators to `.`, drop `()`/whitespace, lowercase. */
private fun normalizeEndpointSpec(raw: String): String = raw
    .replace("::", ".").replace('#', '.').replace('$', '.')
    .replace(Regex("""\(\s*\)"""), "")
    .replace(Regex("""\s+"""), "")
    .trim('.', ',', ';', ':', '-')
    .lowercase()

/**
 * True when [spec] (`pkg.Outer.Inner#method`) is mentioned in [text]: any of its simple class names must
 * appear with the method name adjacent to it — only `.`/`#`/`$`/`::` separators (possibly through further
 * nested-class identifiers) in between. Prose like "looked at TokenEndpoint but found nothing" does NOT
 * match because there is no separator chain between the class and the method name.
 */
private fun endpointMentioned(text: String, spec: String): Boolean {
    val method = spec.substringAfterLast('#').trim()
    val classNames = spec.substringBeforeLast('#')
        .split('.', '$').filter { it.firstOrNull()?.isUpperCase() == true }
    if (method.isEmpty() || classNames.isEmpty()) return false
    return classNames.any { cls ->
        Regex(
            """\b${Regex.escape(cls)}(?:\s*(?:[.#$]|::)\s*[A-Za-z_]\w*)*\s*(?:[.#$]|::)\s*${Regex.escape(method)}\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)
    }
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

data class ChangeSignatureScore(
    /** The agent reported the interface method's signature itself was changed. */
    val signatureChanged: Boolean,
    /** FQNs the agent reported as updated overrides (from `OVERRIDE_UPDATED:` markers, else any FQN). */
    val reportedOverrides: Set<String>,
    /** Ground-truth overrides the agent did NOT report updating — the manual-sweep misses. */
    val missingOverrides: Set<String>,
    /** The post-change build result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** Signature changed AND every ground-truth override was reported updated. */
    val complete: Boolean,
    /** A safe change-signature = complete AND the project still compiles afterwards. */
    val safe: Boolean,
)

/**
 * Score a project-wide CHANGE SIGNATURE for completeness + safety. With MCP the agent drives IntelliJ's
 * `ChangeSignatureProcessor` (PSI): the interface method, every override — abstract bases, `default`
 * methods in sub-interfaces, anonymous classes — and every call site are updated atomically. Without MCP
 * a shell/editor sweep typically misses the non-obvious overrides or breaks call sites. Scored
 * identically for both modes.
 *
 * Expected markers (the prompt asks the agent to compile after the change and report):
 *   SIGNATURE_CHANGED: yes
 *   OVERRIDE_UPDATED: <fully.qualified.ClassName>   (one line per updated override)
 *   BUILD_AFTER_CHANGE: SUCCESS | FAILURE
 *
 * @param requiredOverrides ground-truth override FQNs derived from the project source — every one must
 *                          be reported (exact FQN, or same simple name under a nearby package).
 */
fun scoreChangeSignature(output: String, requiredOverrides: Set<String>): ChangeSignatureScore {
    val signatureChanged = findMarkerValue(output, "SIGNATURE_CHANGED", "Signature changed")
        ?.contains("yes", ignoreCase = true) == true

    // Prefer explicit `OVERRIDE_UPDATED: <fqn>` markers (tolerating markdown wrapping and backticked
    // FQNs); if the agent used a different layout, fall back to every FQN in the answer body.
    val marked = Regex("""(?im)^\s*[*_`>#-]*\s*OVERRIDE_UPDATED\s*[*_`>#-]*\s*:\s*[*_`]*([\w.$]+)""")
        .findAll(output).map { it.groupValues[1].trim().trim('.') }.toSet()
    val reported = marked.ifEmpty { FQN.findAll(output).map { it.groupValues[1] }.toSet() }

    val missing = requiredOverrides.filterNot { req ->
        req in reported || reported.any { it.endsWith(".${req.substringAfterLast('.')}") }
    }.toSet()

    val build = findMarkerValue(output, "BUILD_AFTER_CHANGE", "Build after change")
    val buildGreen = build?.let {
        when {
            it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
            it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("broke", ignoreCase = true) -> false
            else -> null
        }
    }

    val complete = signatureChanged && missing.isEmpty()
    return ChangeSignatureScore(
        signatureChanged = signatureChanged,
        reportedOverrides = reported,
        missingOverrides = missing,
        buildGreen = buildGreen,
        complete = complete,
        safe = complete && buildGreen == true,
    )
}

data class SafeDeleteScore(
    /** The agent reported the method declaration itself was removed. */
    val methodDeleted: Boolean,
    /** Normalized paths from the agent's `MIGRATED: <file>[:<line>]` markers. */
    val reportedMigrated: Set<String>,
    /** Ground-truth usage files the agent did NOT report migrating — the blind-sweep misses. */
    val missingMigrations: Set<String>,
    /** Normalized paths from the agent's `CHANGED_FILE:` markers (the full reported diff). */
    val changedFiles: Set<String>,
    /** Changed PRODUCTION files outside the known set — "while I'm here" collateral edits. */
    val collateralFiles: Set<String>,
    /** The post-delete build result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** Method deleted AND every ground-truth usage site was reported migrated. */
    val complete: Boolean,
    /** A safe delete = complete AND the build stays green AND no collateral production edits. */
    val safe: Boolean,
)

/**
 * Score a SAFE DELETE of a method for completeness + safety + precision. With MCP the agent drives
 * IntelliJ's `SafeDeleteProcessor` (or `ReferencesSearch` + guided edits) via `steroid_execute_code`:
 * the IDE surfaces every BLOCKING USAGE up front, the agent migrates each one (inline the deprecated
 * wrapper's trivial body / call the documented successor) and the deletion lands only when nothing
 * references the method anymore. Without MCP, a shell sweep deletes blind and discovers the missed
 * cross-module usage — or the forgotten import — only at compile time. Scored identically for both.
 *
 * Expected markers (the prompt asks the agent to compile after the delete and report):
 *   METHOD_DELETED: yes
 *   MIGRATED: <path/relative/to/repo/File.java>:<line>   (one line per migrated production usage)
 *   CHANGED_FILE: <path>                                  (one line per file from `git diff --name-only`)
 *   BUILD_AFTER_DELETE: SUCCESS | FAILURE
 *
 * Matching is markdown-normalized and suffix-based (absolute in-container paths and short relative
 * spellings both count). Lines in `MIGRATED` markers are informational — the file is the evidence:
 * an agent cannot name the exact usage files without finding them, which is what the experiment
 * measures (a hallucinated "done" cannot list `integration/…/AuthUtil.java`). Changed files under
 * `src/test` or `testsuite/` are NOT collateral — deleting a method legitimately ripples into tests,
 * and the build verdict covers production sources only.
 *
 * @param requiredUsageFiles  hand-derived ground truth: every production file with a usage of the
 *                            method that must be migrated.
 * @param allowedChangedFiles the full set of files the task is allowed to touch (declaration file +
 *                            usage files); any other changed production file is collateral.
 */
fun scoreSafeDelete(
    output: String,
    requiredUsageFiles: Set<String>,
    allowedChangedFiles: Set<String>,
): SafeDeleteScore {
    val methodDeleted = findMarkerValue(output, "METHOD_DELETED", "Method deleted")
        ?.contains("yes", ignoreCase = true) == true

    val migrated = Regex("""(?im)^\s*[*_`>#|:-]*\s*MIGRATED\s*[*_`]*\s*:\s*(.+)$""")
        .findAll(output)
        .map { normalizeReportedPath(it.groupValues[1]) }
        .filter { it.isNotEmpty() }
        .toSet()
    val missing = requiredUsageFiles.filterNot { truth ->
        migrated.any { pathsReferToSameFile(it, truth) }
    }.toSet()

    val changed = Regex("""(?im)^\s*[*_`>#|:-]*\s*CHANGED_FILE\s*[*_`]*\s*:\s*(.+)$""")
        .findAll(output)
        .map { normalizeReportedPath(it.groupValues[1].replace(Regex("""^\s*[MADRCU?!]{1,2}\s+"""), "")) }
        .filter { it.isNotEmpty() }
        .toSet()
    val collateral = changed.filterNot { file ->
        val isTestFile = file.contains("/src/test/") || file.startsWith("testsuite/") || file.contains("/testsuite/")
        isTestFile || allowedChangedFiles.any { pathsReferToSameFile(file, it) }
    }.toSet()

    val build = findMarkerValue(output, "BUILD_AFTER_DELETE", "Build after delete")
    val buildGreen = build?.let {
        when {
            it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
            it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("broke", ignoreCase = true) -> false
            else -> null
        }
    }

    val complete = methodDeleted && missing.isEmpty()
    return SafeDeleteScore(
        methodDeleted = methodDeleted,
        reportedMigrated = migrated,
        missingMigrations = missing,
        changedFiles = changed,
        collateralFiles = collateral,
        buildGreen = buildGreen,
        complete = complete,
        safe = complete && buildGreen == true && collateral.isEmpty(),
    )
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

data class CompileTriageScore(
    /** The agent's self-reported `ERRORS_FOUND:` count (informational only — never trusted for the verdict). */
    val errorsFound: Int?,
    /** `file simple name → line numbers` parsed from the agent's `FIXED: <path>:<line>` markers. */
    val reportedFixes: Map<String, Set<Int>>,
    /** Total number of reported `file:line` fix pairs. */
    val reportedCount: Int,
    /** Seeded `file:line` sites the agent actually fixed (±1 line tolerance, each consumed once). */
    val matchedSites: Map<String, Set<Int>>,
    val matchedCount: Int,
    /** Seeded sites the agent did NOT report fixing. */
    val missingSites: Map<String, Set<Int>>,
    /** The post-fix bounded-build result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** Every seeded site was fixed (regardless of build state / spam). */
    val allSitesFixed: Boolean,
    /** All seeded sites fixed AND build green AND no shotgun spam — the full win. */
    val safe: Boolean,
)

/**
 * Score a COMPILE-ERROR TRIAGE (time-to-green) run against the KNOWN seeded breakage: a patch applied
 * at IDE start plants deterministic single-line compile errors across modules, and the agent must make
 * the project compile again. With MCP the IDE's red-code analysis lists every error with resolved
 * types instantly (all modules at once); without MCP the agent pays a multi-minute `mvn` cycle per
 * iteration — and Maven stops at the first failing module, hiding the downstream errors entirely.
 *
 * The verdict is evidence-based, NOT self-report-based (the lesson from [scoreInspections], CI builds
 * 991971406/991971408, where a hallucinated count won):
 *  - each reported `FIXED: <path>:<line>` is matched against [seededSites] by file simple name and
 *    line (±1 tolerance; each seeded line consumes at most one reported line and vice versa) — the
 *    agent must fix each error AT ITS SEEDED SITE, so papering over a symptom elsewhere (e.g. a cast
 *    at the error line instead of restoring the declaration) does not count;
 *  - `safe` additionally requires the reported bounded build to be SUCCESS and at most
 *    `3 × |seeded|` total FIXED lines — carpet-editing the tree cannot win by luck.
 *
 * Expected markers (scored identically for both modes):
 *   ERRORS_FOUND: <count>
 *   FIXED: <path>:<line> — <what was wrong and the correct type>
 *   BUILD_AFTER_FIX: SUCCESS | FAILURE
 *
 * @param seededSites ground truth: file simple name → the line numbers of the seeded edits.
 */
fun scoreCompileTriage(output: String, seededSites: Map<String, Set<Int>>): CompileTriageScore {
    // Strip markdown code/bold marks so `path.java:112` and **path.java:112** parse the same.
    // Underscores are NOT stripped — they are significant in marker names (ERRORS_FOUND) and paths.
    val text = output.replace(Regex("[`*]"), "")
    val count = findMarkerValue(text, "ERRORS_FOUND", "Errors found")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    // Every `FIXED:` marker line contributes one (file simple name, line) pair. The file-name
    // pattern has no `/`, so it naturally captures the last path segment.
    val reported = mutableMapOf<String, MutableList<Int>>()
    Regex("""(?im)^\s*[-•>#\s]*FIXED\s*:\s*(.+)$""").findAll(text).forEach { fix ->
        val m = Regex("""([\w$.-]+\.java)\s*:\s*(\d+)""").find(fix.groupValues[1]) ?: return@forEach
        reported.getOrPut(m.groupValues[1]) { mutableListOf() }.add(m.groupValues[2].toInt())
    }
    val reportedCount = reported.values.sumOf { it.size }

    // Greedy bipartite match per file: exact line first, then ±1; each reported line consumed once.
    val matched = mutableMapOf<String, MutableSet<Int>>()
    for ((file, seededLines) in seededSites) {
        val available = reported[file]?.toMutableList() ?: continue
        for (tolerance in 0..1) {
            for (seededLine in seededLines.sorted()) {
                if (seededLine in (matched[file] ?: emptySet<Int>())) continue
                val hit = available.firstOrNull { kotlin.math.abs(it - seededLine) <= tolerance } ?: continue
                available.remove(hit)
                matched.getOrPut(file) { mutableSetOf() }.add(seededLine)
            }
        }
    }
    val matchedCount = matched.values.sumOf { it.size }
    val seededCount = seededSites.values.sumOf { it.size }
    val missing = seededSites
        .mapValues { (file, lines) -> lines - (matched[file] ?: emptySet()) }
        .filterValues { it.isNotEmpty() }

    val build = findMarkerValue(text, "BUILD_AFTER_FIX", "Build after fix")
    val buildGreen = build?.let {
        when {
            it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
            it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("broke", ignoreCase = true) -> false
            else -> null
        }
    }

    val allSitesFixed = matchedCount == seededCount
    return CompileTriageScore(
        errorsFound = count,
        reportedFixes = reported.mapValues { it.value.toSet() },
        reportedCount = reportedCount,
        matchedSites = matched.mapValues { it.value.toSet() },
        matchedCount = matchedCount,
        missingSites = missing,
        buildGreen = buildGreen,
        allSitesFixed = allSitesFixed,
        safe = allSitesFixed && buildGreen == true && reportedCount <= 3 * seededCount,
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

data class InteropUsagesScore(
    /** The `USAGES_FOUND:` total the agent reported (null if it never reported one). */
    val reportedCount: Int?,
    /** Distinct (path, line) pairs parsed from `USAGE:` markers — non-source paths already dropped. */
    val reportedPairCount: Int,
    /** Ground-truth required usages the agent DID report (ground-truth path → matched lines). */
    val foundRequired: Map<String, Set<Int>>,
    /** Ground-truth required usages the agent FAILED to report — the cross-language ones grep misses. */
    val missedRequired: Map<String, Set<Int>>,
    /** Reported `path:line` pairs that are neither required nor optional — e.g. same-named locals. */
    val falsePositives: Set<String>,
    /** True when EVERY required usage was reported (within the ±1 line tolerance). */
    val complete: Boolean,
    /** [complete] AND no false positives — the resolve-exact answer. */
    val exact: Boolean,
)

/**
 * Score a "enumerate EVERY usage of a symbol across BOTH languages" answer against a hand-derived
 * `file → lines` ground truth (pinned revision, so lines are stable). The scenario this scores:
 * a Kotlin `val requestLine` consumed from Java as the generated getter `getRequestLine()` —
 * a case-sensitive search for the declared name finds ZERO Java call sites, while a loose search
 * for the identifier over-matches same-named LOCAL VARIABLES that are not property usages at all.
 * Resolve-based find-usages (`ReferencesSearch` on the property) answers this exactly; the scorer
 * treats both failure modes separately: [InteropUsagesScore.missedRequired] (under-match) and
 * [InteropUsagesScore.falsePositives] (over-match).
 *
 * Expected markers (mode-independent — with-MCP and shell runs are scored identically):
 *   USAGES_FOUND: <total count>
 *   USAGE: <path/relative/to/repo>:<line>      (one line per usage)
 *
 * Matching rules:
 *  - only `.java` / `.kt` paths count; anything else (README snippets, `.api` dumps) is ignored,
 *  - paths are markdown-normalized and suffix-matched, so absolute in-container spellings and
 *    shorter-but-unambiguous relative spellings both count,
 *  - lines match with ±1 tolerance (multi-line expressions), exact matches claimed first, and each
 *    reported line satisfies at most one ground-truth line (and vice versa),
 *  - [optional] usages (e.g. reads inside the declaring file, the declaration line itself) are
 *    never required and never counted as false positives — agents legitimately disagree on them.
 */
fun scoreInteropUsages(
    output: String,
    required: Map<String, Set<Int>>,
    optional: Map<String, Set<Int>> = emptyMap(),
): InteropUsagesScore {
    val reportedCount = findMarkerValue(output, "USAGES_FOUND", "Usages found")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    // Every `USAGE:` marker line contributes one (normalized path, line) pair. `USAGE` must be
    // followed by the separator, so the `USAGES_FOUND:` count line can never match.
    val usageLine = Regex("""(?im)^\s*[*_`>#|:\s-]*USAGE\s*[*_`]*\s*:\s*(.+)$""")
    val pathAndLine = Regex("""([^\s:`*"']+\.(?:java|kt))\s*:\s*(\d+)""")
    val reportedPairs: Set<Pair<String, Int>> = usageLine.findAll(output)
        .mapNotNull { usage ->
            val m = pathAndLine.find(usage.groupValues[1]) ?: return@mapNotNull null
            normalizeReportedPath(m.groupValues[1]) to m.groupValues[2].toInt()
        }
        .filter { it.first.isNotEmpty() }
        .toSet()

    // Group the reported lines under the ground-truth file they refer to (suffix path matching).
    // A reported pair may sit in `remaining` for at most one ground-truth file — files in the
    // ground truth have distinct suffix-disjoint paths.
    fun matchAgainst(truth: Map<String, Set<Int>>, pool: MutableSet<Pair<String, Int>>): Map<String, Set<Int>> {
        val matched = mutableMapOf<String, MutableSet<Int>>()
        for ((truthFile, truthLines) in truth) {
            val candidates = pool.filter { pathsReferToSameFile(it.first, truthFile) }.toMutableList()
            for (tolerance in 0..1) {
                for (truthLine in truthLines.sorted()) {
                    if (truthLine in (matched[truthFile] ?: emptySet<Int>())) continue
                    val hit = candidates.firstOrNull { kotlin.math.abs(it.second - truthLine) <= tolerance }
                        ?: continue
                    candidates.remove(hit)
                    pool.remove(hit)
                    matched.getOrPut(truthFile) { mutableSetOf() }.add(truthLine)
                }
            }
        }
        return matched
    }

    val pool = reportedPairs.toMutableSet()
    val foundRequired = matchAgainst(required, pool)
    matchAgainst(optional, pool) // consume optional hits so they are not false positives

    val missedRequired = required.mapNotNull { (file, lines) ->
        val missing = lines - (foundRequired[file] ?: emptySet())
        if (missing.isEmpty()) null else file to missing
    }.toMap()

    val falsePositives = pool.map { "${it.first}:${it.second}" }.toSet()
    val complete = missedRequired.isEmpty()

    return InteropUsagesScore(
        reportedCount = reportedCount,
        reportedPairCount = reportedPairs.size,
        foundRequired = foundRequired,
        missedRequired = missedRequired,
        falsePositives = falsePositives,
        complete = complete,
        exact = complete && falsePositives.isEmpty(),
    )
}

data class MoveClassScore(
    /** The class file exists at the new package AND is gone from the old one — HARNESS-measured. */
    val moveDone: Boolean,
    /** Normalized (deduplicated) file paths extracted from the agent's `UPDATED:` lines. */
    val reportedUpdatedFiles: Set<String>,
    /** Ground-truth reference sites the agent did NOT report updating — the manual-sweep misses. */
    val missingUpdatedFiles: Set<String>,
    /** The post-move build result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** Occurrences of the OLD fully-qualified name left in the tree — HARNESS-measured grep count. */
    val oldFqnResidueCount: Int?,
    /** True when the harness grep found ZERO old-FQN occurrences (null count = not clean). */
    val residueClean: Boolean,
    /** Move performed AND every ground-truth reference site was reported updated. */
    val complete: Boolean,
    /** A safe move = complete AND build green AND zero old-FQN residue. */
    val safe: Boolean,
)

/**
 * Score a project-wide MOVE CLASS (between packages) for completeness + safety. With MCP the agent
 * drives IntelliJ's `MoveClassesOrPackagesProcessor` (PSI): the class file moves, its package
 * declaration changes, and every import, FQN reference, javadoc link and (with text-occurrence
 * search) non-Java FQN string is rewritten atomically. Without MCP a grep-driven sweep has two
 * blind spots: same-package usages carry NO import line to rewrite (they need a NEW import added
 * or the build breaks), and FQN strings hide in resource/script/doc files (the build stays green
 * but the runtime reference dangles). Scored identically for both modes.
 *
 * Evidence-based by construction — the three load-bearing inputs are measured by the test harness
 * (identically for both legs), NOT self-reported:
 *  - [classAtNewPath] / [classAtOldPath]: container file-existence checks — a `MOVE_DONE: yes`
 *    claim with the file still at the old path (copy, not move) scores false;
 *  - [oldFqnResidueCount]: a project-wide grep for the old FQN run by the harness — the objective
 *    residue check; any count other than exactly 0 (including a failed check = null) is unclean.
 *
 * Only the per-file list and the build claim come from the agent's marker output:
 *   MOVE_DONE: yes
 *   UPDATED: <path/relative/to/repo/File.java>       (one line per updated file)
 *   BUILD_AFTER_MOVE: SUCCESS | FAILURE
 *
 * Path matching is markdown-normalized and suffix-based (see [pathsReferToSameFile]), so absolute
 * in-container paths and repo-relative spellings both count.
 *
 * @param requiredUpdatedFiles ground-truth reference-site paths derived by hand from the pinned
 *                             project revision — every one must appear among the `UPDATED:` lines.
 */
fun scoreMoveClass(
    output: String,
    requiredUpdatedFiles: Set<String>,
    classAtNewPath: Boolean?,
    classAtOldPath: Boolean?,
    oldFqnResidueCount: Int?,
): MoveClassScore {
    // `UPDATED\b` keeps `UPDATED_COUNT:` (same prefix) from contributing a fake path.
    val updatedLine = Regex("""(?im)^\s*[*_`>#|:-]*\s*UPDATED\b\s*[*_`]*\s*:\s*(.+)$""")
    val reported = updatedLine.findAll(output)
        .map { normalizeReportedPath(it.groupValues[1]) }
        .filter { it.isNotEmpty() }
        .toSet()

    val missing = requiredUpdatedFiles.filterNot { truth ->
        reported.any { pathsReferToSameFile(it, truth) }
    }.toSet()

    val build = findMarkerValue(output, "BUILD_AFTER_MOVE", "Build after move")
    val buildGreen = build?.let {
        when {
            it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
            it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("broke", ignoreCase = true) -> false
            else -> null
        }
    }

    val moveDone = classAtNewPath == true && classAtOldPath == false
    val residueClean = oldFqnResidueCount == 0
    val complete = moveDone && missing.isEmpty()
    return MoveClassScore(
        moveDone = moveDone,
        reportedUpdatedFiles = reported,
        missingUpdatedFiles = missing,
        buildGreen = buildGreen,
        oldFqnResidueCount = oldFqnResidueCount,
        residueClean = residueClean,
        complete = complete,
        safe = complete && buildGreen == true && residueClean,
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
