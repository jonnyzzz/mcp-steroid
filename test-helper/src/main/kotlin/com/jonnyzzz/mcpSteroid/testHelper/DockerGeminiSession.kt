/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddArgs
import com.jonnyzzz.mcpSteroid.aiAgents.geminiMcpAddStdioArgs
import com.jonnyzzz.mcpSteroid.filter.GeminiOutputFilter
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoErrorsInOutput
import java.io.File

/**
 * Manages a Gemini CLI session running inside a Docker container.
 */
class DockerGeminiSession(
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
        runInContainer(args = geminiMcpAddArgs(mcpUrl, mcpName))
            .assertExitCode(0) { "MCP server registration" }
            .assertNoErrorsInOutput(message = "MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.HTTP,
            url = mcpUrl,
        )
    }

    override fun registerStdioMcp(command: StdioMcpCommand, mcpName: String) {
        runInContainer(args = geminiMcpAddStdioArgs(command, mcpName))
            .assertExitCode(0) { "devrig MCP server registration" }
            .assertNoErrorsInOutput(message = "devrig MCP server registration")
        mcpRegistrationLog += McpRegistration(
            name = mcpName,
            transport = McpRegistrationTransport.STDIO,
            command = command,
        )
    }

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        registerStdioMcp(session.installDevrigMcp(installDir), mcpName)
    }

    fun runInContainer(args: List<String>, timeoutSeconds: Long = 120): StartedProcess {
        val geminiArgs = buildList {
            add("gemini")
            if (debug) {
                add("--debug")
            }
            addAll(args.toList())
        }
        val env = buildMap {
            put("GEMINI_API_KEY", apiKey)
            // Route through a host-side Gemini-compatible gateway when one is configured (no-op on CI).
            resolveContainerAgentBaseUrl("GOOGLE_GEMINI_BASE_URL", "GEMINI_BASE_URL")?.let {
                put("GOOGLE_GEMINI_BASE_URL", it)
            }
            // Newer Gemini CLI builds (>= late 2026) demote `--approval-mode yolo`.
            // If the working directory is not trusted, they then refuse to run
            // with exit 55 and a "trusted directory" error.
            // The headless contract for automated environments is to opt the
            // workspace in via this env var (see Gemini docs at
            // /docs/cli/trusted-folders/#headless-and-automated-environments).
            put("GEMINI_CLI_TRUST_WORKSPACE", "true")
            if (debug) {
                put("GEMINI_DEBUG", "1")
                put("MCP_DEBUG", "1")
                put("DEBUG", "*")
            }
        }

        return session.startProcessInContainer {
            this
                .args(geminiArgs)
                .timeoutSeconds(timeoutSeconds = timeoutSeconds)
                .description(geminiArgs.joinToString(" ").take(80))
                .secretPatterns(apiKey)
                .extraEnv(env)
        }
    }

    /**
     * Run Gemini in non-interactive mode with stream-json output enabled.
     *
     * Primary flags:
     * `--screen-reader true --sandbox-mode none --approval-mode yolo --output-format stream-json --prompt <prompt>`.
     *
     * Newer Gemini CLI versions replaced `--sandbox-mode none` with `--sandbox false`.
     * We retry once with modern syntax when the legacy flag is rejected.
     *
     * The raw NDJSON output is post-processed via [GeminiOutputFilter] to produce
     * human-readable text.
     */
    override fun runPrompt(prompt: String, timeoutSeconds: Long): AiStartedProcess {
        val args = listOf(
            "--model", model,
            "--screen-reader", "true",
            "--approval-mode", "yolo",
            "--output-format", "stream-json",
            "--prompt", prompt,
        )

        return runInContainer(
            args = args,
            timeoutSeconds = timeoutSeconds
        ).toAiStartedProcess()
    }

    companion object : AIAgentCompanion<DockerGeminiSession>("gemini-cli") {
        /** Default Gemini model for all test runs. Override via system property `gemini.model`. */
        const val DEFAULT_MODEL = "gemini-3.1-pro-preview"

        override val displayName = "Gemini"
        override val outputFilter get() = GeminiOutputFilter()

        override val apiKeyHint = "set env GEMINI_API_KEY, GOOGLE_API_KEY, or ~/.vertex"

        // The TeamCity server does not have a Gemini credentialsJSON token configured.
        // There is no plan to add one, so Gemini-using tests should be reported
        // as ignored rather than failed when the key is absent.
        // See [AIAgentCompanion.skipTestWhenKeyMissing].
        override val skipTestWhenKeyMissing = true

        override fun readApiKey(): String? {
            System.getenv("GEMINI_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            System.getenv("GOOGLE_API_KEY")?.takeIf { it.isNotBlank() }?.let { return it }
            val keyFile = File(System.getProperty("user.home"), ".vertex")
            if (keyFile.exists()) {
                val content = keyFile.readText().trim()
                if (content.isNotBlank()) return content
            }
            return null
        }

        override fun createImpl(session: ContainerDriver, apiKey: String): DockerGeminiSession {
            val model = System.getProperty("gemini.model", DEFAULT_MODEL)
            return DockerGeminiSession(session, apiKey, model = model)
        }
    }
}
