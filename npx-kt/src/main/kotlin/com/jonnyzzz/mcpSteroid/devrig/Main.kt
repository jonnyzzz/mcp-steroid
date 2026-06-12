/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.devrig.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main(rawArgs: Array<String>) {
    // Replace stdout immediately. MCP stdio reserves the original stdout for
    // frames, and command detection / service setup must not leak there.
    val mcpStdin = System.`in`
    val mcpStdout = System.out
    System.setOut(System.err)

    val command = parseDevrigCommand(rawArgs)
    val headliner = buildHeadliner()
    if (command is DevrigCommand.MCP) {
        System.err.println(headliner)
    } else {
        System.setOut(mcpStdout)
    }

    val homePaths = resolveHomePathsOrDie()

    //setup logging. That is essential to avoid logger usages BEFORE this statement
    configureLoggingAndLogStarted(homePaths, rawArgs.toList(), command.debug)

    val lifetime = CloseableStackHost()
    val exitCode = try {
        DevrigServices(
            lifetime = lifetime,
            homePaths = homePaths,
            mcpStdin = mcpStdin,
            mcpStdout = mcpStdout,
        ).mainImpl1(command, headliner)
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        64
    } finally {
        lifetime.closeAllStacks()
    }
    exitProcess(exitCode)
}

private fun buildHeadliner(): String = buildString {
    val devrigVersion = DevrigVersionMetadata.getDevrigVersion()
    appendLine("devrig v$devrigVersion — This environment empowers your AI with the best deterministic coding tools.")
    appendLine()
}

fun DevrigServices.mainImpl1(
    command: DevrigCommand,
    headliner: String,
): Int {
    class DevrigCoroutineExceptionHandler

    val log = logger<DevrigCoroutineExceptionHandler>()
    val exceptionHandler = CoroutineExceptionHandler { context, throwable ->
        log.warn("devrig coroutine exception: ${throwable.message} in $context", throwable)
    }

    return runBlocking(Dispatchers.IO + CoroutineName("devrig") + exceptionHandler + SupervisorJob()) {
        coroutineScope {
            mainImpl2(command, headliner)
        }
    }
}

suspend fun DevrigServices.mainImpl2(
    command: DevrigCommand,
    headliner: String,
): Int = coroutineScope {
    // For the MCP command, the running McpServerCore becomes available once the
    // stdio server is built; the update check broadcasts its notice over it (in
    // addition to stderr) as a `notifications/message`. For non-MCP commands the
    // deferred is never completed, so the notice falls back to stderr only.
    val mcpServerReady = CompletableDeferred<McpServerCore>()

    if (command.runsTool()) {
        backgroundScope.launch {
            delay(Random.nextInt(200, 1300).milliseconds)
            checkForUpdates(onNotice = { message ->
                if (command is DevrigCommand.MCP) {
                    backgroundScope.launch {
                        val core = mcpServerReady.await()
                        core.broadcastLogMessage("warning", "devrig.updates", JsonPrimitive(message))
                    }
                }
            })
        }

        backgroundScope.launch {
            beacon.captureStarted(command)
        }
    }

    if (command is DevrigCommand.MCP) {
        beacon.runHeartbeat()
        try {
            mainImplMcp(onServerReady = { mcpServerReady.complete(it) })
            return@coroutineScope 0
        } catch (t: Throwable) {
            System.err.println("Unexpected error ${t.message}")
            t.printStackTrace(System.err)
            return@coroutineScope 64
        }
    }

    if (command.printsHeadliner()) {
        mcpStdout.println(headliner)
    }
    try {
        runCli(command)
    } catch (t: Throwable) {
        System.err.println("Unexpected error calling $command. ${t.message}")
        t.printStackTrace(System.err)
        64
    }
}

private fun DevrigCommand.runsTool(): Boolean = when (this) {
    is DevrigCommand.MCP,
    is DevrigCommand.DevrigCommandBackend,
    is DevrigCommand.DevrigCommandBackendDownload,
    is DevrigCommand.DevrigCommandBackendStart,
    is DevrigCommand.DevrigCommandBackendStop,
    is DevrigCommand.DevrigCommandBackendProvision,
    is DevrigCommand.DevrigCommandProject,
    is DevrigCommand.DevrigCommandInstall -> true
    is DevrigCommand.DevrigCommandHelp,
    is DevrigCommand.DevrigCommandVersion,
    is DevrigCommand.DevrigCommandParseError -> false
}

private fun DevrigCommand.printsHeadliner(): Boolean =
    runsTool() && this !is DevrigCommand.MCP && !json

suspend fun DevrigServices.mainImplMcp(
    onServerReady: (McpServerCore) -> Unit = {},
) = coroutineScope {
    // devrig boots a real MCP stdio server backed by McpStdioServer and
    // McpSteroidTools. Alongside the stdio server, the IDE monitor runs discovery from
    // <pid>.mcp-steroid JSON markers in the devrig home markers directory
    // plus legacy .<pid>.mcp-steroid markers from $HOME during the transition.
    // The monitor opens one POST <rpcBaseUrl>/projects/stream per IDE and receives
    // push notifications on project open/close.

    val discoveryJob = ideDiscovery.start(this)
    val monitorJob = ideMonitor.start(this)
    val portDiscoveryJob = portDiscovery.start(this)
    try {
        runStubStdioMcpServer(this@mainImplMcp, onServerReady = onServerReady)
    } finally {
        portDiscoveryJob.cancel()
        monitorJob.cancel()
        discoveryJob.cancel()
    }
}
