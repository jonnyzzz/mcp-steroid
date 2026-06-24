/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
@file:Suppress("RedundantOverride")

package com.jonnyzzz.mcpSteroid

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerGeminiSession
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.Assert
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for Gemini CLI with MCP server.
 *
 * Prerequisites:
 * - Docker must be installed and running
 * - GEMINI_API_KEY must be available (either in env or ~/.vertex)
 */
class CliGeminiIntegrationTest : CliIntegrationTestBase() {
    private fun geminiSession() = DockerGeminiSession.create(lifetime)

    override fun createAiSession(): AiAgentSession = geminiSession()

    fun testGeminiInstalled(): Unit = timeoutRunBlocking(180.seconds) {
        geminiSession()
            .runInContainer(args = listOf("--version"))
            .assertExitCode(0) { "Gemini failed" }
    }

    fun testMcpServerRegistration() {
        val mcpName = "intellij"
        timeoutRunBlocking(180.seconds) {
            val session = geminiSession()
            session.registerHttpMcp(resolveDockerUrl(), mcpName)
            session.runInContainer(listOf("mcp", "list"))
                .assertExitCode(0) { "mcp list should succeed" }
                .assertOutputContains(mcpName, message = "mcp list should contain registered server")
        }
    }


    override fun testDiscoversSteroidTools() {
        //needed to make test runner work
        super.testDiscoversSteroidTools()
    }

    override fun testSystemPropertyCanBeRead() {
        //needed to make test runner work
        super.testSystemPropertyCanBeRead()
    }

    override fun testCompilationErrorsDelivered() {
        //needed to make test runner work
        super.testCompilationErrorsDelivered()
    }

    override fun testCompilationWarningsDelivered() {
        //needed to make test runner work
        super.testCompilationWarningsDelivered()
    }

    override fun testExecSessionReset() {
        //the test is ignored
        //needed to make test runner work
        //super.testExecSessionReset()
    }
}
