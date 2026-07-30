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
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The active auto-updater — one `tick()` per docs/updates-check/devrig-auto-update.md. Collaborators
 * are injected so the decision tree is unit-testable without HTTP, processes, or a real home dir.
 */
class AutoUpdater(
    val homePaths: HomePaths,
    val currentVersion: DevrigVersion = DevrigVersionMetadata.getBuildVersion(),
    val isWin: Boolean = isWindows(),
    val coordination: UpdateCoordination = UpdateCoordination(homePaths.updateDir),
    val notify: (String) -> Unit = { },
    val fetchPromoted: suspend () -> DevrigVersion? = { fetchVersionInfo()?.let { DevrigVersion.parse(it.versionBase) } },
    val downloadScript: suspend (url: String, target: Path) -> Boolean = ::downloadInstallScript,
    /** Spawn + supervise the installer; exit code, or null when the timeout killed it. */
    val runInstaller: suspend (script: Path, logFile: Path) -> Int? = { script, logFile ->
        superviseInstallerProcess(script, logFile, isWin)
    },
    val noAutoUpdateEnv: String? = System.getenv(ENV_DEVRIG_NO_AUTO_UPDATE),
    val binRegisterOptOutEnv: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER),
    /** Fired once right before the installer spawns; Main wires this to the beacon (`devrig_self_update`). */
    val onUpdateTriggered: (promotedVersion: String) -> Unit = { },
) {
    private var restartNotified = false

    /** Step-1 gate: SNAPSHOT builds skip everything (incl. GC); the launcher-write opt-out too. */
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
        val target = promoted.value

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
        val scriptUrl = if (isWin) "https://devrig.dev/install.ps1" else "https://devrig.dev/install.sh"
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
            // step 8 — recheck after announcing: the lowest pid wins; losers yield silently
            // (own marker deleted in the finally; only the announce↔recheck race remains — Tradeoff 1)
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
        // step 9 — download; the script is opaque (never inspected — Tradeoff 6); failures retry next tick
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
                    (if (exit == null) "(timed out; installer killed)" else "(exit $exit)") +
                    "; log: $logFile — will retry next tick",
            )
        }
    }

    fun logFileFor(target: String): Path =
        homePaths.logsDir.resolve("update-${coordination.ownPid}-${target.substringBefore('-').substringBefore('/')}.log")

    private fun notifyRestartOnce(version: String) {
        if (restartNotified) return
        restartNotified = true
        val message = buildString {
            appendLine()
            appendLine("devrig $version is installed — restart your agent session to use it (current: $currentVersion).")
            appendLine()
        }
        System.err.println(message)
        notify(message)
    }

}

/**
 * One flow for every devrig command. An enabled MCP session ticks IMMEDIATELY, then re-checks/
 * retries every 3–8 h forever; everything else gets the passive marker-aware notice once.
 */
suspend fun runAutoUpdateFlow(
    homePaths: HomePaths,
    mcpSession: Boolean,
    notify: (String) -> Unit,
    onUpdateTriggered: (promotedVersion: String) -> Unit = { },
) {
    val updater = AutoUpdater(homePaths = homePaths, notify = notify, onUpdateTriggered = onUpdateTriggered)
    if (!mcpSession || !updater.isActive()) {
        checkForUpdates(homePaths, notify)
        return
    }
    while (true) {
        try {
            updater.tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] auto-update tick failed: $e")
        }
        delay(Random.nextLong(180, 481).minutes)
    }
}

/** Opens every installer attempt in the per-pid log file; the timestamp follows. */
const val INSTALLER_ATTEMPT_SEPARATOR_PREFIX = "===== [mcp-steroid] installer attempt at "

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
 * Run the install script detached (own stdio, survives devrig's death; stays in devrig's process
 * group — shielding it is platform-branch territory, see the design doc) and supervise it: exit
 * code, or null after the timeout force-kills the started process ONLY (no tree walk). Output is
 * appended to the per-pid [logFile], each attempt behind a timestamped separator.
 */
suspend fun superviseInstallerProcess(
    script: Path,
    logFile: Path,
    isWin: Boolean,
    timeout: Duration = 1.hours,
): Int? {
    val hostCandidates = if (isWin) {
        windowsInstallerHostCandidates().map {
            listOf(it, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
        }
    } else {
        listOf(listOf("/bin/sh", script.toString()))
    }

    Files.createDirectories(logFile.parent)
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

    // Close all our ends of the child's stdio: stdin pipe → immediate EOF; the redirected
    // stdout/stderr streams too — devrig keeps no handle to the child's stdio.
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
