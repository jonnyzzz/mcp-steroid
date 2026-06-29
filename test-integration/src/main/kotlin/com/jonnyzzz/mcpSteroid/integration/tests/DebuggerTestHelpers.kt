package com.jonnyzzz.mcpSteroid.integration.tests

/**
 * Shared helpers for debugger integration tests.
 */

fun hasAnyMarkerLine(output: String, vararg markers: String): Boolean {
    return markers.any { marker ->
        Regex("""(?im)^\s*[*_`>#-]*\s*${Regex.escape(marker)}\s*[*_`>#-]*\s*:""").containsMatchIn(output)
    }
}

fun findMarkerValue(output: String, vararg markers: String): String? {
    if (markers.isEmpty()) return null
    val markerAlternation = markers.joinToString("|") { Regex.escape(it) }
    val markerRegex = Regex(
        // [*_`>#-]* on both sides of the marker name handles closing bold/italic markdown
        // formatting, e.g. "**BUG_LINE**: value" where ** appears after the marker name too.
        pattern = """(?im)^\s*[*_`>#-]*\s*(?:$markerAlternation)\s*[*_`>#-]*\s*:\s*(.+?)\s*[*_`]*\s*$"""
    )
    val candidates = markerRegex.findAll(output).mapNotNull { match ->
        match.groupValues
            .getOrNull(1)
            ?.trim()
            ?.trim('*', '_', '`')
            ?.takeIf { it.isNotEmpty() }
    }.toList()

    // Filter out template placeholders like <the exact buggy source line>
    // but allow code type params (<Player>, <T>) and C# lambdas (p => p.Score)
    // Template placeholders always contain spaces; code type params don't
    val templatePlaceholder = Regex("""<[a-zA-Z][^>]*\s[^>]*>""")
    return candidates.lastOrNull { value ->
        val lowered = value.lowercase()
        !templatePlaceholder.containsMatchIn(value) &&
                !lowered.contains("copy the") &&
                !lowered.contains("one line description") &&
                !lowered.contains("exact buggy source line")
    }
}

/**
 * Outcome of scoring an agent's answer for the `sortedByDescending` demo bug — the bug is that
 * `players.sortedByDescending { it.score }` returns a NEW sorted list whose return value is ignored,
 * so the original unsorted list is used. [bugFound] is true only when the agent both pinpointed the
 * line AND explained the root cause correctly (ignored/unused return + non-mutating "new list"),
 * without claiming the wrong selector (`it.first`).
 */
data class BugIdentificationScore(
    val bugFound: Boolean,
    val bugLine: String?,
    val rootCause: String?,
    val reasons: List<String>,
)

/**
 * PURE correctness score for the `sortedByDescending` demo — the fair, mode-independent verdict for
 * the debugger A/B (with-MCP vs without-MCP): did the agent *correctly identify the bug*, regardless of
 * how (live debugger vs reading code)? No IDE/Docker/agent needed, so it is unit-tested directly.
 */
fun scoreSortedByDescendingBug(output: String): BugIdentificationScore {
    val bugLine = findMarkerValue(output, "BUG_LINE", "Buggy line", "Bug line")
    val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
    val reasons = mutableListOf<String>()

    val bugLineNamesCall = bugLine?.contains("sortedByDescending", ignoreCase = true) == true
    if (!bugLineNamesCall) reasons += "BUG_LINE missing or does not name sortedByDescending"

    val ignoredReturnPatterns = listOf(
        "ignor", "unused", "discard", "return value", "not assigned", "not assigned back", "not used",
        "isn't assigned", "ignored/not assigned", "not stored", "not captured", "thrown away", "result is lost",
    )
    val newListPatterns = listOf(
        "new list", "returns new", "does not modify", "doesn't modify",
        "not in place", "immutable", "original list", "original unsorted list",
        "new sorted list", "sorted copy",
    )
    val rc = rootCause ?: ""
    val mentionsIgnoredReturn = ignoredReturnPatterns.any { rc.contains(it, ignoreCase = true) }
    val mentionsNewListBehavior = newListPatterns.any { rc.contains(it, ignoreCase = true) }
    val notWrongSelector = !rc.contains("it.first", ignoreCase = true)

    if (rootCause == null) reasons += "ROOT_CAUSE missing"
    if (!mentionsIgnoredReturn) reasons += "ROOT_CAUSE does not explain the ignored/unused return value"
    if (!mentionsNewListBehavior) reasons += "ROOT_CAUSE does not explain the new-list / non-mutating behavior"
    if (!notWrongSelector) reasons += "ROOT_CAUSE wrongly claims a selector bug (it.first)"

    val bugFound = bugLineNamesCall && rootCause != null &&
        mentionsIgnoredReturn && mentionsNewListBehavior && notWrongSelector
    return BugIdentificationScore(bugFound, bugLine, rootCause, reasons)
}

fun assertUsedExecuteCodeEvidence(combined: String) {
    val executionIdPattern = Regex("""\b(?:Execution ID|execution_id):\s*eid_[A-Za-z0-9_-]+""")
    val hasToolEvidence = executionIdPattern.containsMatchIn(combined)

    check(hasToolEvidence) {
        "Agent must show evidence of steroid_execute_code usage.\n" +
                "Expected an execution id marker (`Execution ID: eid_...` or `execution_id: eid_...`).\nOutput:\n$combined"
    }
}

fun assertRootCauseQuality(
    combined: String,
    output: String,
    firstAspectPatterns: List<String>,
    secondAspectPatterns: List<String>,
    explanation: String,
) {
    val rootCause = findMarkerValue(output, "ROOT_CAUSE", "Root cause")
    check(rootCause != null) {
        "Agent did not output required marker 'ROOT_CAUSE:' (or equivalent).\nOutput:\n$combined"
    }

    val mentionsFirstAspect = firstAspectPatterns.any { pattern ->
        rootCause.contains(pattern, ignoreCase = true)
    }
    val mentionsSecondAspect = secondAspectPatterns.any { pattern ->
        rootCause.contains(pattern, ignoreCase = true)
    }
    check(mentionsFirstAspect && mentionsSecondAspect) {
        "$explanation\n" +
                "Expected first-aspect patterns: $firstAspectPatterns\n" +
                "Expected second-aspect patterns: $secondAspectPatterns\n" +
                "Actual ROOT_CAUSE: $rootCause\nOutput:\n$combined"
    }
}