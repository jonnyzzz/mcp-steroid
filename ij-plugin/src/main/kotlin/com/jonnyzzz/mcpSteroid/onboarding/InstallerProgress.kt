/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

/**
 * One recognised step of the canonical installer, derived from a single output line.
 *
 * @param text what to show the user in the progress indicator.
 * @param totalBytes the size of the artifact this step downloads, when the line carries it — the
 *        denominator for the download fraction. Null for steps that download nothing.
 * @param isError true for the installer's own `ERROR:` line, so the caller can surface the reason
 *        instead of a generic exit-code message.
 */
data class InstallerStep(
    val text: String,
    val totalBytes: Long? = null,
    val isError: Boolean = false,
)

/**
 * Every line the installer prints carries this prefix (`log()` in `install.sh.tmpl` /
 * `Write-Log` in `install.ps1.tmpl`), on **stderr**. Lines without it are not ours (curl's progress
 * bar, shell noise) and are ignored.
 */
private const val INSTALLER_PREFIX = "[mcp-steroid] "

private val DOWNLOADING = Regex("""^downloading (\S+) \(~(\d+) MB\)""")
private val RETRY = Regex("""^attempt (\d+)/(\d+) failed""")
private val PLATFORM = Regex("""^platform: (\S+)""")

/**
 * Map one installer output line to a progress step, or null when the line is not a step we report.
 *
 * Only the lines that tell the user something actionable are mapped; the installer's help text (missing
 * tools, "to register devrig with your agents…") is deliberately ignored here — the caller reports
 * failures from the exit code plus the `ERROR:` line.
 */
fun parseInstallerLine(rawLine: String): InstallerStep? {
    val line = rawLine.trim()
    if (!line.startsWith(INSTALLER_PREFIX)) return null
    val body = line.removePrefix(INSTALLER_PREFIX).trim()
    if (body.isEmpty()) return null

    if (body.startsWith("ERROR: ")) {
        return InstallerStep(body.removePrefix("ERROR: ").trim(), isError = true)
    }

    DOWNLOADING.find(body)?.let { m ->
        val kind = m.groupValues[1]
        val megabytes = m.groupValues[2].toLongOrNull()
        return InstallerStep(
            text = "Downloading $kind" + (megabytes?.let { " (~$it MB)" } ?: "") + "…",
            totalBytes = megabytes?.let { it * 1024 * 1024 },
        )
    }
    RETRY.find(body)?.let { m ->
        return InstallerStep("Download attempt ${m.groupValues[1]}/${m.groupValues[2]} failed — retrying…")
    }
    PLATFORM.find(body)?.let { m ->
        return InstallerStep("Detected platform ${m.groupValues[1]}…")
    }
    return when {
        body.startsWith("SHA-256 verified") -> InstallerStep("Verifying the download…")
        body.startsWith("already installed:") -> InstallerStep("Already downloaded — reusing it…")
        body.startsWith("another install finished first") -> InstallerStep("Another install finished first — reusing it…")
        body.startsWith("registering devrig") -> InstallerStep("Registering devrig…")
        body.startsWith("devrig binary is ready") -> InstallerStep("devrig is installed.")
        else -> null
    }
}
