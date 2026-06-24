/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.AgentProgressOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.process.ProcessResult
import com.jonnyzzz.mcpSteroid.process.StartedProcess
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File

/**
 * Result from running an AI agent process.
 * Contains both filtered (human-readable) output and raw (NDJSON) output.
 */
class AiProcessResult(
    override val exitCode: Int?,
    override val stdout: String,
    override val stderr: String,
    /** Raw unfiltered stdout (NDJSON) before output filter was applied */
    val rawStdout: String,
) : ProcessResult {
    override fun toString(): String =
        "AiProcessResult(exitCode=$exitCode, stdout=${stdout.take(500)}, stderr=${stderr.take(500)})"
}

/**
 * AI-specific overload of [com.jonnyzzz.mcpSteroid.process.assertExitCode]: narrows the
 * return type to [AiProcessResult] so callers keep access to the filtered/raw output.
 */
fun AiStartedProcess.assertExitCode(expectedExitCode: Int, message: ProcessResult.() -> String): AiProcessResult =
    awaitForProcessFinish().assertExitCode(expectedExitCode, message)

abstract class AIContainerBase(
    private val session: ContainerDriver,
    private val apiKey: String,
    private val debug: Boolean = false,
    private val workdirInContainer: String,
    override val displayName: String
) : AiAgentSession {
//     = this.ComCompanion.displayName

    override fun registerDevrigMcp(installDir: File, mcpName: String) {
        registerStdioMcp(session.installDevrigMcp(installDir), mcpName)
    }
}

abstract class AIAgentCompanion<T : Any>(val dockerFileBase: String) {
    abstract val displayName: String
    abstract val outputFilter: AgentProgressOutputFilter

    /**
     * Returns the API key for this agent, or `null` if it cannot be found.
     * Each subclass checks env vars and well-known key files.
     */
    protected abstract fun readApiKey(): String?

    /**
     * `true` when [readApiKey] returns a real key (not `null`, not an unresolved
     * `%credentialsJSON:…%` TeamCity reference). Used by JUnit 3 / `BasePlatformTestCase`
     * tests via `UsefulTestCase.shouldRunTest()` — the JUnit 4 `@Rule` chain there
     * recognises `AssumptionViolatedException` thrown from a Rule and reports the test
     * as ignored, unlike exceptions thrown from inside `TestCase.runTest()` which the
     * JUnit 3↔4 bridge converts to failures. Callers in JUnit 5 paths just rely on
     * [skipTestWhenKeyMissing] + [requireApiKey].
     */
    fun isApiKeyAvailable(): Boolean {
        val key = readApiKey() ?: return false
        return !key.startsWith("%")
    }

    /** Human-readable description of where the key can come from (for error/skip messages). */
    protected abstract val apiKeyHint: String

    /**
     * Documented exception to the CLAUDE.md "no test-level skips" rule. When `true`
     * and [readApiKey] returns `null`, [requireApiKey] reports the test as **ignored**
     * (via JUnit's [org.junit.AssumptionViolatedException], honored by JUnit 3/4/5
     * runners and the IntelliJ test platform) instead of failing. Default `false`
     * preserves fail-fast for agents whose keys are configured on CI; only Gemini
     * opts in because the TeamCity server has no Gemini token and there is no
     * plan to add one.
     *
     * The unresolved-TC-reference branch (`%credentialsJSON:…%`) still throws
     * regardless of this flag — that case is a real TC misconfiguration that
     * must stay visible.
     */
    protected open val skipTestWhenKeyMissing: Boolean = false

    private fun requireApiKey(): String {
        val key = readApiKey()

        // Reject unresolved TeamCity credential references (%credentialsJSON:...%)
        // which look non-blank but are not actual API keys.
        if (key != null && !key.startsWith("%")) {
            return key
        }

        val isUnresolvedTcRef = key != null
        val message = if (isUnresolvedTcRef) {
            "$displayName API key is an unresolved TeamCity reference ($apiKeyHint)"
        } else {
            "$displayName API key not found ($apiKeyHint)"
        }

        if (skipTestWhenKeyMissing && !isUnresolvedTcRef) {
            // Opt-in skip — see [skipTestWhenKeyMissing] kdoc. Recognised by
            // JUnit 3/4/5 + IntelliJ's BasePlatformTestCase runner, which
            // report the result as ignored rather than failed.
            throw org.junit.AssumptionViolatedException(message)
        }

        // Fail-fast default. Earlier revisions threw TestAbortedException /
        // Assume.assumeTrue unconditionally; that masked a real TC credentials
        // misconfiguration as "0 failed, 20 ignored" for weeks. The
        // unresolved-TC-ref branch keeps that lesson — don't loosen it.
        error(message)
    }

    fun create(lifetime: CloseableStack): T {
        val dockerfilePath = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-helper/src/main/docker/$dockerFileBase/Dockerfile")
            .toFile()
        require(dockerfilePath.isFile) { "Docker file $dockerfilePath must exist" }

        val imageId = buildDockerImage(
            logPrefix = dockerFileBase.uppercase(),
            dockerfilePath,
            timeoutSeconds = 600,
        )

        val session = startDockerContainerAndDispose(lifetime,
            StartContainerRequest()
                .image(imageId)
        )

        return create(session)
    }

    fun create(session: ContainerDriver): T {
        println("[DOCKER-${dockerFileBase.uppercase()}] Session created in container")
        val apiKey = requireApiKey()
        return createImpl(session, apiKey)
    }

    fun StartedProcess.toAiStartedProcess(): AiStartedProcess {
        return object: AiStartedProcess, StartedProcess by this@toAiStartedProcess {
            override val outputFilter: AgentProgressOutputFilter
                get() = this@AIAgentCompanion.outputFilter

            override fun awaitForProcessFinish(): AiProcessResult {
                val rawResult = this@toAiStartedProcess.awaitForProcessFinish()

                return AiProcessResult(
                    exitCode = rawResult.exitCode ?: error("Process ${this@toAiStartedProcess} finished with exit code ${rawResult.exitCode}"),
                    stdout = this.outputFilter.filterText(rawResult.stdout),
                    stderr = rawResult.stderr,
                    rawStdout = rawResult.stdout,
                )
            }

            override fun toString(): String {
                return "$displayName-${this@toAiStartedProcess}"
            }
        }
    }


    protected abstract fun createImpl(session: ContainerDriver, apiKey: String): T
}
