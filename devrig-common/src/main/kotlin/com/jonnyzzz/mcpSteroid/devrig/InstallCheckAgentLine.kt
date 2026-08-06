/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli

/**
 * The per-agent stdout contract of bare `devrig install --check` — the all-agents mode. Like the exit
 * codes next door (`InstallCheckExitCodes.kt`) this is a contract between two processes, so it lives
 * where both can see it: devrig prints one [renderInstallCheckAgentLine] line per supported agent, and
 * the IDE plugin's settings page reads them back ([parseInstallCheckAgentLines]) to render every agent
 * row from ONE devrig spawn per page show — a process exit code can only carry one agent's answer,
 * which is why the multi-agent answer moves onto stdout.
 *
 * Additive only, and a consumer of an OLDER devrig must degrade, never misreport: a devrig that
 * predates the all-agents mode rejects bare `install --check` as a usage error and prints no lines,
 * so every agent parses as absent — the caller's "could not find out" state, not "not registered".
 */
enum class InstallCheckAgentStatus(val token: String) {
    /** The registration is canonical — re-running `devrig install <agent>` would change nothing. */
    REGISTERED("registered"),

    /** Install would change something: no entry, a stale command, duplicates, a non-canonical name. */
    DRIFT("drift"),

    /** Canonical but switched off in the agent's own config — the fact no agent's `mcp list` reports. */
    DISABLED("disabled"),

    /** The agent's CLI is not on PATH — there is nothing to register with. */
    CLI_MISSING("cli-missing"),

    /** The state could not be determined: the CLI is present but the check itself failed. */
    CHECK_FAILED("check-failed");

    companion object {
        fun parse(token: String): InstallCheckAgentStatus? = entries.firstOrNull { it.token == token }
    }
}

/**
 * The marker every per-agent result line starts with. Distinctive on purpose: the same stdout carries
 * human prose (the header, the IDE-reachability report, the summary), and a parser must be able to
 * pick the result lines out of it by prefix alone.
 */
const val INSTALL_CHECK_AGENT_LINE_PREFIX = "install-check:"

/** One agent's answer as the line devrig prints: `install-check: <binary>=<status>`. */
fun renderInstallCheckAgentLine(agent: AiAgentCli, status: InstallCheckAgentStatus): String =
    "$INSTALL_CHECK_AGENT_LINE_PREFIX ${agent.binary}=${status.token}"

/**
 * Every per-agent result line found in [output], in print order. Non-result lines are skipped (the
 * output deliberately mixes them with prose), and so are lines naming an agent or a status this
 * parser does not know — the additive-contract rule: a newer devrig may answer for agents or with
 * statuses an older consumer has no word for, and those must not break the answers it does have.
 * An agent absent from the result is the caller's "could not find out", never "not registered".
 */
fun parseInstallCheckAgentLines(output: String): Map<AiAgentCli, InstallCheckAgentStatus> {
    val result = LinkedHashMap<AiAgentCli, InstallCheckAgentStatus>()
    for (line in output.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith(INSTALL_CHECK_AGENT_LINE_PREFIX)) continue
        val body = trimmed.removePrefix(INSTALL_CHECK_AGENT_LINE_PREFIX).trim()
        val agent = AiAgentCli.parse(body.substringBefore('=')) ?: continue
        val status = InstallCheckAgentStatus.parse(body.substringAfter('=', missingDelimiterValue = "")) ?: continue
        result[agent] = status
    }
    return result
}
