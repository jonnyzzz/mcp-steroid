/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Pure scorers for the x11k A/B experiments (jonnyzzz/mcp-steroid#169 family) against the pinned
 * github.com/jonnyzzz/x11k commit `cfdf1f7d171df2581b63f7dfe675c343f6c86882`:
 *
 *  - [scoreSplitFile]        — `x11k__split_file`: split the 11.6k-line `X11State.kt` into cohesive files;
 *  - [scoreTestSetupHelper]  — `x11k__test_setup_helper`: extract the copy-pasted in-process X-server boot
 *                              block of `XSyncProtocolTest.kt` into a shared helper and migrate all 15 sites;
 *  - [scoreKotlinInspections] — `x11k__inspections`: report the `file:line` sites kotlinc `extraWarnings`
 *                              flags (never-reassigned / never-read local `var`s), Kotlin-path twin of
 *                              [scoreInspections].
 *
 * Same design rules as [SemanticTaskScoring.kt]: mode-independent (with-MCP and shell-only runs are
 * scored identically), evidence-based (markers matched against hand-derived / mechanically-derived
 * ground truth, spam caps against shotgun answers), no IDE / Docker / agent needed → unit-tested directly.
 */

/** Parse a SUCCESS/FAILURE-ish marker value into a tri-state build/tests verdict. */
private fun parseGreen(value: String?): Boolean? = value?.let {
    when {
        it.contains("SUCCESS", ignoreCase = true) || it.equals("pass", ignoreCase = true) ||
            it.equals("passed", ignoreCase = true) || it.equals("green", ignoreCase = true) -> true
        it.contains("FAIL", ignoreCase = true) || it.contains("error", ignoreCase = true) ||
            it.contains("broke", ignoreCase = true) -> false
        else -> null
    }
}

data class SplitFileScore(
    /** The agent reported the split itself was performed. */
    val splitDone: Boolean,
    /** `wc -l` of the original file after the split as reported (0 = file removed); null = never reported. */
    val remainingLines: Int?,
    /** Distinct new `.kt` file paths reported via `NEW_FILE:` (the original file never counts). */
    val newFiles: Set<String>,
    /** The post-split build result the agent reported (null if it never reported one). */
    val buildGreen: Boolean?,
    /** Sentinel declaration name → reported `grep` occurrence count (from `RESIDUE: <name>=<count>`). */
    val residueCounts: Map<String, Int>,
    /** Every sentinel was reported with count exactly 1 — moved once, not deleted, not duplicated. */
    val residueClean: Boolean,
    /** Split done AND file below threshold AND enough new files AND build green AND residue clean. */
    val safe: Boolean,
)

/**
 * Score a "split the god file into cohesive files" run. With MCP the agent drives IntelliJ's Move
 * declarations / Move members refactorings via `steroid_execute_code` (references and imports update
 * atomically); without MCP it is a manual cut-and-paste sweep. Both legs run the SAME verification
 * commands and report their raw results, which this scorer cross-checks:
 *
 *   SPLIT_DONE: yes
 *   REMAINING_LINES: <wc -l of the original file after the split; 0 if the file was removed>
 *   NEW_FILE: <path of one newly created .kt file>          (one line per file)
 *   BUILD_AFTER_SPLIT: SUCCESS | FAILURE
 *   RESIDUE: <SentinelClassName>=<count>                    (grep -r occurrence count, must be 1)
 *
 * @param originalFileName  simple name of the file being split (a NEW_FILE with this name is ignored).
 * @param maxRemainingLines the shrink threshold the original file must reach.
 * @param minNewFiles       minimum number of distinct new files for a real multi-file split.
 * @param sentinels         declaration names whose `RESIDUE:` count must be exactly 1 — checks that the
 *                          moved code still exists exactly once (a deleted or copy-duplicated declaration
 *                          fails even when the build-green claim is a lie of omission).
 */
fun scoreSplitFile(
    output: String,
    originalFileName: String,
    maxRemainingLines: Int,
    minNewFiles: Int,
    sentinels: Set<String>,
): SplitFileScore {
    // Strip markdown code/bold marks so `- **NEW_FILE**: `path`` and plain markers parse the same.
    val text = output.replace(Regex("[`*]"), "")

    val splitDone = findMarkerValue(text, "SPLIT_DONE", "Split done")
        ?.contains("yes", ignoreCase = true) == true

    val remainingRaw = findMarkerValue(text, "REMAINING_LINES", "Remaining lines")
    val remainingLines = remainingRaw?.let { raw ->
        Regex("""\d+""").find(raw)?.value?.toIntOrNull()
            ?: if (raw.contains("remov", ignoreCase = true) || raw.contains("delet", ignoreCase = true)) 0 else null
    }

    // Every `NEW_FILE:` marker contributes one path (markdown marks already stripped above).
    val newFiles = Regex("""(?im)^\s*[-•>#|\s]*NEW_FILE\s*:\s*(.+)$""")
        .findAll(text)
        .map { it.groupValues[1].trim().trim('_', '"', '\'').removePrefix("./").trim('/') }
        .filter { it.endsWith(".kt") && !it.endsWith("/$originalFileName") && it != originalFileName }
        .toSet()

    val buildGreen = parseGreen(findMarkerValue(text, "BUILD_AFTER_SPLIT", "Build after split"))

    // `RESIDUE: <name>=<count>` — tolerate spaces around `=`.
    val residueCounts = mutableMapOf<String, Int>()
    Regex("""(?im)^\s*[-•>#|\s]*RESIDUE\s*:\s*([\w$]+)\s*=\s*(\d+)""")
        .findAll(text).forEach { m ->
            residueCounts[m.groupValues[1]] = m.groupValues[2].toInt()
        }
    val residueClean = sentinels.isNotEmpty() && sentinels.all { residueCounts[it] == 1 }

    return SplitFileScore(
        splitDone = splitDone,
        remainingLines = remainingLines,
        newFiles = newFiles,
        buildGreen = buildGreen,
        residueCounts = residueCounts,
        residueClean = residueClean,
        safe = splitDone &&
            remainingLines != null && remainingLines <= maxRemainingLines &&
            newFiles.size >= minNewFiles &&
            buildGreen == true &&
            residueClean,
    )
}

data class TestSetupHelperScore(
    /** The agent reported creating the shared helper (`HELPER_CREATED:`). */
    val helperCreated: Boolean,
    /** The helper path/name as reported (null when never reported). */
    val helperRef: String?,
    /** Original-file line numbers parsed from `MIGRATED: <expectedFile>:<line>` markers. */
    val reportedLines: List<Int>,
    val reportedCount: Int,
    /** How many ground-truth call sites were hit (tolerance-matched, each consumed once). */
    val matchedCount: Int,
    /** Ground-truth call-site lines the agent did NOT report migrating. */
    val missingLines: Set<Int>,
    /** The post-change test-run result the agent reported (null if it never reported one). */
    val testsGreen: Boolean?,
    /** Enough ground-truth call sites hit AND the answer is not a shotgun spam of line numbers. */
    val migrated: Boolean,
    /** Helper created AND migrated AND the tests still pass. */
    val safe: Boolean,
)

/**
 * Score a "replace the copy-pasted test setup with a shared helper" run. The ground truth is the
 * hand-derived list of call-site line numbers (where each duplicated setup block starts) in the
 * target test file at the pinned revision. The prompt asks the agent to report each migrated call
 * site by its ORIGINAL line number, so a fabricated answer must guess 60-line-spaced positions:
 *
 *   HELPER_CREATED: <path or name of the new shared helper>
 *   MIGRATED: <path ending with expectedFile>:<original line>   (one per migrated call site)
 *   TESTS_AFTER_CHANGE: SUCCESS | FAILURE
 *
 * Matching is greedy-bipartite per tolerance ring (exact first, then growing distance up to
 * [lineTolerance]); each reported line is consumed at most once, so repeating one true line N times
 * counts as one match. `migrated` additionally requires the spam cap: at most `3 × |expected|`
 * reported pairs.
 *
 * @param expectedFile  simple name of the test file whose call sites are migrated; `MIGRATED:` markers
 *                      for other files are ignored (suffix path match, absolute container paths OK).
 * @param expectedLines hand-derived original line numbers of every duplicated setup block.
 * @param minMigrated   how many ground-truth call sites must be hit.
 * @param lineTolerance per-site line drift tolerance (agents occasionally report the `use {` line or
 *                      an off-by-few position from their editor context).
 */
fun scoreTestSetupHelper(
    output: String,
    expectedFile: String,
    expectedLines: Set<Int>,
    minMigrated: Int,
    lineTolerance: Int,
): TestSetupHelperScore {
    val text = output.replace(Regex("[`*]"), "")

    val helperRef = findMarkerValue(text, "HELPER_CREATED", "Helper created")
    val helperCreated = !helperRef.isNullOrBlank() &&
        !helperRef.equals("no", ignoreCase = true) && !helperRef.equals("none", ignoreCase = true)

    // `MIGRATED: <path>:<line>` — keep only markers whose path refers to the expected file.
    val reportedLines = mutableListOf<Int>()
    Regex("""(?im)^\s*[-•>#|\s]*MIGRATED\s*:\s*(.+)$""").findAll(text).forEach { m ->
        val match = Regex("""([\w$./\\-]+\.kts?)\s*:\s*(\d+)""").find(m.groupValues[1]) ?: return@forEach
        val path = match.groupValues[1].replace('\\', '/')
        if (path == expectedFile || path.endsWith("/$expectedFile")) {
            reportedLines.add(match.groupValues[2].toInt())
        }
    }

    // Greedy bipartite match: exact lines first, then growing tolerance; each reported line consumed once.
    val available = reportedLines.toMutableList()
    val matched = mutableSetOf<Int>()
    for (tolerance in 0..lineTolerance) {
        for (expectedLine in expectedLines.sorted()) {
            if (expectedLine in matched) continue
            val hit = available.firstOrNull { kotlin.math.abs(it - expectedLine) <= tolerance } ?: continue
            available.remove(hit)
            matched.add(expectedLine)
        }
    }

    val testsGreen = parseGreen(findMarkerValue(text, "TESTS_AFTER_CHANGE", "Tests after change"))
    val migrated = matched.size >= minMigrated && reportedLines.size <= 3 * expectedLines.size

    return TestSetupHelperScore(
        helperCreated = helperCreated,
        helperRef = helperRef,
        reportedLines = reportedLines,
        reportedCount = reportedLines.size,
        matchedCount = matched.size,
        missingLines = expectedLines - matched,
        testsGreen = testsGreen,
        migrated = migrated,
        safe = helperCreated && migrated && testsGreen == true,
    )
}

/**
 * Score a "run IDE inspections + report issues" answer for KOTLIN (or mixed) sources — identical
 * verdict logic to [scoreInspections], whose `ISSUE:` parser is limited to `.java` paths. Ground
 * truth for the x11k scenario: the `file:line` sites kotlinc 2.4.0 `extraWarnings` (K2 `-Wextra`)
 * reports at the pinned commit — local `var`s never reassigned (should be `val`) or never read.
 * With MCP the agent runs IntelliJ's Kotlin inspections (`CanBeVal` / unused symbol) per candidate
 * file; grep sees `var` declarations everywhere but cannot determine "never written after
 * initialization" without data-flow analysis.
 *
 * Expected markers:
 *   ISSUES_FOUND: <count>                                  (informational — never trusted)
 *   ISSUE: <path>:<line> — <description>                   (one per finding)
 *
 * `detected` requires ≥ [minMatches] ground-truth hits (±1 line tolerance, greedy-bipartite, each
 * line consumed once) AND at most `3 × |expected|` total reported pairs (spam cap).
 */
fun scoreKotlinInspections(output: String, expected: Map<String, Set<Int>>, minMatches: Int): InspectionScore {
    // Strip markdown code/bold marks; underscores stay (significant in marker names and paths).
    val text = output.replace(Regex("[`*]"), "")
    val count = findMarkerValue(text, "ISSUES_FOUND", "Issues found")
        ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

    // Every `ISSUE:` line contributes one (file simple name, line) pair; the file-name pattern has
    // no `/`, so it naturally captures the last path segment. Accepts .kt/.kts/.java.
    val reported = mutableMapOf<String, MutableList<Int>>()
    Regex("""(?im)^\s*[-•>#\s]*ISSUE\s*:\s*(.+)$""").findAll(text).forEach { issue ->
        val m = Regex("""([\w$.-]+\.(?:java|kts?))\s*:\s*(\d+)""").find(issue.groupValues[1]) ?: return@forEach
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
