/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.AiStartedProcess
import com.jonnyzzz.mcpSteroid.process.ProcessStreamType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps an [AiAgentSession] to display agent activity in the [ConsoleDriver].
 *
 * Before running a prompt, the prompt text is shown in bright ANSI color.
 * During execution, agent output is pumped to the console in real-time:
 * STDOUT lines are decoded through the agent-specific output filter (NDJSON → readable text);
 * STDERR lines are forwarded directly.
 *
 * Each [runPrompt] call writes two log files into [logDir]:
 *  - `agent-{agentName}-{N}-raw.ndjson`    — raw NDJSON lines from STDOUT (unfiltered)
 *  - `agent-{agentName}-{N}-decoded.txt`   — the human-readable decoded output
 */
class ConsoleAwareAgentSession(
    private val delegate: AiAgentSession,
    private val console: ConsoleDriver,
    private val agentName: String,
    private val logDir: File,
) : AiAgentSession {
    override val displayName: String
        get() = delegate.displayName
    override val mcpRegistrations
        get() = delegate.mcpRegistrations
    override val strictMcpConfigJson
        get() = delegate.strictMcpConfigJson

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val promptCounter = AtomicInteger(0)

    override fun runPrompt(prompt: String, timeoutSeconds: Long): AiStartedProcess {
        console.writePrompt(agentName, prompt)
        console.writeInfo("Running $agentName...")

        val aiProcess = delegate.runPrompt(prompt, timeoutSeconds)
        val promptIndex = promptCounter.incrementAndGet()

        // Pump process output to the console in real-time and optionally persist log files.
        // Each STDOUT line is decoded through the agent-specific NDJSON filter;
        // STDERR lines are forwarded as-is.
        scope.launch {
            val safeName = agentName.replace(' ', '-').lowercase()
            val rawWriter = PrintWriter(FileWriter(File(logDir, "agent-$safeName-$promptIndex-raw.ndjson")), true)
            val decodedWriter = PrintWriter(FileWriter(File(logDir, "agent-$safeName-$promptIndex-decoded.txt")), true)
            try {
                aiProcess.messagesFlow.collect { streamLine ->
                    when (streamLine.type) {
                        ProcessStreamType.STDOUT -> {
                            rawWriter.println(streamLine.line)
                            val filtered = aiProcess.outputFilter.filterText(streamLine.line)
                            filtered.lines().forEach { line ->
                                console.writeLine(line)
                                decodedWriter.println(line)
                            }
                        }

                        ProcessStreamType.STDERR -> {
                            val text = "[stderr] ${streamLine.line}"
                            console.writeLine(text)
                            decodedWriter.println(text)
                        }

                        ProcessStreamType.INFO -> {
                            val text = "[INFO] ${streamLine.line}"
                            console.writeLine(text)
                            decodedWriter.println(text)
                        }
                    }
                }
            } finally {
                rawWriter.close()
                decodedWriter.close()
            }
        }

        return aiProcess
    }

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        delegate.registerHttpMcp(mcpUrl, mcpName)
    }

    override fun registerStdioMcp(command: StdioMcpCommand, mcpName: String) {
        delegate.registerStdioMcp(command, mcpName)
    }

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        delegate.registerDevrigMcp(installDir, mcpName)
    }
}
