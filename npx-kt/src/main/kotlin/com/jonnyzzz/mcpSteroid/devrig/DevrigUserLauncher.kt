/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import java.nio.file.Path

/**
 * Single source of truth for the user-facing devrig launcher under `~/.mcp-steroid/bin` and how to
 * INVOKE it from another process (agent MCP registration, prompts/docs, anywhere a command line is
 * built).
 *
 * The launcher — POSIX `devrig`; Windows `devrig.cmd` (a self-contained batch, no PowerShell) — is the
 * self-healing wrapper that [ensureBinLauncher] writes on every devrig start. It always sets
 * `DEVRIG_JAVA_HOME` to the JDK devrig runs under, so **no caller needs to deal with JAVA_HOME**. Pointing
 * registrations/docs at this stable path (rather than a content-addressed install tree that changes on
 * every upgrade) is what lets the wrapper repoint underneath without re-registering the agent.
 */
object DevrigUserLauncher {
    /** The user-facing launcher file for [windows]: `~/.mcp-steroid/bin/devrig` or `…/devrig.cmd`. */
    fun path(home: HomePaths, windows: Boolean = isWindows()): Path =
        home.binDir.resolve(if (windows) "devrig.cmd" else "devrig").toAbsolutePath().normalize()

    /**
     * OS-correct command to run the user launcher with [args]. Windows runs the `.cmd` through
     * `cmd.exe /d /c` (a `.cmd` is not directly executable as a process image, and `/d` skips any
     * AutoRun script); POSIX execs the script directly. **No JAVA_HOME** — the launcher exports
     * `DEVRIG_JAVA_HOME` for the JDK devrig runs under.
     */
    fun invocation(home: HomePaths, args: List<String>, windows: Boolean = isWindows()): StdioMcpCommand {
        val launcher = path(home, windows).toString()
        return if (windows) {
            // cmd.exe parses everything after `/c` as ONE command line, so the launcher path must be
            // quoted or a `%USERPROFILE%` with a space (e.g. C:\Users\First Last) splits and the server
            // never starts.
            val line = (listOf("\"$launcher\"") + args).joinToString(" ")
            StdioMcpCommand(command = "cmd.exe", args = listOf("/d", "/c", line))
        } else {
            StdioMcpCommand(command = launcher, args = args)
        }
    }
}
