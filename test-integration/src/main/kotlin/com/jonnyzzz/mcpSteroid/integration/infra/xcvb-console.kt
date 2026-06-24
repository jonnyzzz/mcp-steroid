/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.runInContainerDetached
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import java.util.concurrent.atomic.AtomicInteger


class PumpHandle(
    private val process: RunningContainerProcess,
) {
    fun stop() {
        try {
            // Give pump a moment to flush remaining lines
            Thread.sleep(300)
            process.kill()
        } catch (_: Exception) {
            // pump cleanup is best-effort
        }
    }
}

/**
 * Provides a visible console window (xterm) on the right 1/3 of the screen
 * that displays test status messages in real-time.
 *
 * Uses `tail -f` to follow a log file; text is sent via a Kotlin [Channel] to a
 * single long-running `docker exec -i` process that reads from stdin and appends to the file.
 * This avoids a separate docker exec round-trip per [writeLine] call.
 *
 * ANSI escape codes are supported natively by xterm.
 *
 * Immutable: created via [create] factory, cleanup registered in [CloseableStack].
 */
class ConsoleDriver(
    private val container: ContainerDriver,
    private val consoleFile: String,
    private val lineChannel: Channel<ByteArray>,
) {

    fun writeLine(text: String) {
        println(text)
        // Channel.UNLIMITED — trySend never fails due to capacity
        lineChannel.trySend((text + "\n").toByteArray())
    }

    // -- ANSI formatting helpers --
    fun writeHeader(text: String) {
        writeLine("")
        writeLine("$BOLD$CYAN${"═".repeat(40)}$RESET")
        writeLine("$BOLD$CYAN  $text$RESET")
        writeLine("$BOLD$CYAN${"═".repeat(40)}$RESET")
        writeLine("")
    }

    private val stepNumber = AtomicInteger(1)

    fun writeStep(text: String) {
        val step = stepNumber.incrementAndGet()
        writeLine("$BOLD$YELLOW[$step]$RESET $text")
    }

    fun writeSuccess(text: String) {
        writeLine(" ${GREEN}OK$RESET $text")
    }

    fun writeError(text: String) {
        writeLine("${RED}FAIL$RESET $text")
    }

    fun writeInfo(text: String) {
        writeLine("$BLUE>>>$RESET $text")
    }

    fun <T> writeProgressBlock(test: String, action: () -> T) : T {
        writeInfo(test)
        return action()
    }

    fun writePrompt(label: String, prompt: String) {
        writeLine("")
        writeLine("$BOLD$BRIGHT_WHITE--- $label ---$RESET")
        prompt.lineSequence().forEach { line ->
            writeLine("$BRIGHT_WHITE$line$RESET")
        }
        writeLine("$BOLD$BRIGHT_WHITE--- end ---$RESET")
        writeLine("")
    }

    /**
     * Start a background process in the container that pumps lines from [filePath]
     * to the console file, prefixing each line with a colored [prefix].
     *
     * Uses `tail -F` (follows by name, handles truncation/replacement) and `awk`
     * with explicit `fflush()` for immediate, unbuffered line-by-line display.
     * The pump script is written to a file in the container to avoid shell escaping
     * issues with variable expansion.
     *
     * Returns a [PumpHandle] to stop the pump when the agent finishes.
     */
    fun startFilePump(
        filePath: String,
        prefix: String,
        prefixColor: String = CYAN,
    ): PumpHandle {
        val scriptPath = "/tmp/pump-${System.nanoTime()}.sh"
        val awkPrefix = prefix.replace("\\", "\\\\").replace("\"", "\\\"")
        // Map ANSI color constant to SGR code number for awk printf
        val colorCode = when (prefixColor) {
            RED -> "31"
            GREEN -> "32"
            YELLOW -> "33"
            BLUE -> "34"
            CYAN -> "36"
            else -> "36"
        }
        val script = buildString {
            appendLine("#!/bin/bash")
            appendLine("touch $filePath")
            // tail -F follows by name (handles truncation/replacement)
            // -s 0.1 polls every 100ms instead of default 1s for smoother updates
            // awk flushes both stdout and the console file after each line
            appendLine("tail -F -s 0.1 $filePath 2>/dev/null | awk '{printf \"\\033[${colorCode}m${awkPrefix}\\033[0m %s\\n\", \$0 >> \"$consoleFile\"; fflush(\"$consoleFile\")}'")
        }
        container.writeFileInContainer(scriptPath, script, executable = true)

        val proc = container.runInContainerDetached(
            listOf("bash", scriptPath),
        )
        return PumpHandle(proc)
    }

    companion object {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val RED = "\u001b[31m"
        const val GREEN = "\u001b[32m"
        const val YELLOW = "\u001b[33m"
        const val BLUE = "\u001b[34m"
        const val CYAN = "\u001b[36m"
        const val BRIGHT_WHITE = "\u001b[97m"

    }
}


class XcvbConsoleDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val windowDriver: XcvbWindowDriver,
) {
    private val consoleCounter = AtomicInteger(0)

    fun createConsoleDriver(
        container: ContainerDriver,
        title: String,
        layoutRect: WindowRect,
    ): ConsoleDriver {
        val consoleWorkDir = "/tmp"
        val consoleFile = "$consoleWorkDir/test-console-${consoleCounter.incrementAndGet()}"
        // Seed the console file with a CONTAINER-SIDE write so it is owned by the
        // container user (the same `agent` user that runs the `cat >> consoleFile`
        // writer below). writeFileInContainer now stages through `docker cp`, which
        // leaves the file owned by the host uid — `cat >>` running as `agent` then
        // gets permission-denied and nothing past the seed ever appears in the
        // console. Writing the seed via `docker exec` keeps ownership consistent.
        container.startProcessInContainer {
            this
                .args("bash", "-c", "printf 'Thinking...\\n' > $consoleFile")
                .description("seed console file $consoleFile")
                .timeoutSeconds(5)
                .quietly()
        }.assertExitCode(0) { "Failed to seed console file $consoleFile: $stderr" }

        val xtermArgs = mutableListOf(
            "xterm",
            "-u8",
            "-title", title,
            "-geometry", "80x30+${layoutRect.x}+${layoutRect.y}",
            "-fa", "JetBrains Mono:style=Regular",
            "-fs", "16",
            "-bg", "black",
            "-fg", "white",
            "-e",

            "tail", "-f", "-s", "0.1", consoleFile
        )

        println("[xcvb] Starting visible console '$title'")

        val consoleProcess = driver.runInContainerDetached(
            xtermArgs,
            workingDir = consoleWorkDir,
        )

        lifetime.registerCleanupAction {
            consoleProcess.kill()
        }

        var lastLogMs = 0L
        var lastWindowsSnapshot = emptyList<WindowInfo>()
        val consoleWindowId = try {
            waitForValue(30_000, "console window '$title'") {
                val windows = windowDriver.listWindows()
                lastWindowsSnapshot = windows
                val byPid = windows.firstOrNull { it.pid != null && it.pid == consoleProcess.pid }
                if (byPid != null) return@waitForValue byPid
                // Fallback: match by title (WM_NAME), since some xterm builds/configs
                // do not expose _NET_WM_PID via xdotool getwindowpid.
                val byTitle = windows.firstOrNull { it.title.contains(title) }
                if (byTitle != null) {
                    val xtermPid = try { consoleProcess.pid } catch (_: Exception) { -1L }
                    println("[xcvb] Console window matched by title '$title' (pid-file=$xtermPid, window.pid=${byTitle.pid})")
                    return@waitForValue byTitle
                }
                // Diagnostic: log every ~5s to diagnose why neither PID nor title matched
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLogMs > 5_000) {
                    lastLogMs = nowMs
                    val xtermPid = try { consoleProcess.pid } catch (_: Exception) { -1L }
                    println("[xcvb-debug] Waiting for xterm window (pid-file=$xtermPid title='$title'). Found ${windows.size} windows:")
                    windows.forEach { w -> println("[xcvb-debug]   id=${w.id} pid=${w.pid} title='${w.title}'") }
                }
                null
            }
        } catch (t: RuntimeException) {
            val windowsDump = lastWindowsSnapshot.joinToString(separator = "\n") { w ->
                "  id=${w.id} pid=${w.pid} title='${w.title}'"
            }
            throw RuntimeException(
                buildString {
                    append("Failed waiting for console window '$title'.")
                    if (windowsDump.isNotEmpty()) {
                        appendLine()
                        append("Last visible windows:")
                        appendLine()
                        append(windowsDump)
                    }
                },
                t,
            )
        }

        windowDriver.updateLayout(consoleWindowId, layoutRect)

        // Start a single long-running process that reads from stdin (line-by-line) and appends
        // to the console file. This replaces per-writeLine docker exec round-trips with a single
        // persistent connection driven by a Kotlin Channel.
        val lineChannel = Channel<ByteArray>(Channel.UNLIMITED)
        val writerProcess = container.startProcessInContainer {
            this
                .args("bash", "-c", "cat >> $consoleFile")
                .interactive()
                .stdin(lineChannel.consumeAsFlow())
                .quietly()
                .description("console writer for $consoleFile")
        }
        lifetime.registerCleanupAction {
            lineChannel.close()
            writerProcess.destroyForcibly()
        }

        val driver = ConsoleDriver(container, consoleFile, lineChannel)
        driver.writeHeader(title)
        return driver
    }
}
