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
    // The devrig binary owns ~/.mcp-steroid/bin/devrig: (re)create/update it on start so it self-heals
    // and always points at this running install + JDK. Best-effort and stderr-only — never blocks
    // serving. It writes atomically, so an agent mid-read of the launcher never sees a torn file. For
    // `devrig mcp` we skip the Windows user-PATH registration (it spawns PowerShell and would delay the
    // first serve); that runs on interactive/`install` invocations instead.
    //
    // Gated on [selfHealsLauncherOnStart]: the MCP-as-CLI tool facades are thin, stateless bridge
    // forwarders and must NOT mutate on-disk launcher/PATH state (Tenet 3). The self-heal is a
    // bootstrap/lifecycle concern reserved for `mcp` + the interactive commands.
    if (command.selfHealsLauncherOnStart()) {
        ensureBinLauncher(homePaths, registerWindowsPath = command !is DevrigCommand.MCP)
    }

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
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        // Last-resort crash handler. Under --json still emit a single isError envelope so a machine
        // consumer never gets an empty stdout on an unexpected failure; otherwise log the trace to
        // stderr (stdout stays clean). Never a stack trace on stdout, never a second stdout document.
        if (command.json) {
            Presentation.Json().renderError(
                "devrig", "unexpected error: ${t.message ?: t.javaClass.simpleName}",
                exit = 64, out = mcpStdout,
            )
        } else {
            System.err.println("Unexpected error calling $command. ${t.message}")
            t.printStackTrace(System.err)
            64
        }
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
    is DevrigCommand.DevrigCommandInstall,
    is DevrigCommand.DevrigCommandInstallDevrig,
    is DevrigCommand.DevrigCommandFetchResource,
    is DevrigCommand.DevrigCommandExecuteCode,
    is DevrigCommand.DevrigCommandListProjects,
    is DevrigCommand.DevrigCommandListWindows,
    is DevrigCommand.DevrigCommandOpenProject,
    is DevrigCommand.DevrigCommandScreenshot,
    is DevrigCommand.DevrigCommandInput,
    is DevrigCommand.DevrigCommandFeedback -> true
    is DevrigCommand.DevrigCommandHelp,
    is DevrigCommand.DevrigCommandVersion,
    is DevrigCommand.DevrigCommandParseError -> false
}

/**
 * The MCP-as-CLI tool commands emit data (markdown, JSON, tool output) to stdout that must stay
 * clean for piping, so they never print the human headliner banner — unlike the interactive
 * `project` / `backend` / `install` listings.
 */
private fun DevrigCommand.isMcpAsCliToolCommand(): Boolean = when (this) {
    is DevrigCommand.DevrigCommandFetchResource,
    is DevrigCommand.DevrigCommandExecuteCode,
    is DevrigCommand.DevrigCommandListProjects,
    is DevrigCommand.DevrigCommandListWindows,
    is DevrigCommand.DevrigCommandOpenProject,
    is DevrigCommand.DevrigCommandScreenshot,
    is DevrigCommand.DevrigCommandInput,
    is DevrigCommand.DevrigCommandFeedback -> true
    else -> false
}

private fun DevrigCommand.printsHeadliner(): Boolean =
    runsTool() && this !is DevrigCommand.MCP && !json && !isMcpAsCliToolCommand()

/**
 * Whether this command performs the on-start `~/.mcp-steroid/bin` launcher + PATH self-heal
 * ([ensureBinLauncher]). The self-heal is a bootstrap/lifecycle concern: it runs for the long-lived
 * `mcp` server and the interactive lifecycle commands (backend / project / install / help / version) —
 * but NOT for the thin, stateless MCP-as-CLI tool facades ([isMcpAsCliToolCommand]), which must never
 * mutate on-disk launcher/PATH state just to forward a single bridge call (Tenet 3: devrig is
 * stateless). Pure and side-effect-free so it is unit-testable across every [DevrigCommand] variant.
 */
fun DevrigCommand.selfHealsLauncherOnStart(): Boolean = !isMcpAsCliToolCommand()

suspend fun DevrigServices.mainImplMcp(
    onServerReady: (McpServerCore) -> Unit = {},
) = coroutineScope {
    // devrig boots a real MCP stdio server backed by McpStdioServer and
    // McpSteroidTools. Alongside the stdio server, the IDE monitor runs discovery from
    // <pid>.mcp-steroid JSON markers in the devrig home markers directory
    // plus legacy .<pid>.mcp-steroid markers from $HOME during the transition.
    // The monitor opens one POST <rpcBaseUrl>/projects/stream per IDE and receives
    // push notifications on project open/close.
    runStubStdioMcpServer(this@mainImplMcp, onServerReady = onServerReady)
}
