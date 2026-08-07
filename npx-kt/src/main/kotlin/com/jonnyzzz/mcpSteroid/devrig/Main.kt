/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.github.ajalt.mordant.terminal.Terminal
import com.jonnyzzz.mcpSteroid.devrig.server.runStubStdioMcpServer
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.PrintStream
import kotlinx.coroutines.CancellationException
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
    exitProcess(runDevrigMain(rawArgs))
}

fun runDevrigMain(
    rawArgs: Array<String>,
    terminal: Terminal = Terminal(),
    parseCommand: (Array<String>, Terminal) -> DevrigCliInvocation = ::parseDevrigCommand,
): Int {
    // FIRST, before anything can reach SLF4J: logback pins every logback.xml substitution at its first
    // getLogger call, and command-tree construction below makes that call (a tool handler holds a logger
    // field). Publishing these after parsing published nothing at all — see #462.
    configureLoggingSystemProperties(rawArgs)

    // Construct the terminal while stdout still points at the user's destination. Parsing happens
    // after stdout is guarded for MCP purity, but color auto-detection must follow stdout, not stderr.
    // Replace stdout immediately. MCP stdio reserves the original stdout for
    // frames, and command detection / service setup must not leak there.
    val mcpStdin = System.`in`
    val mcpStdout = System.out
    System.setOut(System.err)

    return try {
        // Keep command-tree construction inside the SOFTWARE=70 boundary. Schema/type/alias invariants
        // fail while parsing, before a command exists, and must not escape main as JVM exit 1
        // (CliExit.TOOL_ERROR is reserved for a backend result with isError=true).
        val command = parseCommand(rawArgs, terminal)
        val headliner = buildHeadliner()
        if (command.mode.isMcp) {
            System.err.println(headliner)
        } else if (!command.keepsSystemOutGuarded) {
            System.setOut(mcpStdout)
        }

        val homePaths = resolveHomePathsOrDie()

        // Logging itself was configured at the top of this function; this only records the startup line,
        // which needs a validated home.
        configureLoggingAndLogStarted(homePaths, rawArgs.toList())

        val lifetime = CloseableStackHost()
        try {
            DevrigServices(
                lifetime = lifetime,
                homePaths = homePaths,
                mcpStdin = mcpStdin,
                mcpStdout = mcpStdout,
            ).mainImpl1(command, headliner)
        } finally {
            lifetime.closeAllStacks()
        }
    } catch (t: Throwable) {
        System.err.println("Unexpected error ${t.message}")
        t.printStackTrace(System.err)
        CliExit.SOFTWARE
    } finally {
        System.setOut(mcpStdout)
    }
}

private fun buildHeadliner(): String = buildString {
    val devrigVersion = DevrigVersionMetadata.getDevrigVersion()
    appendLine("devrig v$devrigVersion - This environment empowers your AI with the best deterministic coding tools.")
    appendLine()
}

fun DevrigServices.mainImpl1(
    command: DevrigCliInvocation,
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
    command: DevrigCliInvocation,
    headliner: String,
): Int = coroutineScope {
    // Generated MCP-as-CLI facades are stateless bridge calls and must not mutate launcher/PATH state.
    // Lifecycle commands keep the existing self-heal behavior.
    if (command.selfHealsLauncherOnStart) {
        ensureBinLauncher(homePaths)
    }

    // For the MCP command, the running McpServerCore becomes available once the
    // stdio server is built; the update check broadcasts its notice over it (in
    // addition to stderr) as a `notifications/message`. For non-MCP commands the
    // deferred is never completed, so the notice falls back to stderr only.
    val mcpServerReady = CompletableDeferred<McpServerCore>()

    if (command.mode.runsTool) {
        backgroundScope.launch {
            delay(Random.nextInt(200, 1300).milliseconds)
            val onNotice: (String) -> Unit = { message ->
                if (command.mode.isMcp) {
                    backgroundScope.launch {
                        val core = mcpServerReady.await()
                        core.broadcastLogMessage("warning", "devrig.updates", JsonPrimitive(message))
                    }
                }
            }
            // One flow for every command (docs/updates-check/devrig-auto-update.md): the first
            // check runs right after the short startup delay above; MCP sessions then keep
            // re-checking/retrying every 3–8 h, everything else gets the passive notice once.
            runAutoUpdateFlow(
                homePaths = homePaths,
                mcpSession = command.mode.isMcp,
                notify = onNotice,
                onUpdateEvent = { phase, promoted, exitCode ->
                    val properties = LinkedHashMap<String, Any>()
                    properties["target_version"] = promoted
                    if (exitCode != null) properties["exit_code"] = exitCode
                    beacon.capture("self_update_$phase", properties)
                },
            )
        }

        backgroundScope.launch {
            beacon.captureStarted(command.telemetryMode)
        }
    }

    if (command.mode.isMcp) {
        // Orphan back-stop (#132): stdin EOF only reaps a parent that CLOSES the pipe; a SIGKILL'd
        // agent leaves this JVM alive forever. exitProcess (not scope cancellation) because the read
        // loop is parked in a blocking stream read that cancellation cannot interrupt.
        ParentDeathWatchdog(
            ancestorsAlive = watchedAncestorLiveness(),
            onParentDeath = {
                val message = "parent process died without closing stdin — exiting orphaned 'devrig mcp'"
                System.err.println("[mcp-steroid] $message")
                logger<ParentDeathWatchdog>().warn(message)
                exitProcess(0)
            },
        ).launchIn(backgroundScope)
        beacon.runHeartbeat()
        try {
            mainImplMcp(onServerReady = { mcpServerReady.complete(it) })
            return@coroutineScope 0
        } catch (t: Throwable) {
            System.err.println("Unexpected error ${t.message}")
            t.printStackTrace(System.err)
            logger<DevrigLastResortCrashHandler>().error("Unexpected error serving 'devrig mcp'. ${t.message}", t)
            return@coroutineScope CliExit.SOFTWARE
        }
    }

    if (command.printsHeadliner) {
        mcpStdout.println(command.renderHeadliner(headliner))
    }
    runCliWithLastResortHandling(command, mcpStdout) { command.execute(this@mainImpl2) }
}

private class DevrigLastResortCrashHandler

/**
 * Converts user-facing command failures and unexpected runtime faults into the stable CLI exit contract.
 * Parsing is already complete here, so JSON tool commands can preserve their single-document envelope
 * while every stack trace remains on stderr.
 */
suspend fun runCliWithLastResortHandling(
    command: DevrigCliInvocation,
    mcpStdout: PrintStream,
    block: suspend () -> Int,
): Int = try {
    block()
} catch (c: CancellationException) {
    throw c
} catch (e: CliUserFacingException) {
    System.err.println(e.message)
    logger<DevrigLastResortCrashHandler>().info("Command ${command.commandPath} failed: ${e.message}", e)
    e.exit
} catch (t: Throwable) {
    System.err.println("Unexpected error calling ${command.commandPath}. ${t.message}")
    t.printStackTrace(System.err)
    logger<DevrigLastResortCrashHandler>().error(
        "Unexpected error calling ${command.commandPath}. ${t.message}",
        t,
    )
    if (command.json) {
        Presentation.Json().renderError(
            command = command.jsonEnvelopeCommand,
            message = "unexpected error: ${t.message ?: t.javaClass.simpleName}",
            exit = CliExit.SOFTWARE,
            out = mcpStdout,
        )
    } else {
        CliExit.SOFTWARE
    }
}

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
