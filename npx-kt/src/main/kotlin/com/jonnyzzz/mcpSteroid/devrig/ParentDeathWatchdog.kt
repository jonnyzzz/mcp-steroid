/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Parent-death watchdog for `devrig mcp` (jonnyzzz/mcp-steroid#132).
 *
 * The stdio server's read loop exits on stdin EOF, which covers a parent that CLOSES the pipe.
 * A parent that dies WITHOUT closing it (a SIGKILL'd claude/codex session, `taskkill /F`) leaves
 * this JVM orphaned forever — observed piling up at ~250 MB each for days. ProcessHandle liveness
 * polling is the one JDK-portable mechanism across macOS/Linux/Windows (POSIX reparenting checks
 * and Windows Job Objects are platform-specific), so it complements EOF as the orphan back-stop.
 */
class ParentDeathWatchdog(
    /**
     * Liveness probes for the watched ancestors (see [watchedAncestorLiveness]); the process is
     * orphaned when ANY of them dies. Empty = ancestry unknown → watchdog stays off (never
     * false-positive).
     */
    private val ancestorsAlive: List<() -> Boolean>,
    private val onParentDeath: () -> Unit,
    private val pollInterval: Duration = 5.seconds,
    /** Consecutive dead readings required before firing — one transient platform hiccup must not kill the server. */
    private val confirmations: Int = 2,
) {
    private val log = logger<ParentDeathWatchdog>()

    fun launchIn(scope: CoroutineScope) {
        if (ancestorsAlive.isEmpty()) {
            log.info("parent process unknown — parent-death watchdog disabled")
            return
        }
        scope.launch {
            var consecutiveDead = 0
            while (true) {
                delay(pollInterval)
                consecutiveDead = if (ancestorsAlive.all { it() }) 0 else consecutiveDead + 1
                if (consecutiveDead >= confirmations) {
                    onParentDeath()
                    return@launch
                }
            }
        }
    }

}

/**
 * Liveness probes for THIS process's watched ancestors; empty when the platform reports none.
 *
 * Always watches the immediate parent. On Windows the `~\.mcp-steroid\bin\devrig.cmd` launcher
 * cannot `exec` like its POSIX sibling, so a live `cmd.exe` wrapper sits between the agent and
 * this JVM — and `taskkill /F` on the agent leaves that wrapper alive, waiting on us. When the
 * parent is a `cmd.exe` wrapper the GRANDPARENT (the agent) is therefore watched too; either one
 * dying means this server is orphaned.
 *
 * Handles are captured once: ProcessHandle identity is pinned to the original process (pid +
 * start time on mainstream platforms), so `isAlive` stays false after death even if the pid is
 * reused by a new process.
 */
fun watchedAncestorLiveness(): List<() -> Boolean> {
    val parent = ProcessHandle.current().parent().orElse(null) ?: return emptyList()
    val probes = mutableListOf<() -> Boolean>(parent::isAlive)
    if (isCmdExe(parent.info().command().orElse(""))) {
        parent.parent().orElse(null)?.let { agent -> probes += agent::isAlive }
    }
    return probes
}

/** True when [command] is a `cmd.exe` path — the Windows batch-launcher wrapper shape. */
fun isCmdExe(command: String): Boolean =
    command.substringAfterLast('\\').substringAfterLast('/').equals("cmd.exe", ignoreCase = true)
