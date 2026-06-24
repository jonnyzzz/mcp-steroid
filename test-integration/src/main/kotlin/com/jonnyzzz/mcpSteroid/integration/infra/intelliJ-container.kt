/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.commitContainerToImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.process.ProcessResult
import java.io.File

/**
 * Controls how AI agents connect to MCP Steroid inside the test container.
 *
 * [IntelliJContainer.aiAgents] ([AiAgentDriver]) is always created regardless of mode.
 * This enum only determines which MCP transport (if any) is registered with each agent.
 *
 * Pass as `IntelliJContainer.create`'s `aiMode` parameter.
 */
enum class AiMode {
    /**
     * Agents are available, but MCP Steroid is NOT registered with them.
     * Use for pure IDE/infrastructure tests that don't need MCP Steroid tools.
     */
    NONE,

    /**
     * Agents connect to MCP Steroid via HTTP (default).
     * Each agent has AiAgentSession.registerHttpMcp called with the guest-side URL.
     */
    AI_MCP,

    /**
     * Agents connect to MCP Steroid via devrig stdio.
     * [DevrigSteroidDriver] is deployed before agents are initialized; each agent
     * has `AiAgentSession.registerStdioMcp` called with the resulting command.
     */
    AI_DEVRIG,
}

/**
 * Manages a Docker container running IntelliJ IDEA with the MCP Steroid plugin.
 * Assembles the Docker build context from separate artifacts and starts a named container.
 *
 * The container is NOT removed after the test — it stays around for debugging.
 * It IS removed before the next test run (by name).
 *
 * All IDE directories, video, and screenshots are mounted to a timestamped
 * run directory under testOutputDir for easy inspection and debugging.
 */
class IntelliJContainer(
    val lifetime: CloseableStack,

    val opts: IntelliJContainerOpts,

    val gui: GuiContainer,

    val runDirInContainer: File,
    val scope: ContainerDriver,

    val intellijDriver: IntelliJDriver,

    private val intellij: RunningContainerProcess,

    val mcpSteroid: McpSteroidDriver,

    /**
     * AI agent driver — always present.
     * Whether agents have MCP Steroid registered depends on the [AiMode] used at creation.
     */
    val aiAgents: AiAgentDriver,

    /**
     * Relative path (from project root) of the file to open when the IDE starts.
     * When null, the default README.md / first source file fallback is used.
     */
     val openFileOnStart: String? = null,
) {
    val input: XcvbInputDriver by gui::inputDriver
    val console: ConsoleDriver by gui::console
    val windows: XcvbWindowDriver by gui::windowsDriver

    /**
     * The project this container was created for. Carries its declared JDK version and
     * build systems so [waitForProjectReady] can set the project SDK and import each build
     * system without the caller restating them.
     */
    val project: IntelliJProject by opts::project

    val windowLayout: WindowLayoutManager by gui::windowsLayout

    val pid by intellij::pid

    fun diagnosticsSummary(): String = buildString {
        appendLine("RUN_DIR=${runDirInContainer.absolutePath}")
        appendLine("SESSION_INFO=${File(runDirInContainer, "session-info.txt").absolutePath}")
        appendLine("SCREENSHOT_DIR=${File(runDirInContainer, "screenshot").absolutePath}")
        appendLine("VIDEO_DIR=${File(runDirInContainer, "video").absolutePath}")
        appendLine("IDE_LOG=${File(runDirInContainer, "intellij/ide-log/idea.log").absolutePath}")
        appendLine("AGENT_LOG_DIR=${runDirInContainer.absolutePath}")
        appendLine("AGENT_LOG_PATTERN=agent-<name>-<N>-raw.ndjson / agent-<name>-<N>-decoded.txt")
    }.trimEnd()

    companion object
}
