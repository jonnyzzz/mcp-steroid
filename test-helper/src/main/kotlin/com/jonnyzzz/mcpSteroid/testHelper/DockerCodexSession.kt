/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.codexMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.CodexOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import java.io.File

/**
 * Manages a Codex CLI session running inside a Docker container.
 * This provides complete isolation from the local system, preventing
 * MCP server registrations from affecting the local Codex config.
 *
 * The API key is read from ~/.openai mounted into the container.
 */
class DockerCodexSession(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
    val model: String = DEFAULT_MODEL,
) : AiAgentSession {
    override val displayName: String = Companion.displayName
    private val mcpRegistrationLog = mutableListOf<McpRegistration>()
    override val mcpRegistrations: List<McpRegistration>
        get() = mcpRegistrationLog.toList()

    override fun registerHttpMcp(mcpUrl: String, mcpName: String) {
        runInContainer(args = codexMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0) { "MCP server registration" }
            .assertNoErrorsInOutput("MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.HTTP,
            url = mcpUrl,
        )
    }

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        registerStdioMcp(session.installDevrigMcp(installDir), mcpName)
    }

    override fun registerStdioMcp(command: StdioMcpCommand, mcpName: String) {
        runInContainer(args = codexMcpAddStdioArgs(command, mcpName))
            .assertExitCode(0) { "devrig MCP server registration" }
            .assertNoErrorsInOutput("devrig MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.STDIO,
            command = command,
        )
    }

    /**
     * Run a codex command inside the Docker container.
     * Note: Codex doesn't support --verbose flag like Claude does.
     */
    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): StartedProcess {
        val codexArgs = buildList {
            add("codex")
            addAll(args)
        }
        val extraEnvVars = buildMap {
            put("OPENAI_API_KEY", apiKey)
            put("CODEX_API_KEY", apiKey)

            if (debug) {
                put("CODEX_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
        }

        return session.startProcessInContainer {
            this
                .args(codexArgs)
                .timeoutSeconds(timeoutSeconds)
                .description("Codex: " + codexArgs.joinToString(" ").take(80))
                .secretPatterns(apiKey)
                .extraEnv(extraEnvVars)
        }
    }

    /**
     * Run codex exec for non-interactive mode.
     *
     * Codex CLI flags for auto-approval and progress visibility:
     * `codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check --json <prompt>`.
     * `--json` streams NDJSON events to stdout for real-time console visibility.
     *
     * The raw NDJSON output is post-processed via [CodexOutputFilter] to produce
     * human-readable text.
     */
    override fun runPrompt(
        prompt: String,
        timeoutSeconds: Long,
    ): AiStartedProcess {
        val codexArgs = buildList {
            add("exec")
            add("--model")
            add(model)
            add("--dangerously-bypass-approvals-and-sandbox")
            add("--skip-git-repo-check")
            add("--json")
            add(prompt)
        }

        return runInContainer(
            args = codexArgs,
            timeoutSeconds = timeoutSeconds
        ).toAiStartedProcess()
    }

    companion object : AIAgentCompanion<DockerCodexSession>("codex-cli") {
        /** Default Codex model for all test runs. Override via system property `codex.model`. */
        const val DEFAULT_MODEL = "gpt-5.5"

        override val displayName = "Codex"
        override val outputFilter get() = CodexOutputFilter()

        override val apiKeyHint = "set env OPENAI_API_KEY or ~/.openai"

        override fun readApiKey(): String? {
            System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".openai")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            return null
        }

        override fun createImpl(session: ContainerDriver, apiKey: String): DockerCodexSession {
            val model = System.getProperty("codex.model", DEFAULT_MODEL)
            return DockerCodexSession(session, apiKey, model = model)
        }
    }
}
