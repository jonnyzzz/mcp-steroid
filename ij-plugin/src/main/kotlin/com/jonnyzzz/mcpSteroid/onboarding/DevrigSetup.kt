/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.getBuildVersion
import com.jonnyzzz.mcpSteroid.devrig.UpdateCoordination
import com.jonnyzzz.mcpSteroid.devrig.UpdateStateInfo
import com.jonnyzzz.mcpSteroid.devrig.devrigInstallerUrl
import com.jonnyzzz.mcpSteroid.devrig.downloadInstallerScript
import com.jonnyzzz.mcpSteroid.devrig.installerCommands
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotificationKind
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotifications
import com.jonnyzzz.mcpSteroid.settings.McpSteroidConfigurable
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
import com.jonnyzzz.mcpSteroid.devrig.DevrigUserLauncher
import com.jonnyzzz.mcpSteroid.devrig.resolveHomePaths
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The stable devrig launcher path for this OS — [DevrigUserLauncher.path] over the shared home layout
 * ([resolveHomePaths]), parameterized by [userHome] for tests. Delegation only: the launcher-path logic
 * has exactly one home, `:devrig-common`, so what the settings page renders
 * (`devrigLauncherDisplayPath`), what devrig registers, and the file this module checks cannot drift.
 */
fun devrigBinPath(userHome: Path, windows: Boolean): Path =
    DevrigUserLauncher.path(resolveHomePaths(userHome), windows)

/**
 * The `mcpServers` snippet that points an MCP client at this machine's devrig over stdio — for the clients
 * devrig has no CLI for (Cursor, Windsurf, anything configured by an `mcp.json`-style file).
 *
 * Built from the same [DevrigUserLauncher.invocation] that devrig itself registers with (and that
 * `devrig install config` prints), so what the settings page offers to copy and what
 * `devrig install <agent>` writes cannot drift. The settings-page twin of `devrig install config`.
 */
fun devrigStdioMcpConfigJson(userHome: Path, windows: Boolean): String =
    stdioMcpServersJson(DevrigUserLauncher.invocation(resolveHomePaths(userHome), listOf("mcp"), windows))

/**
 * Marker the claude-plugin's own install wrapper writes on failure (`bin/install-devrig`), read by its
 * SessionStart hook and `/devrig:status`. The IDE-side install writes the SAME marker, so a failure is
 * visible from the agent side no matter which half of the product attempted the install. Lives in the
 * plugin↔devrig marker directory ([PidMarker.markerDirectory]).
 */
fun devrigInstallFailedMarker(userHome: Path): Path =
    PidMarker.markerDirectory(userHome).resolve("bootstrap-install.failed")

private fun setupLog(): Logger = Logger.getInstance("com.jonnyzzz.mcpSteroid.onboarding.DevrigSetup")

private const val MIB = 1024L * 1024L

/**
 * Polls how much of the announced artifact size has landed on disk, once per [pollInterval], until
 * cancelled. The installer stages every download as `binaries/.tmp.*` before moving it into place
 * (`install.sh.tmpl`), so [stagedBytes] sums those as the bytes-so-far for the current phase; [total]
 * comes from the installer's own log line and 0 means "no download announced yet — nothing to report".
 *
 * A coroutine, not a `ScheduledExecutorService` task, on purpose: `scheduleWithFixedDelay` stops
 * scheduling **forever** after any exception escapes one run — including a rethrown
 * [ProcessCanceledException] — per its own contract, so one bad poll froze the progress bar for the rest
 * of a 30-minute install with no signal anywhere. In a coroutine the failure handling is the loop's own
 * (log and keep polling), and PCE/[CancellationException] mean exactly what they should: this poller is
 * being cancelled, so it ends. Same shape as the platform's `JVMStatsToOTelReporter` (`launch` + `delay`
 * loop).
 */
fun CoroutineScope.startDownloadPoller(
    indicator: ProgressIndicator,
    total: AtomicLong,
    pollInterval: Duration = 1.seconds,
    stagedBytes: () -> Long,
): Job = launch(Dispatchers.IO) {
    while (isActive) {
        delay(pollInterval)
        try {
            val expected = total.get()
            if (expected <= 0) continue
            val staged = stagedBytes()
            indicator.fraction = (staged.toDouble() / expected).coerceIn(0.0, 1.0)
            indicator.text2 = "${staged / MIB} MB of ${expected / MIB} MB"
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setupLog().debug("download progress poll failed: ${e.message}")
        }
    }
}

/**
 * Reassembles a process's output chunks into lines. `onTextAvailable` delivers arbitrary chunks (curl's
 * progress bar is not even newline-terminated), so [append] buffers until a newline and hands every
 * completed line to [onLine]. [flush] then hands over whatever is left once the stream is done: the
 * installer's **last** line — its `ERROR: …` reason, on the failures that matter most — can arrive with
 * no trailing newline, and before the flush existed it was silently dropped, leaving the user a generic
 * exit-code message instead of the reason.
 */
class ProcessLineBuffer(private val onLine: (String) -> Unit) {
    private val pending = StringBuilder()

    /** Buffers [text] and emits every line it completes. Safe to call from the process-reader threads. */
    fun append(text: String) {
        synchronized(pending) {
            pending.append(text)
            while (true) {
                val nl = pending.indexOf("\n")
                if (nl < 0) break
                val line = pending.substring(0, nl)
                pending.delete(0, nl + 1)
                onLine(line)
            }
        }
    }

    /** Emits the trailing newline-less line, if any. Call once the process is done; further calls no-op. */
    fun flush() {
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                onLine(pending.toString())
                pending.setLength(0)
            }
        }
    }
}

/**
 * Installs devrig from the IDE, in a cancellable background progress task.
 *
 * **It installs the bridge and stops there.** Pointing an agent at devrig is a separate, explicit act:
 * doing it here would mean the IDE edits another product's configuration on the strength of a click that
 * said "install devrig", and there is more than one agent to choose from anyway.
 *
 * The installer's own output drives the progress indicator — it is a ~611 MB download, so a static
 * "Downloading…" label for up to 30 minutes is indistinguishable from a hang. Its `[mcp-steroid] ` lines
 * become the phase text ([parseInstallerLine]) and the size it announces becomes the denominator for a
 * real fraction, measured from the staging files it writes under `~/.mcp-steroid/binaries`.
 *
 * **Every notification below reports something the user asked for by pressing a button.** Nothing here
 * runs on its own, so there is no path by which someone is told that an operation they never started has
 * failed. Keep it that way: if this is ever triggered automatically, the reporting has to change with it.
 *
 * An application service, so the install's helper coroutines (the download poller) have a structured
 * parent: the platform-injected [scope] dies with the plugin, and every child launched on it dies too.
 */
@Service(Service.Level.APP)
class DevrigSetupRunner(private val scope: CoroutineScope) {
    private val log = thisLogger()

    /**
     * Install devrig by running the canonical installer.
     *
     * [project] only anchors the progress bar and the notifications; it may be null when the call comes
     * from an application-level surface such as the settings page with no project open.
     *
     * [onFinished] runs on the task's background thread after the install ends, however it ended —
     * the completion callback of a button the user pressed, not a monitoring pipeline. The settings
     * page uses it to re-probe and stop offering an install that just succeeded. It survives the
     * failure notification's Retry, so a retried install reports back to the same surface.
     */
    fun runInstall(project: Project?, onFinished: (() -> Unit)? = null) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Installing devrig…", true) {
            override fun run(indicator: ProgressIndicator) {
                val userHome = Path.of(System.getProperty("user.home"))
                val windows = SystemInfo.isWindows
                try {
                    if (!installDevrig(project, indicator, userHome, windows, onFinished)) return
                    notify(
                        project, NotificationType.INFORMATION, "devrig is installed",
                        // The next step lives on the settings page — the action below takes the user
                        // there. A "see Settings | Tools | …" menu path in prose is the same dead end
                        // as "see the IDE log": directions to walk instead of a button to press.
                        "Register your agent with it to bridge this IDE.",
                        openSettingsAction(project),
                    )
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("devrig install failed", e)
                    val reason = e.message ?: e.javaClass.simpleName
                    writeFailureMarker(userHome, "devrig install failed: $reason")
                    notifyFailure(project, "Installing devrig failed: $reason.", onFinished)
                } finally {
                    onFinished?.invoke()
                }
            }
        })
    }

    /**
     * [runInstall], awaitable: suspends until the install task ends, however it ended. The install
     * itself stays on its background [Task] — cancelling the awaiting coroutine (a Settings page
     * closing over it) only stops the listening, never a download the user asked for. The settings
     * page awaits this to stop offering an install that just succeeded.
     */
    suspend fun install(project: Project?) {
        val finished = CompletableDeferred<Unit>()
        runInstall(project) { finished.complete(Unit) }
        finished.await()
    }

    /**
     * Runs the canonical installer with live progress. Returns true when devrig is installed afterwards.
     *
     * Takes part in devrig's own update coordination (`~/.mcp-steroid/update`, [UpdateCoordination])
     * rather than running blind. devrig self-updates by running the very same installer, so without
     * this an IDE and a devrig session could each start a ~611 MB download of the same thing: the
     * installer tolerates that (staging files are per-pid and the promote is atomic) but does not
     * dedupe it, so both would download in full. Concretely: yield while another process holds a live
     * marker, announce our own while we run, and leave an `updated-<version>` record so a running
     * devrig tells its user to restart the session onto the new build.
     */
    private fun installDevrig(
        project: Project?,
        indicator: ProgressIndicator,
        userHome: Path,
        windows: Boolean,
        onFinished: (() -> Unit)?,
    ): Boolean {
        val coordination = UpdateCoordination(resolveHomePaths(userHome).updateDir)
        if (coordination.anyLiveInProgressMarker()) {
            // Not a failure, and not something the user did wrong — devrig got there first. The settings
            // page is where "is it ready yet?" gets answered, so that is the action this one carries.
            notify(
                project, NotificationType.INFORMATION, "devrig is already being installed",
                "Another process is installing devrig right now. It will be ready shortly.",
                openSettingsAction(project),
            )
            log.info("devrig install skipped: another process holds a live update marker")
            return false
        }

        indicator.isIndeterminate = true
        indicator.text = "Downloading the devrig installer…"
        // The shared download (`:devrig-common`, [downloadInstallerScript]) — the very code devrig's
        // own updater runs, so the URL, the cache-buster and the fetch-then-run shape cannot drift
        // between the two halves. Failures are reported here (there is no retry inside; the
        // notification offers one), with the User-Agent naming this half in the server logs.
        val url = devrigInstallerUrl(windows)
        val script = coordination.scriptFile(windows)
        if (!downloadInstallerScript(url, script, userAgent = "mcp-steroid/${getBuildVersion().value}")) {
            writeFailureMarker(userHome, "could not download $url")
            notifyFailure(project, "Could not download the devrig installer from $url.", onFinished)
            return false
        }

        // The release ships the plugin and devrig from one VERSION, so our own build version names the
        // devrig we are about to install — no extra request just to fill in the marker.
        val target = getBuildVersion().value
        val info = UpdateStateInfo(
            pid = coordination.ownPid,
            // Write-only debugging JSON by its own contract ([UpdateStateInfo]) — not worth parsing
            // a version out of the launcher text just to fill it in.
            currentVersion = "unknown",
            targetVersion = target,
            startedAt = System.currentTimeMillis(),
            scriptUrl = devrigInstallerUrl(windows),
        )
        coordination.writeInProgressMarker(target, info)
        try {
            return runInstaller(project, indicator, userHome, windows, script, coordination, target, info, onFinished)
        } finally {
            coordination.deleteInProgressMarker(target)
            try {
                Files.deleteIfExists(script)
            } catch (e: Exception) {
                log.warn("could not delete the downloaded install script $script", e)
            }
        }
    }

    private fun runInstaller(
        project: Project?,
        indicator: ProgressIndicator,
        userHome: Path,
        windows: Boolean,
        script: Path,
        coordination: UpdateCoordination,
        target: String,
        info: UpdateStateInfo,
        onFinished: (() -> Unit)?,
    ): Boolean {
        indicator.text = "Installing devrig…"
        val lastError = StringBuilder()
        val total = AtomicLong(0)
        val started = System.nanoTime()

        val poller = scope.startDownloadPoller(indicator, total) { stagedBytes(userHome) }
        val result = try {
            runCommandStreaming(installerCommands(script, windows), indicator, timeoutMs = 30 * 60 * 1000) { line ->
                val step = parseInstallerLine(line) ?: return@runCommandStreaming
                if (step.isError) {
                    lastError.appendLine(step.text)
                } else {
                    indicator.text = step.text
                }
                step.totalBytes?.let {
                    total.set(it)
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                }
            }
        } finally {
            poller.cancel()
        }

        val installed = devrigInstalled(userHome, windows)
        val ok = !result.isTimeout && !result.isCancelled && result.exitCode == 0 && installed
        analyticsBeacon.capture(
            "devrig_install_finished", project,
            mapOf(
                "ok" to ok,
                "exit_code" to result.exitCode,
                "timeout" to result.isTimeout,
                "cancelled" to result.isCancelled,
                "duration_ms" to elapsedMs(started),
            ),
        )
        if (ok) {
            clearFailureMarker(userHome)
            // What a running devrig reads to tell its user "restart the session onto the new build".
            coordination.writeUpdatedMarker(target, info.copy(completedAt = System.currentTimeMillis()))
            return true
        }
        // Cancelling is a choice, not a failure: the user already knows what happened and why, so saying
        // it back to them in an error balloon would be noise.
        if (result.isCancelled) {
            log.info("devrig install cancelled by the user")
            return false
        }

        val reason = when {
            result.isTimeout -> "the installer timed out after 30 minutes"
            lastError.isNotBlank() -> lastError.trim().toString()
            result.exitCode != 0 -> "the installer exited with code ${result.exitCode}"
            else -> "the installer finished but devrig was not found at ${devrigBinPath(userHome, windows)}"
        }
        writeFailureMarker(userHome, reason)
        notifyFailure(project, "$reason.", onFinished)
        log.warn("devrig install failed: $reason\n${result.output.takeLast(4000)}")
        return false
    }

    private fun stagedBytes(userHome: Path): Long {
        val dir = resolveHomePaths(userHome).binariesDir
        if (!Files.isDirectory(dir)) return 0
        return dir.listDirectoryEntries(".tmp.*").sumOf { entry ->
            try {
                if (Files.isRegularFile(entry)) Files.size(entry) else 0
            } catch (e: Exception) {
                // A staging file can vanish mid-poll (the installer moves it into place) — that is normal,
                // so it contributes 0 bytes, but never silently: the reason belongs in the log.
                log.debug("cannot size staging file $entry: ${e.message}")
                0
            }
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isTimeout: Boolean,
        val isCancelled: Boolean = false,
    ) {
        val output: String get() = stderr.ifBlank { stdout }
    }

    /**
     * Runs the installer, handing every completed output line to [onLine] as it arrives rather than
     * buffering until exit — that buffering is why an earlier version could only show a static label for
     * the whole download.
     *
     * Waits in short slices so pressing Cancel on the progress bar actually stops the installer: a single
     * blocking `waitFor(timeout)` would ignore the indicator for up to 30 minutes.
     */
    private fun runCommandStreaming(
        commands: List<List<String>>,
        indicator: ProgressIndicator,
        timeoutMs: Int,
        onLine: (String) -> Unit,
    ): CommandResult {
        // Most reliable host first; a host that cannot even start is not a failed install.
        var handler: OSProcessHandler? = null
        var spawnError: Exception? = null
        for (argv in commands) {
            try {
                handler = OSProcessHandler(GeneralCommandLine(argv))
                break
            } catch (e: Exception) {
                spawnError = e
                log.warn("installer host '${argv.firstOrNull()}' failed to start: ${e.message}")
            }
        }
        if (handler == null) {
            throw IllegalStateException(
                "no installer host could be started (tried ${commands.joinToString { it.firstOrNull().orEmpty() }})",
                spawnError,
            )
        }
        return streamProcess(handler, indicator, timeoutMs, onLine)
    }

    private fun streamProcess(
        handler: OSProcessHandler,
        indicator: ProgressIndicator,
        timeoutMs: Int,
        onLine: (String) -> Unit,
    ): CommandResult {
        val captured = StringBuilder()
        val lines = ProcessLineBuffer(onLine)

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                synchronized(captured) {
                    // Keep the tail only: the installer is chatty and this is for the log line on failure.
                    captured.append(text)
                    if (captured.length > 64_000) captured.delete(0, captured.length - 64_000)
                }
                lines.append(text)
            }
        })

        handler.startNotify()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var cancelled = false
        var timedOut = false
        while (!handler.waitFor(CANCEL_POLL_MS)) {
            if (indicator.isCanceled) {
                cancelled = true
                break
            }
            if (System.nanoTime() >= deadline) {
                timedOut = true
                break
            }
        }
        // The installer's final line may carry the reason (`ERROR: …`) and no trailing newline — hand it
        // over now that no more output is coming. On the cancel/timeout paths this is best-effort (the
        // fragment may be mid-write), but timeout/cancel already outrank a partial reason downstream.
        lines.flush()
        val output = synchronized(captured) { captured.toString() }
        if (cancelled || timedOut) {
            handler.destroyProcess()
            return CommandResult(
                exitCode = -1, stdout = "", stderr = output, isTimeout = timedOut, isCancelled = cancelled,
            )
        }
        return CommandResult(exitCode = handler.exitCode ?: -1, stdout = "", stderr = output, isTimeout = false)
    }

    private fun writeFailureMarker(userHome: Path, reason: String) {
        val marker = devrigInstallFailedMarker(userHome)
        try {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, reason + "\n")
        } catch (e: Exception) {
            log.warn("could not write $marker", e)
        }
    }

    private fun clearFailureMarker(userHome: Path) {
        try {
            Files.deleteIfExists(devrigInstallFailedMarker(userHome))
        } catch (e: Exception) {
            log.warn("could not remove the devrig install-failed marker", e)
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    /**
     * Report the outcome of an install the user started, via [McpSteroidNotifications]. One kind for the
     * whole install flow ([McpSteroidNotificationKind.DEVRIG_INSTALL]): its latest outcome is the truth,
     * so a new message replaces the pending one instead of stacking next to it. The message reports an
     * action the user just triggered and is watching, and the same fact is on the settings page a moment
     * later; anything missed stays in the Notifications tool window.
     *
     * Every message here carries at least one [action][actions] — a notification that only points
     * somewhere else ("see the IDE log") reports a problem and hands the user homework, which is worse
     * than saying nothing.
     */
    private fun notify(
        project: Project?,
        type: NotificationType,
        title: String,
        content: String,
        vararg actions: AnAction,
    ) {
        McpSteroidNotifications.getInstance()
            .notify(McpSteroidNotificationKind.DEVRIG_INSTALL, project, type, title, content, *actions)
    }

    /** Opens Settings | Tools | Devrig — the page where every next step of this flow lives. */
    private fun openSettingsAction(project: Project?): AnAction =
        NotificationAction.createSimpleExpiring("Open settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, McpSteroidConfigurable::class.java)
        }

    /**
     * Report a failed install. The reason comes from the installer itself (`ERROR: …` — a sha mismatch, a
     * dead mirror, no space), which is the only wording that tells the user whether retrying is worth it,
     * so it is the whole message — it stays copyable from the Notifications tool window. Retry is offered
     * right here because most of these are transient and the alternative is making the user find the
     * button again; it keeps [onFinished] so the retried run reports back to the same surface that
     * started the original.
     */
    private fun notifyFailure(project: Project?, reason: String, onFinished: (() -> Unit)? = null) {
        McpSteroidNotifications.getInstance().notify(
            McpSteroidNotificationKind.DEVRIG_INSTALL, project, NotificationType.ERROR,
            "devrig install failed",
            reason,
            NotificationAction.createSimpleExpiring("Retry") { runInstall(project, onFinished) },
        )
    }

    companion object {
        fun getInstance(): DevrigSetupRunner = service()

        /** How long a single wait slice is; also the worst-case delay before Cancel takes effect. */
        private const val CANCEL_POLL_MS = 200L
    }
}
