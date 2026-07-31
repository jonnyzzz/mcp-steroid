/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
)

data class AiAgentCliResult(
    val exitCode: Int,
    val output: String,
)

fun interface AiAgentCliRunner {
    fun run(invocation: AiAgentCliInvocation): AiAgentCliResult
}

/**
 * The agent CLI process could not be LAUNCHED — the binary is missing from PATH, or is a shape the
 * OS cannot spawn directly (a Windows `.cmd` npm shim needs `cmd.exe /d /c`). Distinct from other
 * IOExceptions (temp-file creation, output read) so callers can turn exactly this case into
 * "install the CLI first" guidance (jonnyzzz/mcp-steroid#342) without masking infrastructure
 * failures as a missing CLI.
 */
class AgentCliNotLaunchableException(
    val binary: String,
    cause: IOException,
) : IOException("could not launch '$binary': ${cause.message}", cause)

/**
 * Runs an agent CLI to completion with a hard [timeout].
 *
 * Output goes to a TEMP FILE, not a pipe: with a pipe, draining via
 * `readText()` blocks until EOF and no timeout can ever fire — a hung agent
 * CLI then hangs devrig forever, uninterruptibly (pipe reads ignore thread
 * interrupts). With a file redirect, `waitFor(timeout)` is real timeout
 * enforcement. stderr stays merged into stdout (same interleaving as the
 * previous `redirectErrorStream(true)` behavior); stdin is closed right
 * after start so a CLI that reads stdin sees EOF instead of blocking on a
 * pipe nobody writes to.
 *
 * On timeout the child is killed and [IllegalStateException] is thrown:
 * a loud, bounded failure instead of an unbounded hang.
 */
class ProcessAiAgentCliRunner(
    private val timeout: Duration = 120.seconds,
) : AiAgentCliRunner {
    override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult {
        val outputFile = Files.createTempFile("devrig-agent-cli-", ".out")
        try {
            val process = try {
                ProcessBuilder(listOf(invocation.binary) + invocation.args)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start()
            } catch (e: IOException) {
                throw AgentCliNotLaunchableException(invocation.binary, e)
            }
            runCatching { process.outputStream.close() } // stdin: immediate EOF
            if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "'${invocation.binary} ${invocation.args.joinToString(" ")}' " +
                        "timed out after $timeout and was killed",
                )
            }
            return AiAgentCliResult(process.exitValue(), Files.readString(outputFile, Charsets.UTF_8))
        } finally {
            try {
                Files.deleteIfExists(outputFile)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not delete agent CLI output file $outputFile: $e")
            }
        }
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
    )

fun mcpRemoveInvocation(
    agent: AiAgentCli,
    serverName: String = DEFAULT_SERVER_NAME,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpRemoveArgs(serverName),
    )

fun mcpListInvocation(
    agent: AiAgentCli,
): AiAgentCliInvocation =
    AiAgentCliInvocation(
        binary = agent.binary,
        args = agent.mcpListArgs(),
    )
