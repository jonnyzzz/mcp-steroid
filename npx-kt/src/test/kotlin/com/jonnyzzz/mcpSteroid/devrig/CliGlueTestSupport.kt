/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Shared harness for the MCP-as-CLI *glue* tests: they exercise the CLI command functions with a fake
 * [McpSteroidTools] so we assert the args→`*Params`→render→exit-code wiring WITHOUT a live IDE. The
 * payload→wire mapping of the real handlers is covered separately by `DevrigToolBridgeClientTest`.
 */

/** Captured result of running one CLI command against buffers. */
data class CliRun(val exit: Int, val stdout: String, val stderr: String)

/**
 * Runs [invoke] with a [DevrigServices] whose stdout is a buffer and with `System.err` redirected to a
 * buffer, returning both plus the exit code. [stdin] feeds `--code-file=-` style stdin reads.
 */
fun runCliCommand(
    homePaths: HomePaths,
    stdin: ByteArray = ByteArray(0),
    invoke: DevrigServices.() -> Int,
): CliRun {
    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    val originalErr = System.err
    System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
    val lifetime = CloseableStackHost()
    val exit = try {
        DevrigServices(
            lifetime = lifetime,
            homePaths = homePaths,
            mcpStdin = ByteArrayInputStream(stdin),
            mcpStdout = PrintStream(outBuf, true, Charsets.UTF_8),
        ).invoke()
    } finally {
        lifetime.closeAllStacks()
        System.setErr(originalErr)
    }
    return CliRun(
        exit = exit,
        stdout = outBuf.toString(Charsets.UTF_8).replace("\r\n", "\n"),
        stderr = errBuf.toString(Charsets.UTF_8).replace("\r\n", "\n"),
    )
}

/** An [McpSteroidTools] that returns the handlers it was given, keyed by their interface type. */
class FakeMcpSteroidTools(private val handlers: Map<Class<*>, Any>) : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T =
        type.cast(handlers[type] ?: error("FakeMcpSteroidTools: no handler wired for ${type.name}"))
}

fun fakeTools(vararg pairs: Pair<Class<*>, Any>): FakeMcpSteroidTools =
    FakeMcpSteroidTools(pairs.toMap())

fun okResult(text: String = "ok"): ToolCallResult =
    ToolCallResult(content = listOf(ContentItem.Text(text)))

fun toolErrorResult(text: String = "boom"): ToolCallResult =
    ToolCallResult(content = listOf(ContentItem.Text(text)), isError = true)

// ---------------------------- throwing fakes (routing / bridge failures) ----------------------------

class ThrowingExecuteCode(private val ex: Throwable) : ExecuteCodeToolHandler {
    override suspend fun executeCode(projectName: String, execCodeParams: ExecCodeParams, callProgress: McpProgressReporter): ToolCallResult = throw ex
}

class ThrowingFeedback(private val ex: Throwable) : ExecuteFeedbackToolHandler {
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult = throw ex
}

class ThrowingInput(private val ex: Throwable) : VisionInputToolHandler {
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult = throw ex
}

class ThrowingScreenshot(private val ex: Throwable) : VisionScreenshotToolHandler {
    override suspend fun screenshotWindow(projectName: String, screenshotParams: ScreenshotParams, mcpProgressReporter: McpProgressReporter): ToolCallResult = throw ex
}

class ThrowingOpenProject(private val ex: Throwable) : OpenProjectToolHandler {
    override suspend fun handleOpenProject(openProjectParams: OpenProjectParams, callProgress: McpProgressReporter): ToolCallResult = throw ex
}

class ThrowingListWindows(private val ex: Throwable) : ListWindowsToolHandler {
    override suspend fun collectListWindowsResponse(): ListWindowsResponse = throw ex
}

// ---------------------------- recording fake handlers ----------------------------

class RecordingExecuteCode(private val result: ToolCallResult = okResult()) : ExecuteCodeToolHandler {
    var projectName: String? = null
    var params: ExecCodeParams? = null
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        this.projectName = projectName
        this.params = execCodeParams
        return result
    }
}

class RecordingFeedback(private val result: ToolCallResult = okResult()) : ExecuteFeedbackToolHandler {
    var projectName: String? = null
    var params: FeedbackParams? = null
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        this.projectName = projectName
        this.params = params
        return result
    }
}

class RecordingInput(private val result: ToolCallResult = okResult()) : VisionInputToolHandler {
    var projectName: String? = null
    var params: InputParams? = null
    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        this.projectName = projectName
        this.params = inputParams
        return result
    }
}

class RecordingScreenshot(private val result: ToolCallResult) : VisionScreenshotToolHandler {
    var projectName: String? = null
    var params: ScreenshotParams? = null
    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter,
    ): ToolCallResult {
        this.projectName = projectName
        this.params = screenshotParams
        return result
    }
}

class RecordingOpenProject(private val result: ToolCallResult = okResult()) : OpenProjectToolHandler {
    var params: OpenProjectParams? = null
    override suspend fun handleOpenProject(
        openProjectParams: OpenProjectParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        this.params = openProjectParams
        return result
    }
}

/** Returns the queued responses in order (last one repeats) — for the open_project --wait poll loop. */
class SequencedListWindows(private val responses: List<ListWindowsResponse>) : ListWindowsToolHandler {
    var calls: Int = 0
    override suspend fun collectListWindowsResponse(): ListWindowsResponse {
        val idx = minOf(calls, responses.lastIndex)
        calls++
        return responses[idx]
    }
}

/**
 * Throws on the first [failFirst] poll(s) (a transient bridge failure the --wait loop must tolerate),
 * then returns [then] on every subsequent poll.
 */
class FlakyListWindows(private val failFirst: Int, private val then: ListWindowsResponse) : ListWindowsToolHandler {
    var calls: Int = 0
    override suspend fun collectListWindowsResponse(): ListWindowsResponse {
        val current = calls++
        if (current < failFirst) throw RuntimeException("transient poll failure #$current")
        return then
    }
}
