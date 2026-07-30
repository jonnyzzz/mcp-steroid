/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import com.jonnyzzz.mcpSteroid.util.process.ProcessRunSpec
import com.jonnyzzz.mcpSteroid.util.process.runProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class AiAgentCli(
    val binary: String,
    val displayName: String,
) {
    CLAUDE("claude", "Claude"),
    CODEX("codex", "Codex"),
    GEMINI("gemini", "Gemini");

    fun mcpAddStdioArgs(command: StdioMcpCommand, serverName: String = DEFAULT_SERVER_NAME): List<String> = when (this) {
        CLAUDE -> claudeMcpAddStdioArgs(command, serverName)
        CODEX -> codexMcpAddStdioArgs(command, serverName)
        GEMINI -> geminiMcpAddStdioArgs(command, serverName)
    }

    fun mcpRemoveArgs(serverName: String = DEFAULT_SERVER_NAME): List<String> = when (this) {
        CLAUDE -> claudeMcpRemoveArgs(serverName)
        CODEX -> codexMcpRemoveArgs(serverName)
        GEMINI -> geminiMcpRemoveArgs(serverName)
    }

    fun mcpListArgs(): List<String> = when (this) {
        CLAUDE -> claudeMcpListArgs()
        CODEX -> codexMcpListArgs()
        GEMINI -> geminiMcpListArgs()
    }

    companion object {
        fun parse(value: String): AiAgentCli? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.binary == value.lowercase() }
    }
}

data class AiAgentCliInvocation(
    val binary: String,
    val args: List<String>,
    /** Wall-clock budget for the CLI call; enforced by [ProcessAiAgentCliRunner]. */
    val timeout: Duration = 120.seconds,
)

data class AiAgentCliResult(
    val exitCode: Int,
    val output: String,
)

/**
 * Runs one agent-CLI invocation to completion.
 *
 * Implementations may throw
 * [com.jonnyzzz.mcpSteroid.util.process.ProcessRunException] — a timeout
 * ([com.jonnyzzz.mcpSteroid.util.process.ProcessTimeoutException]) or a
 * start failure such as a missing binary
 * ([com.jonnyzzz.mcpSteroid.util.process.ProcessStartException]). Callers
 * with best-effort semantics must catch the family at their boundary.
 */
fun interface AiAgentCliRunner {
    fun run(invocation: AiAgentCliInvocation): AiAgentCliResult
}

/**
 * The production runner: stdin closed, stderr merged into stdout (the same
 * interleaving `redirectErrorStream(true)` always produced here), output
 * captured via the process log files under `~/.mcp-steroid/logs`, and the
 * invocation's timeout enforced — a hung agent CLI can no longer hang
 * devrig (the process tree is killed and
 * [com.jonnyzzz.mcpSteroid.util.process.ProcessTimeoutException] thrown).
 */
class ProcessAiAgentCliRunner : AiAgentCliRunner {
    override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult {
        val result = runProcess(
            ProcessRunSpec(
                command = listOf(invocation.binary) + invocation.args,
                timeout = invocation.timeout,
                name = invocation.binary,
            ),
        )
        return AiAgentCliResult(result.exitCode, result.logs.readStdout())
    }
}

fun mcpAddStdioInvocation(
    agent: AiAgentCli,
    command: StdioMcpCommand,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpAddStdioArgs(command, serverName),
        timeout = 120.seconds,
    )

fun mcpRemoveInvocation(
    agent: AiAgentCli,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpRemoveArgs(serverName),
        // Removals are quick config edits; a hung CLI must not stall the install flow for long.
        timeout = 30.seconds,
    )

fun mcpListInvocation(
    agent: AiAgentCli,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpListArgs(),
        // Listing is best-effort at every call site; fail fast instead of stalling.
        timeout = 30.seconds,
    )
