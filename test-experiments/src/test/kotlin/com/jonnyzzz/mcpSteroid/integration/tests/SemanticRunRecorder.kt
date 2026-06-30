/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.arena.extractDecodedLogMetrics
import com.jonnyzzz.mcpSteroid.integration.arena.extractTokenUsage
import com.jonnyzzz.mcpSteroid.integration.arena.findDecodedLogFile
import java.io.File

/**
 * Emit a full `[ARENA]` block for one agent run of a semantic A/B experiment (type-hierarchy,
 * find-usages, rename, inspections, debugger). The dashboard's ArenaLogParser reads these lines, so the
 * with/without-MCP comparison shows not just the correctness verdict but the **efficiency** signals —
 * agent duration, tokens/cost, and the **tool-call counters** (which tools each mode used:
 * steroid_execute_code vs Read/Edit/Write/Bash/Glob/Grep). This is what surfaces the MCP win when both
 * modes are equally *correct* but differ in effort. Format mirrors DpaiaScenarioBaseTest exactly.
 *
 * @param claimedFix the experiment's correctness verdict for this run.
 * @param rawOutput  the agent's raw stdout (NDJSON) — token/cost/turns are parsed from it.
 * @param runDir     the run dir holding the decoded agent log — tool-call counts are parsed from it.
 */
fun recordSemanticRun(
    scenario: String,
    agentName: String,
    withMcp: Boolean,
    claimedFix: Boolean,
    rawOutput: String,
    exitCode: Int?,
    agentDurationMs: Long,
    runDir: File?,
    summary: String,
) {
    val mode = if (withMcp) "mcp" else "none"
    val tokens = extractTokenUsage(rawOutput)
    val decodedLogName = when (agentName) {
        "claude" -> "claude-code"
        else -> agentName
    }
    val decoded = runDir
        ?.let { findDecodedLogFile(it, agentName = decodedLogName) }
        ?.let { runCatching { extractDecodedLogMetrics(it.readText()) }.getOrNull() }

    println("[ARENA] $agentName+$mode — $scenario")
    println("[ARENA]   Claimed fix:    $claimedFix")
    println("[ARENA]   Used MCP:       $withMcp")
    println("[ARENA]   Exit code:      ${exitCode ?: -1}")
    println("[ARENA]   Agent time:     ${agentDurationMs / 1000}s")
    if (tokens != null) {
        println("[ARENA]   Tokens in/out:  ${tokens.inputTokens}/${tokens.outputTokens}")
        tokens.costUsd?.let { println("[ARENA]   Cost:           $$it") }
        tokens.numTurns?.let { println("[ARENA]   Turns:          $it") }
    }
    if (decoded != null) {
        // Tool-call counters — "which tools were executed" in each mode.
        println("[ARENA]   exec_code:      ${decoded.execCodeCalls}")
        println("[ARENA]   Read/Edit/Write: ${decoded.readCalls}/${decoded.editCalls}/${decoded.writeCalls}")
        println("[ARENA]   Glob/Grep/Bash: ${decoded.globCalls}/${decoded.grepCalls}/${decoded.bashCalls}")
    }
    println("[ARENA]   Summary:        ${summary.replace('\n', ' ').take(120)}")
}
