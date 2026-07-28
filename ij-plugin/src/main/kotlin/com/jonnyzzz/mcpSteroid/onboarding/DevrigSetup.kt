/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.concurrency.AppExecutorUtil
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.listDirectoryEntries

const val INSTALL_SH_URL = "https://mcp-steroid.jonnyzzz.com/install.sh"
const val INSTALL_PS1_URL = "https://mcp-steroid.jonnyzzz.com/install.ps1"

/** The stable devrig launcher path for this OS. */
fun devrigBinPath(userHome: Path, windows: Boolean): Path =
    userHome.resolve(".mcp-steroid").resolve("bin").resolve(if (windows) "devrig.cmd" else "devrig")

/**
 * Marker the claude-plugin's own install wrapper writes on failure (`bin/install-devrig`), read by its
 * SessionStart hook and `/devrig:status`. The IDE-side install writes the SAME marker, so a failure is
 * visible from the agent side no matter which half of the product attempted the install.
 */
fun devrigInstallFailedMarker(userHome: Path): Path =
    userHome.resolve(".mcp-steroid").resolve("markers").resolve("bootstrap-install.failed")

/** Where the installer stages its in-flight downloads (`.tmp.*` under `binaries/`). */
private fun devrigBinariesDir(userHome: Path): Path =
    userHome.resolve(".mcp-steroid").resolve("binaries")

/** Argv that runs the canonical devrig installer for this OS (the same one-liner the docs publish). */
fun installerArgv(windows: Boolean): List<String> =
    if (windows) listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "irm $INSTALL_PS1_URL | iex")
    else listOf("/bin/sh", "-c", "curl -fsSL $INSTALL_SH_URL | sh")

/** Argv that enables the Claude marketplace plugin via the Plan-1 devrig verb. */
fun connectClaudeArgv(devrigBin: Path): List<String> =
    listOf(devrigBin.toString(), "connect", "claude")

/**
 * Runs the IDE-first migration in a background progress task: install devrig (or update a stale one),
 * then `devrig connect claude`, then report the outcome via the onboarding notification group.
 *
 * The installer's own output drives the progress indicator — it is a ~611 MB download, so a static
 * "Downloading…" label for up to 30 minutes is indistinguishable from a hang. Its `[mcp-steroid] ` lines
 * become the phase text ([parseInstallerLine]) and the size it announces becomes the denominator for a
 * real fraction, measured from the staging files it writes under `~/.mcp-steroid/binaries`.
 */
class DevrigSetupRunner {
    private val log = thisLogger()

    fun runEnable(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Setting up devrig…", true) {
            override fun run(indicator: ProgressIndicator) {
                val userHome = Path.of(System.getProperty("user.home"))
                val windows = SystemInfo.isWindows
                try {
                    val devrig = devrigBinPath(userHome, windows)
                    // Re-running the installer is also how an UPDATE happens: the one-liner re-fetches
                    // install.sh, which always points at the current release.
                    val outdated = DevrigConnectionStateService.getInstance().current()?.outdated == true
                    if (!devrigInstalled(userHome, windows) || outdated) {
                        if (!installDevrig(project, indicator, userHome, windows)) return
                    }

                    indicator.isIndeterminate = true
                    indicator.text = "Connecting Claude Code to this IDE…"
                    indicator.text2 = ""
                    val started = System.nanoTime()
                    val connect = runCommand(connectClaudeArgv(devrig), timeoutMs = 60 * 1000)
                    analyticsBeacon.capture(
                        "devrig_connect_claude_finished", project,
                        mapOf(
                            "ok" to (!connect.isTimeout && connect.exitCode == 0),
                            "exit_code" to connect.exitCode,
                            "timeout" to connect.isTimeout,
                            "duration_ms" to elapsedMs(started),
                        ),
                    )
                    if (connect.isTimeout || connect.exitCode != 0) {
                        val content = if (connect.isTimeout)
                            "`devrig connect claude` timed out. See the IDE log for details."
                        else
                            "`devrig connect claude` exited with code ${connect.exitCode}. See the IDE log for details."
                        notify(project, NotificationType.ERROR, "Could not connect Claude Code", content)
                        log.warn("devrig connect claude failed: ${connect.stderr.ifBlank { connect.stdout }}")
                        return
                    }

                    notify(project, NotificationType.INFORMATION, "Claude Code connected to this IDE",
                        "Restart Claude Code (or start a new session) to drive this IDE with devrig.")
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("devrig setup failed", e)
                    writeFailureMarker(userHome, "devrig setup failed: ${e.message ?: e.javaClass.simpleName}")
                    notify(project, NotificationType.ERROR, "devrig setup failed",
                        "Setting up devrig failed: ${e.message ?: e.javaClass.simpleName}. See the IDE log for details.")
                } finally {
                    // Whatever happened, the widget must reflect reality afterwards.
                    DevrigConnectionStateService.getInstance().refreshAsync()
                }
            }
        })
    }

    /** Runs the canonical installer with live progress. Returns true when devrig is installed afterwards. */
    private fun installDevrig(
        project: Project,
        indicator: ProgressIndicator,
        userHome: Path,
        windows: Boolean,
    ): Boolean {
        indicator.isIndeterminate = true
        indicator.text = "Installing devrig…"
        val lastError = StringBuilder()
        val total = AtomicLong(0)
        val started = System.nanoTime()

        val poller = startDownloadPoller(userHome, indicator, total)
        val result = try {
            runCommandStreaming(installerArgv(windows), timeoutMs = 30 * 60 * 1000) { line ->
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
            poller.cancel(false)
        }

        val installed = devrigInstalled(userHome, windows)
        val ok = !result.isTimeout && result.exitCode == 0 && installed
        analyticsBeacon.capture(
            "devrig_install_finished", project,
            mapOf(
                "ok" to ok,
                "exit_code" to result.exitCode,
                "timeout" to result.isTimeout,
                "duration_ms" to elapsedMs(started),
            ),
        )
        if (ok) {
            clearFailureMarker(userHome)
            return true
        }

        val reason = when {
            result.isTimeout -> "the installer timed out after 30 minutes"
            lastError.isNotBlank() -> lastError.trim().toString()
            result.exitCode != 0 -> "the installer exited with code ${result.exitCode}"
            else -> "the installer finished but devrig was not found at ${devrigBinPath(userHome, windows)}"
        }
        writeFailureMarker(userHome, reason)
        notify(project, NotificationType.ERROR, "devrig install failed", "$reason. See the IDE log for details.")
        log.warn("devrig install failed: $reason\n${result.output.takeLast(4000)}")
        return false
    }

    /**
     * Reports how much of the announced artifact size has landed on disk. The installer stages every
     * download as `binaries/.tmp.*` before moving it into place (`install.sh.tmpl`), so their combined
     * size is the bytes-so-far for the current phase; [total] comes from the installer's own log line.
     */
    private fun startDownloadPoller(
        userHome: Path,
        indicator: ProgressIndicator,
        total: AtomicLong,
    ): ScheduledFuture<*> =
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            try {
                val expected = total.get()
                if (expected <= 0) return@scheduleWithFixedDelay
                val staged = stagedBytes(userHome)
                indicator.fraction = (staged.toDouble() / expected).coerceIn(0.0, 1.0)
                indicator.text2 = "${staged / MIB} MB of ${expected / MIB} MB"
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                log.debug("download progress poll failed: ${e.message}")
            }
        }, 1, 1, TimeUnit.SECONDS)

    private fun stagedBytes(userHome: Path): Long {
        val dir = devrigBinariesDir(userHome)
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
    ) {
        val output: String get() = stderr.ifBlank { stdout }
    }

    private fun runCommand(argv: List<String>, timeoutMs: Int): CommandResult {
        val cmd = GeneralCommandLine(argv)
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        return CommandResult(out.exitCode, out.stdout, out.stderr, out.isTimeout)
    }

    /**
     * Like [runCommand], but hands every completed output line to [onLine] as it arrives instead of
     * buffering everything until exit — that buffering is why the old implementation could only show a
     * static label for the whole download.
     */
    private fun runCommandStreaming(
        argv: List<String>,
        timeoutMs: Int,
        onLine: (String) -> Unit,
    ): CommandResult {
        val handler = OSProcessHandler(GeneralCommandLine(argv))
        val captured = StringBuilder()
        val pending = StringBuilder()

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                synchronized(captured) {
                    // Keep the tail only: the installer is chatty and this is for the log line on failure.
                    captured.append(text)
                    if (captured.length > 64_000) captured.delete(0, captured.length - 64_000)
                }
                // onTextAvailable delivers arbitrary chunks (curl's progress bar is not even newline
                // terminated), so buffer until a newline before parsing.
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
        })

        handler.startNotify()
        val finished = handler.waitFor(timeoutMs.toLong())
        val output = synchronized(captured) { captured.toString() }
        if (!finished) {
            handler.destroyProcess()
            return CommandResult(exitCode = -1, stdout = "", stderr = output, isTimeout = true)
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

    private fun notify(project: Project, type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")
            .createNotification(title, content, type)
            .notify(project)
    }

    private companion object {
        const val MIB = 1024L * 1024L
    }
}
