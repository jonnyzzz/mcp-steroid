/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.logger
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

const val DEVRIG_INSTALL_SH_URL = "https://devrig.dev/install.sh"
const val DEVRIG_INSTALL_PS1_URL = "https://devrig.dev/install.ps1"

/**
 * The active auto-updater — one `tick()` per docs/updates-check/devrig-auto-update.md's
 * "The update loop". Runs ONLY inside `devrig mcp` sessions (a long-lived parent that can supervise
 * the installer and deliver the restart notice over MCP); every collaborator is injected so the whole
 * decision tree is unit-testable without HTTP, processes, or a real `~/.mcp-steroid`.
 */
class AutoUpdater(
    val homePaths: HomePaths,
    val currentVersion: DevrigVersion = DevrigVersionMetadata.getBuildVersion(),
    val isWin: Boolean = isWindows(),
    val coordination: UpdateCoordination = UpdateCoordination(homePaths.updateDir),
    val notify: (String) -> Unit = { },
    val fetchPromoted: suspend () -> DevrigVersion? = { fetchVersionInfo()?.let { DevrigVersion.parse(it.versionBase) } },
    val downloadScript: suspend (url: String, target: Path) -> Boolean = ::downloadInstallScript,
    val launcherVersion: () -> DevrigVersion? = {
        readLauncherVersion(DevrigUserLauncher.path(homePaths, windows = isWindows()))?.let { DevrigVersion.parse(it) }
    },
    val selfHealLauncher: () -> Unit = { ensureBinLauncher(homePaths, registerWindowsPath = false) },
    /** Spawn + supervise the installer; exit code, or null when the 30 min timeout killed the tree. */
    val runInstaller: suspend (script: Path, logFile: Path) -> Int? = { script, logFile ->
        superviseInstallerProcess(script, logFile, isWindows())
    },
    val noAutoUpdateEnv: String? = System.getenv(ENV_DEVRIG_NO_AUTO_UPDATE),
    val binRegisterOptOutEnv: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER),
    /**
     * Fired exactly once per actually-triggered update — after the lock is held and the skew guard
     * passed, right before the installer spawns — with the raw promoted version from version.json.
     * Main wires this to the beacon (`devrig_self_update` with `target_version`).
     */
    val onUpdateTriggered: (promotedVersion: String) -> Unit = { },
) {
    private var restartNotified = false
    private var manualNotified = false

    /**
     * The step-1 gate. A SNAPSHOT build must skip the WHOLE tick including GC (it compares newer than
     * every promoted version, so the GC would delete every `updated-*` marker — the restart-pending
     * signal). The launcher-write opt-out also disables the updater: with launcher writes disabled,
     * every install attempt would fail its verify step by construction, burning capped attempts.
     */
    fun isActive(): Boolean = !currentVersion.isSnapshotBuild &&
        !parseUpdateEnvFlag(noAutoUpdateEnv) &&
        !parseUpdateEnvFlag(binRegisterOptOutEnv)

    suspend fun tick() {
        if (!isActive()) return

        // step 2 — GC
        coordination.gc(currentVersion, homePaths.logsDir)

        // step 3 — restart pending? (local; never trust the marker over the launcher)
        val launcherV = launcherVersion()?.let { baseVersion(it.value) }
        val newestUpdated = coordination.newestUpdatedVersion()
        if (newestUpdated != null && newestUpdated > baseVersion(currentVersion.value)) {
            if (launcherV != null && launcherV >= newestUpdated) {
                notifyRestartOnce(newestUpdated.value)
                return
            }
            // torn: a flip-back landed after the install — repair by re-running the installer below
            System.err.println(
                "[mcp-steroid] updated-$newestUpdated is torn (launcher is at ${launcherV ?: "unknown"}); re-running the installer",
            )
            coordination.deleteUpdatedMarker(newestUpdated.value)
        } else if (launcherV != null && launcherV < baseVersion(currentVersion.value)) {
            // a flip-back landed while only newer sessions were running — self-heal on the spot
            selfHealLauncher()
        }

        // step 4 — fetch; never downgrade
        val promoted = fetchPromoted() ?: return
        if (!DevrigVersion.isUpdateAvailable(current = currentVersion, promoted = promoted)) return
        val target = baseVersionString(promoted.value)

        // step 5 — caps
        if (coordination.isFailureCapped(target)) {
            notifyManualOnce(promoted.value, "auto-update attempts for $target failed (see ${homePaths.logsDir}/update-*-$target.log)")
            return
        }
        if (coordination.isSkewCapped(target)) {
            val skew = coordination.readSkew(target)
            notifyManualOnce(
                promoted.value,
                "the install script still serves ${skew?.parsedVersion ?: "an unknown version"}, expected $target",
            )
            return
        }

        // step 6 — the lock (in-flight elsewhere → stop silently: no notification during an install)
        val scriptUrl = if (isWin) DEVRIG_INSTALL_PS1_URL else DEVRIG_INSTALL_SH_URL
        val logFile = logFileFor(target)
        val info = UpdateStateInfo(
            pid = coordination.ownPid,
            hostname = coordination.hostname,
            currentVersion = currentVersion.value,
            targetVersion = target,
            startedAt = coordination.clock(),
            logFile = logFile.toString(),
            scriptUrl = scriptUrl,
        )
        if (!coordination.tryAcquireLock(info)) return
        try {
            // re-verify steps 3–5 under the lock (the scan-then-lock TOCTOU)
            val nowUpdated = coordination.newestUpdatedVersion()
            if (nowUpdated != null && nowUpdated >= baseVersion(target)) return
            if (coordination.isFailureCapped(target) || coordination.isSkewCapped(target)) return

            coordination.writeInProgressMarker(target, info)
            val script = coordination.scriptFile(isWin)
            try {
                runLockedUpdate(script, scriptUrl, logFile, target, promotedRaw = promoted.value)
            } finally {
                coordination.deleteInProgressMarker(target)
                try {
                    Files.deleteIfExists(script)
                } catch (e: Exception) {
                    System.err.println("[mcp-steroid] could not delete the downloaded install script $script: $e")
                }
            }
        } finally {
            coordination.releaseLock()
        }
    }

    private suspend fun runLockedUpdate(script: Path, scriptUrl: String, logFile: Path, target: String, promotedRaw: String) {
        // step 7 — download + skew guard
        if (!downloadScript(scriptUrl, script)) {
            coordination.recordFailure(target, exitCode = null)
            System.err.println("[mcp-steroid] could not download $scriptUrl; will retry later")
            return
        }
        val scriptContent = try {
            Files.readString(script)
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not read the downloaded install script $script: $e")
            coordination.recordFailure(target, exitCode = null)
            return
        }
        val baked = parseInstallScriptVersion(scriptContent, isWin)
        when {
            baked == null -> {
                // template drift/corruption must surface via the cap, not loop silently forever
                coordination.recordFailure(target, exitCode = null)
                System.err.println("[mcp-steroid] could not find the baked VERSION in $scriptUrl — unexpected script format")
                return
            }
            baseVersion(baked).compareTo(baseVersion(target)) != 0 -> {
                // CDN mid-release propagation: quiet, bounded retry. Ordering-based comparison, not
                // string equality — `0.102` and `0.102.0` are the same release.
                coordination.recordSkew(target, parsedVersion = baked)
                return
            }
        }

        // steps 8–9 — run detached under DEVRIG_AUTO_UPDATE=1, supervise with the tree-kill timeout.
        // The self-update is now actually triggered — surface it to telemetry (best-effort) with the
        // version.json version as the parameter.
        try {
            onUpdateTriggered(promotedRaw)
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] self-update trigger telemetry failed: $e")
        }
        val exit = try {
            runInstaller(script, logFile)
        } catch (e: Exception) {
            // spawn failure (missing shell, IO error) follows the same path as a non-zero exit
            System.err.println("[mcp-steroid] could not start the installer for $target: $e")
            coordination.recordFailure(target, exitCode = null)
            return
        }

        // steps 10–11
        if (exit == 0) {
            if (coordination.readUpdatedMarker(target) == null) {
                // exit-0-without-marker is anomalous; attest the fallback marker ONLY when the launcher
                // stamp confirms the install actually landed — otherwise this is a failed attempt.
                val landed = launcherVersion()?.let { baseVersion(it.value) >= baseVersion(target) } == true
                if (landed) {
                    coordination.writeUpdatedMarker(
                        target,
                        UpdateStateInfo(
                            pid = coordination.ownPid,
                            hostname = coordination.hostname,
                            currentVersion = currentVersion.value,
                            targetVersion = target,
                            startedAt = coordination.clock(),
                            completedAt = coordination.clock(),
                            logFile = logFile.toString(),
                            scriptUrl = scriptUrl,
                        ),
                    )
                } else {
                    coordination.recordFailure(target, exitCode = 0)
                    System.err.println(
                        "[mcp-steroid] installer for $target exited 0 but neither the completion marker " +
                            "nor the launcher confirm it — treating as failed (log: $logFile)",
                    )
                    return
                }
            }
            coordination.clearFailure(target)
            coordination.clearSkew(target)
            notifyRestartOnce(target)
        } else {
            coordination.recordFailure(target, exitCode = exit)
            System.err.println(
                "[mcp-steroid] devrig auto-update to $target failed " +
                    (if (exit == null) "(timed out after $INSTALLER_TIMEOUT_MINUTES min; installer tree killed)" else "(exit $exit)") +
                    "; log: $logFile",
            )
        }
    }

    fun logFileFor(target: String): Path = homePaths.logsDir.resolve("update-${coordination.ownPid}-$target.log")

    private fun notifyRestartOnce(version: String) {
        if (restartNotified) return
        restartNotified = true
        val message = buildString {
            appendLine()
            appendLine("devrig ${baseVersionString(version)} is installed — restart your agent session to use it (current: $currentVersion).")
            appendLine()
        }
        System.err.println(message)
        notify(message)
    }

    private fun notifyManualOnce(promoted: String, reason: String) {
        if (manualNotified) return
        manualNotified = true
        val message = buildString {
            appendLine()
            appendLine("A new version of devrig is available: $promoted (current: $currentVersion), but $reason.")
            appendLine("Update manually: https://devrig.dev/releases/")
            appendLine()
        }
        System.err.println(message)
        notify(message)
    }
}

const val INSTALLER_TIMEOUT_MINUTES = 30L

/**
 * The baked devrig version of a downloaded install script. Anchored, whitespace-tolerant patterns
 * matched against the real templates: `VERSION='…'` at column 0 (install.sh) and the padding-aligned
 * `$Version      = '…'` (install.ps1) — an exact-literal `$Version = ` match would never fire.
 */
fun parseInstallScriptVersion(content: String, isWin: Boolean): String? {
    val regex = if (isWin) ps1BakedVersionRegex else shBakedVersionRegex
    return regex.find(content)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
}

private val shBakedVersionRegex = Regex("""(?m)^VERSION='([^']*)'""")
private val ps1BakedVersionRegex = Regex("""(?m)^\s*\${'$'}Version\s*=\s*'([^']*)'""")

/**
 * Windows installer-host candidates, most reliable first: the absolute System32 Windows PowerShell
 * (GUI-launched agents commonly carry stripped PATHs, and a spawn failure would burn a capped attempt
 * on a non-install problem), then PATH `powershell`, then `pwsh` (not in-box on any Windows).
 */
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
 * Start the install script as a dedicated detached process — stdin closed (immediate EOF), stdout +
 * stderr appended to [logFile] whose first line records the resolved host binary, environment carries
 * `DEVRIG_AUTO_UPDATE=1` (so the `devrig install devrig` handoff honors the no-downgrade guard) —
 * then supervise: exit code, or null when the timeout fired and the WHOLE process tree was killed
 * before returning (the lock must never be released while a process that can mutate ~/.mcp-steroid
 * may still run). The child survives devrig's own death: an unsupervised installer completes and its
 * `devrig install devrig` handoff writes the completion marker itself.
 */
suspend fun superviseInstallerProcess(
    script: Path,
    logFile: Path,
    isWin: Boolean,
    timeout: Duration = INSTALLER_TIMEOUT_MINUTES.minutes,
): Int? {
    val hostCandidates = if (isWin) {
        windowsInstallerHostCandidates().map {
            listOf(it, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
        }
    } else {
        listOf(listOf("/bin/sh", script.toString()))
    }

    Files.createDirectories(logFile.parent)
    var process: Process? = null
    var spawnError: Exception? = null
    for (command in hostCandidates) {
        Files.writeString(
            logFile,
            "[mcp-steroid] installer host: ${command.joinToString(" ")}\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        val builder = ProcessBuilder(command)
        builder.environment()[ENV_DEVRIG_AUTO_UPDATE] = "1"
        builder.redirectErrorStream(true)
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
        try {
            process = builder.start()
            break
        } catch (e: Exception) {
            spawnError = e
            System.err.println("[mcp-steroid] installer host '${command.first()}' failed to start: $e")
        }
    }
    val started = process ?: throw IllegalStateException("no installer host could be started", spawnError)

    // stdin: close our end of the pipe so the child reads EOF immediately (the scripts are
    // contractually non-interactive; this mirrors install.sh's `< /dev/null`).
    try {
        started.outputStream.close()
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not close the installer's stdin: $e")
    }

    // runInterruptible on Dispatchers.IO: the blocking waitFor must not occupy the caller's thread,
    // or a single-threaded caller could never fire the timeout that cancels (interrupts) it.
    val exit = withTimeoutOrNull(timeout) {
        runInterruptible(Dispatchers.IO) { started.waitFor() }
    }
    if (exit != null) return exit

    killProcessTree(started.toHandle())
    return null
}

/** Descendants first, then the root, then confirm the root is gone (bounded grace). */
suspend fun killProcessTree(root: ProcessHandle) {
    try {
        root.descendants().forEach { it.destroyForcibly() }
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not enumerate installer descendants: $e")
    }
    root.destroyForcibly()
    val gone = withTimeoutOrNull(30.seconds) {
        runInterruptible(Dispatchers.IO) { root.onExit().get() }
        true
    }
    if (gone == null) {
        System.err.println("[mcp-steroid] installer process ${root.pid()} did not terminate within the grace period")
    }
}

/** Download an install script over TLS to [target]; false on any failure (caller records the attempt). */
suspend fun downloadInstallScript(url: String, target: Path): Boolean {
    class InstallScriptDownloader

    val client = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = false
    }
    return try {
        val response = client.get(url) {
            header("User-Agent", "devrig/${DevrigVersionMetadata.getDevrigVersion()}")
        }
        if (!response.status.isSuccess()) {
            System.err.println("[mcp-steroid] GET $url returned ${response.status}")
            return false
        }
        Files.createDirectories(target.parent)
        Files.writeString(target, response.bodyAsText())
        true
    } catch (e: Exception) {
        logger<InstallScriptDownloader>().debug("install script download failed: ${e.message}", e)
        System.err.println("[mcp-steroid] could not download $url: $e")
        false
    } finally {
        client.close()
    }
}
