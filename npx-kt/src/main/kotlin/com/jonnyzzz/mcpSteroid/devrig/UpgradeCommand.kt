/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.PrintStream

/** Origin that serves the published, self-contained install scripts. */
private const val INSTALL_BASE_URL = "https://mcp-steroid.jonnyzzz.com"

/**
 * Runs a fully-specified OS command inheriting this process's stdio, returning its exit code.
 * Abstracted (an injectable seam) so the upgrade wiring is unit-testable without spawning a process.
 */
fun interface UpgradeRunner {
    fun run(command: List<String>): Int
}

/** Production runner: spawn the install script, inheriting stdio (it narrates to stderr, prompts nothing). */
val ProcessUpgradeRunner = UpgradeRunner { command ->
    ProcessBuilder(command).inheritIO().start().waitFor()
}

/**
 * The command that fetches and runs the PUBLISHED install script for [windows]. devrig "updates" by
 * re-running the very same self-contained installer the website serves — which is content-addressed and
 * INCREMENTAL: an unchanged JDK / devrig (same version + sha) is detected on disk and NOT re-downloaded,
 * so an upgrade only fetches the artifacts that actually changed and then repoints `~/.mcp-steroid/bin`.
 *
 * Unlike the human `curl … | sh` one-liner, this PROGRAMMATIC form propagates a fetch failure: a piped
 * `curl -f … | sh` exits 0 even when curl 404s (the trailing `sh` reads empty stdin and runs nothing), so
 * here POSIX downloads to a temp file first and `&&`-chains the run, surfacing curl's exit code. Windows
 * uses `Invoke-RestMethod` (returns the script BODY) into `Invoke-Expression` — the repo's documented
 * idiom; `Invoke-WebRequest` would pipe a response OBJECT and never execute the installer.
 */
internal fun upgradeInvocation(windows: Boolean): List<String> = if (windows) {
    listOf(
        "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
        "Invoke-RestMethod $INSTALL_BASE_URL/install.ps1 | Invoke-Expression",
    )
} else {
    listOf(
        "sh", "-c",
        "t=\$(mktemp) && curl -fsSL $INSTALL_BASE_URL/install.sh -o \"\$t\" && sh \"\$t\"; r=\$?; rm -f \"\$t\"; exit \$r",
    )
}

fun DevrigServices.runUpgradeCommand(
    command: DevrigCommand.DevrigCommandUpgrade,
    runner: UpgradeRunner = ProcessUpgradeRunner,
    windows: Boolean = isWindows(),
): Int = runUpgradeCommand(command, mcpStdout, System.err, runner, windows)

/**
 * Testable core: narrates what it will do, then runs the published install script via [runner] and
 * returns its exit code. No process is spawned here — that is [runner]'s job — so tests inject a fake.
 */
fun runUpgradeCommand(
    command: DevrigCommand.DevrigCommandUpgrade,
    out: PrintStream,
    err: PrintStream,
    runner: UpgradeRunner,
    windows: Boolean,
): Int {
    val invocation = upgradeInvocation(windows)
    if (!command.json) {
        out.println("Upgrading devrig by fetching and running the published install script:")
        out.println("    ${invocation.joinToString(" ")}")
        out.println()
        out.println("The installer is incremental: an unchanged JDK or devrig is reused (only changed")
        out.println("artifacts are downloaded), then your ~/.mcp-steroid/bin launcher is repointed.")
        out.println()
    }
    val exit = runner.run(invocation)
    if (exit != 0) {
        err.println("devrig upgrade FAILED — the install script exited with code $exit.")
    } else if (!command.json) {
        out.println("devrig upgrade complete.")
    }
    return exit
}
