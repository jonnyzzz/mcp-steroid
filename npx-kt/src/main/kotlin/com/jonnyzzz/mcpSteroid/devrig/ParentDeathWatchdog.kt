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
    /** Liveness probe for the recorded parent; null = parent unknown → watchdog stays off (never false-positive). */
    private val parentAlive: (() -> Boolean)?,
    private val onParentDeath: () -> Unit,
    private val pollInterval: Duration = 5.seconds,
    /** Consecutive dead readings required before firing — one transient platform hiccup must not kill the server. */
    private val confirmations: Int = 2,
) {
    fun launchIn(scope: CoroutineScope) {
        val probe = parentAlive ?: run {
            log.info("parent process unknown — parent-death watchdog disabled")
            return
        }
        scope.launch {
            var consecutiveDead = 0
            while (true) {
                delay(pollInterval)
                consecutiveDead = if (probe()) 0 else consecutiveDead + 1
                if (consecutiveDead >= confirmations) {
                    onParentDeath()
                    return@launch
                }
            }
        }
    }

    companion object {
        private val log = logger<ParentDeathWatchdog>()
    }
}

/**
 * Liveness probe for THIS process's launching parent, or null when the platform reports none.
 * The handle is captured once: ProcessHandle identity is pinned to the original process (pid +
 * start time on mainstream platforms), so `isAlive` stays false after death even if the pid is
 * reused by a new process.
 */
fun currentParentLiveness(): (() -> Boolean)? {
    val parent = ProcessHandle.current().parent().orElse(null) ?: return null
    return parent::isAlive
}
