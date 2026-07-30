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
import java.time.Instant
import kotlin.io.path.exists
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

const val DEVRIG_INSTALL_SH_URL = "https://devrig.dev/install.sh"
const val DEVRIG_INSTALL_PS1_URL = "https://devrig.dev/install.ps1"

/**
 * The active auto-updater — one `tick()` per docs/updates-check/devrig-auto-update.md's
 * "The update tick". Runs ONLY inside `devrig mcp` sessions (a long-lived parent that can supervise
 * the installer and deliver the restart notice over MCP); every collaborator is injected so the
 * whole decision tree is unit-testable without HTTP, processes, or a real `~/.mcp-steroid`.
 */
class AutoUpdater(
    val homePaths: HomePaths,
    val currentVersion: DevrigVersion = DevrigVersionMetadata.getBuildVersion(),
    val isWin: Boolean = isWindows(),
    val coordination: UpdateCoordination = UpdateCoordination(homePaths.updateDir),
    val notify: (String) -> Unit = { },
    val fetchPromoted: suspend () -> DevrigVersion? = { fetchVersionInfo()?.let { DevrigVersion.parse(it.versionBase) } },
    val downloadScript: suspend (url: String, target: Path) -> Boolean = ::downloadInstallScript,
    /** Spawn + supervise the installer; exit code, or null when the 1 h timeout killed it. */
    val runInstaller: suspend (script: Path, logFile: Path) -> Int? = { script, logFile ->
        superviseInstallerProcess(script, logFile, isWin)
    },
    val noAutoUpdateEnv: String? = System.getenv(ENV_DEVRIG_NO_AUTO_UPDATE),
    val binRegisterOptOutEnv: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER),
    /**
     * Fired exactly once per actually-triggered update — right before the installer spawns — with
     * the raw promoted version from version.json. Main wires this to the beacon
     * (`devrig_self_update` with `target_version`).
     */
    val onUpdateTriggered: (promotedVersion: String) -> Unit = { },
) {
    private var restartNotified = false

    /**
     * The step-1 gate. A SNAPSHOT build must skip the WHOLE tick including GC (a dev build must not
     * GC or write records for real installs; a SNAPSHOT `current` would also poison the GC bound).
     * The launcher-write opt-out also disables the updater: with launcher writes disabled, an
     * install could never take effect.
     */
    fun isActive(): Boolean = !currentVersion.isSnapshotBuild &&
        !parseUpdateEnvFlag(noAutoUpdateEnv) &&
        !parseUpdateEnvFlag(binRegisterOptOutEnv)

    suspend fun tick() {
        // steps 1-2 — gate, then fetch (GC needs `promoted` for its bound; no fetch → no tick at all)
        if (!isActive()) return
        val promoted = fetchPromoted() ?: return

        // step 3 — GC below min(current, promoted): a session running newer than the promoted
        // version (post-rollback) must not delete the records older sessions rely on
        coordination.gc(currentVersion, promoted, homePaths.logsDir)

        // step 4 — never downgrade; nothing to do when up to date
        if (!DevrigVersion.isUpdateAvailable(current = currentVersion, promoted = promoted)) return
        val target = baseVersionString(promoted.value)

        // step 5 — someone else updating (after the step-3 dead-file cleanup) → yield silently:
        // no notification before an install script completes
        if (coordination.anyLiveInProgressMarker()) return

        // step 6 — this version already installed → propose a restart, once per process
        if (coordination.hasUpdatedMarker(target)) {
            notifyRestartOnce(target)
            return
        }

        // There is deliberately NO failure tracking and NO retry cap: too many transient root causes
        // exist, and the goal is to keep users up to date — every failure simply retries on the next
        // scheduled tick (3–8 h), forever. Diagnosis lives in stderr + the per-attempt log files.

        // step 7 — announce ourselves (the "I am updating" record others yield to; not a lock)
        val scriptUrl = if (isWin) DEVRIG_INSTALL_PS1_URL else DEVRIG_INSTALL_SH_URL
        val logFile = logFileFor(target)
        val info = UpdateStateInfo(
            pid = coordination.ownPid,
            currentVersion = currentVersion.value,
            targetVersion = target,
            startedAt = coordination.clock(),
            logFile = logFile.toString(),
            scriptUrl = scriptUrl,
        )
        coordination.writeInProgressMarker(target, info)
        val script = coordination.scriptFile(isWin)
        try {
            // step 8 — recheck after announcing: lowest PID wins. Another process can pass the
            // step-5 scan in the same window and announce concurrently; if any OTHER live marker
            // (any version) carries a lower pid, that process wins — yield silently (our own marker
            // is deleted in the finally below; the next tick re-evaluates). Equal pids are
            // impossible on one host; a higher-pid rival that scans after us sees our lower pid
            // and yields the same way. Only the announce↔recheck race remains (Tradeoff 1).
            if (coordination.liveInProgressPids().any { it < coordination.ownPid }) return

            runAnnouncedUpdate(script, scriptUrl, logFile, target, info, promotedRaw = promoted.value)
        } finally {
            // a crashed tick leaves only a dead-pid marker, which any process cleans (step 3)
            coordination.deleteInProgressMarker(target)
            try {
                Files.deleteIfExists(script)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not delete the downloaded install script $script: $e")
            }
        }
    }

    private suspend fun runAnnouncedUpdate(
        script: Path,
        scriptUrl: String,
        logFile: Path,
        target: String,
        info: UpdateStateInfo,
        promotedRaw: String,
    ) {
        // step 9 — download. A failed download resolves as a stderr line and a quiet retry on the
        // next scheduled tick. The downloaded script is deliberately NOT inspected (no baked-version
        // parsing, no sanity check): devrig has no dependency on the script's internal format, and
        // anything wrong with the file surfaces when the installer runs it — the same retry path.
        // A mid-propagation stale script is an accepted tradeoff (design doc, Tradeoff 6).
        if (!downloadScript(scriptUrl, script)) {
            System.err.println("[mcp-steroid] could not download $scriptUrl; will retry next tick")
            return
        }

        // step 10 — the self-update is actually triggered; surface it to telemetry (best-effort)
        try {
            onUpdateTriggered(promotedRaw)
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] self-update trigger telemetry failed: $e")
        }
        val exit = try {
            runInstaller(script, logFile)
        } catch (e: Exception) {
            // spawn failure (missing shell, IO error) follows the same path as a non-zero exit
            System.err.println("[mcp-steroid] could not start the installer for $target: $e; will retry next tick")
            return
        }

        // steps 11-12
        if (exit == 0) {
            coordination.writeUpdatedMarker(target, info.copy(completedAt = coordination.clock()))
            notifyRestartOnce(target)
        } else {
            System.err.println(
                "[mcp-steroid] devrig auto-update to $target failed " +
                    (if (exit == null) "(timed out after $INSTALLER_TIMEOUT; installer killed)" else "(exit $exit)") +
                    "; log: $logFile — will retry next tick",
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

}

val INSTALLER_TIMEOUT: Duration = 1.hours

/** Opens every installer attempt in the per-pid log file; the timestamp follows. */
const val INSTALLER_ATTEMPT_SEPARATOR_PREFIX = "===== [mcp-steroid] installer attempt at "

/**
 * Windows installer-host candidates, most reliable first: the absolute System32 Windows PowerShell
 * (GUI-launched agents commonly carry stripped PATHs, and a spawn failure would waste a whole
 * 3–8 h retry cycle on a non-install problem), then PATH `powershell`, then `pwsh` (not in-box on
 * any Windows).
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
 * stderr appended to [logFile]. The log is per-pid (`update-<pid>-<version>.log`), so concurrent
 * devrig processes never clash; when the SAME process retries the same version on a later tick it
 * appends to the same file — each attempt opens with a timestamped separator line, then a record of
 * the resolved host binary, keeping the accumulated log readable. Then supervise:
 * exit code, or null when the 1 h timeout fired and the started process (the shell/PowerShell host,
 * and ONLY it) was force-killed before returning. Children of the killed shell may survive —
 * grandchildren are deliberately NOT killed (tree-walking is too much process-management detail for
 * this path; a surviving `curl`/`Invoke-WebRequest` finishes or dies on its own, and the next tick
 * retries anyway). Note the kill can land mid-`devrig install devrig`, so the launcher replacement
 * must be crash-safe — PR #385. The child survives devrig's own death by design: an unsupervised
 * orphan completes, but no `updated-` record is written without a supervisor — the next session
 * re-runs from cached artifacts.
 *
 * Detachment decision (documentation over code): the child stays a plain member of devrig's own
 * session/process group. Re-parenting it into its own session would shield it from an agent CLI
 * that signals devrig's WHOLE group on session close, but every real shield is a platform branch —
 * `setsid(1)` exists on Linux, macOS ships no such binary, and Windows would need CreateProcess
 * flags ProcessBuilder cannot set — while full daemonization (double-fork) would break the
 * `waitFor` → `updated-` contract outright. And a shielded survivor is exactly the unsupervised
 * orphan above: its success unrecorded, the work redone next session anyway. So a group-wide
 * SIGKILL takes installer and supervisor down together — a rare, accepted edge case; the next
 * session's tick re-runs from cached artifacts.
 */
suspend fun superviseInstallerProcess(
    script: Path,
    logFile: Path,
    isWin: Boolean,
    timeout: Duration = INSTALLER_TIMEOUT,
): Int? {
    val hostCandidates = if (isWin) {
        windowsInstallerHostCandidates().map {
            listOf(it, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
        }
    } else {
        listOf(listOf("/bin/sh", script.toString()))
    }

    Files.createDirectories(logFile.parent)
    // Attempt separator: retries of the same version by the same process land in the same per-pid
    // log file across 3-8 h ticks; the timestamp tells the attempts apart.
    Files.writeString(
        logFile,
        "\n$INSTALLER_ATTEMPT_SEPARATOR_PREFIX${Instant.now()} =====\n",
        StandardOpenOption.CREATE, StandardOpenOption.APPEND,
    )
    var process: Process? = null
    var spawnError: Exception? = null
    for (command in hostCandidates) {
        Files.writeString(
            logFile,
            "[mcp-steroid] installer host: ${command.joinToString(" ")}\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        val builder = ProcessBuilder(command)
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

    // Close ALL our ends of the child's stdio: the stdin pipe (the child reads EOF immediately —
    // the scripts are contractually non-interactive, mirroring install.sh's `< /dev/null`); stdout/
    // stderr are redirected to the log file, so the JVM-side streams are closed too — devrig keeps
    // no handle to the child's stdio.
    for ((name, stream) in listOf("stdin" to started.outputStream, "stdout" to started.inputStream, "stderr" to started.errorStream)) {
        try {
            stream.close()
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not close the installer's $name stream: $e")
        }
    }

    // runInterruptible on Dispatchers.IO: the blocking waitFor must not occupy the caller's thread,
    // or a single-threaded caller could never fire the timeout that cancels (interrupts) it.
    val exit = withTimeoutOrNull(timeout) {
        runInterruptible(Dispatchers.IO) { started.waitFor() }
    }
    if (exit != null) return exit

    // Timeout: kill ONLY the process we started (no tree walk — see the KDoc), then a short bounded
    // wait so we do not return while the kill is still in flight.
    started.destroyForcibly()
    val gone = withTimeoutOrNull(30.seconds) {
        runInterruptible(Dispatchers.IO) { started.waitFor() }
        true
    }
    if (gone == null) {
        System.err.println("[mcp-steroid] installer process ${started.pid()} did not terminate within the grace period")
    }
    return null
}

/** Download an install script over TLS to [target]; false on any failure (the caller retries quietly). */
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
