/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bare `devrig install` — overview mode (jonnyzzz/mcp-steroid#277): list every valid install target,
 * tell the user which agent CLIs are actually reachable on PATH, exit 0.
 */
fun DevrigServices.runInstallOverviewCommand(): Int {
    val detected = AiAgentCli.entries.associateWith { findCliOnPath(it.binary) }
    mcpStdout.print(renderInstallOverview(detected))
    return 0
}

fun renderInstallOverview(detected: Map<AiAgentCli, Path?>): String = buildString {
    appendLine("Usage: devrig install <target> [--check]")
    appendLine()
    appendLine("Targets:")
    for (agent in AiAgentCli.entries) {
        val status = detected[agent]?.let { "${agent.binary} CLI found: $it" }
            ?: "${agent.binary} CLI not found on PATH"
        appendLine("  ${agent.binary.padEnd(8)} register devrig as the mcp-steroid MCP server in ${agent.displayName} ($status)")
    }
    appendLine("  ${"plugin".padEnd(8)} install the MCP Steroid plugin into locally-running JetBrains IDEs")
    appendLine("  ${"devrig".padEnd(8)} re-register devrig's own launcher and PATH (used by the install scripts)")
    appendLine()
    appendLine("Example: devrig install claude")
}

/**
 * Resolve [binary] against a PATH-style environment value, mirroring what the OS SHELL would launch.
 * Pure lookup for testability: PATH content and platform behavior arrive as parameters. On Windows a
 * bare name launches `<name>.exe|.cmd|.bat` ([windowsExtensions]); executability is only checked where
 * the OS reports it (Files.isExecutable is always true on Windows).
 *
 * Caveat: shell semantics, not ProcessBuilder semantics. A `.cmd`/`.bat` npm shim found here is real
 * (the user's shell runs it via PATHEXT), but ProcessAiAgentCliRunner spawns the bare name and
 * CreateProcess cannot execute batch files - those need `cmd.exe /d /c` (see DevrigUserLauncher).
 * Routing the runner's batch-shim launches through cmd.exe is tracked in jonnyzzz/mcp-steroid#342.
 */
fun findCliOnPath(
    binary: String,
    pathEnv: String? = System.getenv("PATH"),
    windowsExtensions: Boolean = System.getProperty("os.name").startsWith("Windows"),
): Path? {
    if (pathEnv.isNullOrBlank()) return null
    val names = if (windowsExtensions) listOf(binary, "$binary.exe", "$binary.cmd", "$binary.bat") else listOf(binary)
    return pathEnv.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .asSequence()
        .flatMap { dir -> names.map { Path.of(dir).resolve(it) } }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}
