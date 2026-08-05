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
 * self-healing wrapper that `ensureBinLauncher` (in the devrig CLI) writes on every devrig start. It always sets
 * `DEVRIG_JAVA_HOME` to the JDK devrig runs under, so **no caller needs to deal with JAVA_HOME**. Pointing
 * registrations/docs at this stable path (rather than a content-addressed install tree that changes on
 * every upgrade) is what lets the wrapper repoint underneath without re-registering the agent.
 */
object DevrigUserLauncher {
    /** The user-facing launcher file for [windows]: `~/.mcp-steroid/bin/devrig` or `…/devrig.cmd`. */
    fun path(home: HomePaths, windows: Boolean = isWindows()): Path =
        home.binDir.resolve(devrigLauncherFileName(windows)).toAbsolutePath().normalize()

    /**
     * OS-correct command to run the user launcher with [args]. Windows runs the `.cmd` through
     * `cmd.exe /d /c` (a `.cmd` is not directly executable as a process image, and `/d` skips any
     * AutoRun script); POSIX execs the script directly. **No JAVA_HOME** — the launcher exports
     * `DEVRIG_JAVA_HOME` for the JDK devrig runs under.
     */
    fun invocation(home: HomePaths, args: List<String>, windows: Boolean = isWindows()): StdioMcpCommand =
        devrigStdioMcpCommand(path(home, windows).toString(), windows, args)
}

/**
 * The stable launcher's file name for this OS: a `.cmd` shim on Windows (so cmd.exe and PowerShell resolve
 * it via PATHEXT), a plain `devrig` script on POSIX.
 */
fun devrigLauncherFileName(windows: Boolean): String = if (windows) "devrig.cmd" else "devrig"

/**
 * OS-correct stdio invocation of the stable devrig launcher at [launcherPath], with [args].
 *
 * Lives here, in `:devrig-common` — the module both halves of the product depend on — because two places
 * need the exact same answer: devrig itself when it registers an agent ([DevrigUserLauncher.invocation]),
 * and the IDE plugin when it shows the manual configuration for a client devrig cannot register (Cursor,
 * Windsurf, anything reading an `mcpServers` file). If the two ever built this string separately, the
 * copyable snippet would quietly stop matching what the button writes.
 *
 * Windows runs the `.cmd` through `cmd.exe /d /c` — a `.cmd` is not directly executable as a process image,
 * and `/d` skips any AutoRun script. The launcher path is quoted because `cmd.exe` parses everything after
 * `/c` as ONE command line, so an unquoted `C:\Users\First Last\…` splits and the server never starts.
 * POSIX execs the script directly.
 */
fun devrigStdioMcpCommand(launcherPath: String, windows: Boolean, args: List<String> = listOf("mcp")): StdioMcpCommand =
    if (windows) {
        StdioMcpCommand(
            command = "cmd.exe",
            args = listOf("/d", "/c", (listOf("\"$launcherPath\"") + args).joinToString(" ")),
        )
    } else {
        StdioMcpCommand(command = launcherPath, args = args)
    }

/**
 * devrig's home rendered for humans — see the display policy on [devrigLauncherDisplayPath].
 */
fun devrigHomeDisplayPath(userHome: String, windows: Boolean): String =
    displayPath(userHome, windows, DEVRIG_HOME_DIR_NAME)

/**
 * The stable launcher path rendered for humans.
 *
 * Display policy for every user-visible devrig path: the **real absolute home** (never `~`), joined with
 * the OS-native separator — `C:\Users\me\.mcp-steroid\bin\devrig.cmd` on Windows,
 * `/home/me/.mcp-steroid/bin/devrig` on POSIX. `~` is a lie on Windows (neither cmd.exe nor an
 * `mcp.json`-reading client expands it), and a copy button must put exactly what is displayed on the
 * clipboard — so the display IS the clipboard content, on every OS.
 *
 * A string renderer, not [DevrigUserLauncher.path], on purpose: the display must show Windows
 * separators even when rendered on a POSIX JVM (tests, docs), which `java.nio.file.Path` cannot do.
 */
fun devrigLauncherDisplayPath(userHome: String, windows: Boolean): String =
    displayPath(userHome, windows, DEVRIG_HOME_DIR_NAME, "bin", devrigLauncherFileName(windows))

/**
 * The one-line command that runs devrig as a stdio MCP server — what a user types into an MCP client that
 * asks for a command line instead of reading an `mcpServers` file. The launcher path follows the
 * [devrigLauncherDisplayPath] policy (real absolute home, OS-native separators) and is quoted when it
 * contains a space, because every shell and client splits an unquoted `C:\Users\First Last\…` in two.
 */
fun devrigMcpCommandLine(userHome: String, windows: Boolean): String {
    val launcher = devrigLauncherDisplayPath(userHome, windows)
    val quoted = if (' ' in launcher) "\"$launcher\"" else launcher
    return "$quoted mcp"
}

private fun displayPath(userHome: String, windows: Boolean, vararg segments: String): String {
    val separator = if (windows) "\\" else "/"
    // A Windows home can arrive with forward slashes (a config file, a test) — render it Windows-naturally.
    val home = (if (windows) userHome.replace('/', '\\') else userHome).trimEnd('/', '\\')
    return (listOf(home) + segments).joinToString(separator)
}
