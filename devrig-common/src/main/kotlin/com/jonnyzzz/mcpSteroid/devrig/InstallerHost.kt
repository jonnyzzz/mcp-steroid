/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Path
import kotlin.io.path.exists

/** The published installers. Both halves of the product install devrig by running exactly these. */
const val DEVRIG_INSTALL_SH_URL = "https://devrig.dev/install.sh"
const val DEVRIG_INSTALL_PS1_URL = "https://devrig.dev/install.ps1"

/** The installer to fetch for this OS. */
fun devrigInstallerUrl(isWin: Boolean): String = if (isWin) DEVRIG_INSTALL_PS1_URL else DEVRIG_INSTALL_SH_URL

/** Most reliable first: absolute System32 PowerShell (agents often carry stripped PATHs), then PATH lookups. */
fun windowsInstallerHostCandidates(systemRoot: String? = System.getenv("SystemRoot")): List<String> {
    val root = systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
    val system32 = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    return buildList {
        if (system32.exists()) add(system32.toString())
        add("powershell")
        add("pwsh")
    }
}

/**
 * Command lines that run an already-downloaded install script, most reliable host first — try each in
 * order until one starts.
 *
 * Downloading first and running a file, rather than piping `curl … | sh` / `irm … | iex`, is what makes
 * the fallback list possible at all: with a pipeline there is one shell and it has to be on PATH. It also
 * drops the dependency on `curl` being installed.
 */
fun installerCommands(script: Path, isWin: Boolean): List<List<String>> = when {
    isWin -> windowsInstallerHostCandidates().map {
        listOf(it, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
    }
    else -> listOf(listOf("/bin/sh", script.toString()))
}
