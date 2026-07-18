/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Path

const val INSTALL_SH_URL = "https://mcp-steroid.jonnyzzz.com/install.sh"
const val INSTALL_PS1_URL = "https://mcp-steroid.jonnyzzz.com/install.ps1"

/** The stable devrig launcher path for this OS. */
fun devrigBinPath(userHome: Path, windows: Boolean): Path =
    userHome.resolve(".mcp-steroid").resolve("bin").resolve(if (windows) "devrig.cmd" else "devrig")

/** Argv that runs the canonical devrig installer for this OS (the same one-liner the docs publish). */
fun installerArgv(windows: Boolean): List<String> =
    if (windows) listOf("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "irm $INSTALL_PS1_URL | iex")
    else listOf("/bin/sh", "-c", "curl -fsSL $INSTALL_SH_URL | sh")

/** Argv that enables the Claude marketplace plugin via the Plan-1 devrig verb. */
fun connectClaudeArgv(devrigBin: Path): List<String> =
    listOf(devrigBin.toString(), "connect", "claude")

/**
 * Runs the IDE-first "Enable" flow in a background progress task: install devrig if missing, then run
 * `devrig connect claude`, then report the outcome via the onboarding notification group. Platform
 * wiring over the pure builders above; not unit-tested (see Task 3 registration test + controller smoke).
 */
class DevrigSetupRunner {
    private val log = thisLogger()

    fun runEnable(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Setting up devrig…", true) {
            override fun run(indicator: ProgressIndicator) {
                val userHome = Path.of(System.getProperty("user.home"))
                val windows = SystemInfo.isWindows
                val devrig = devrigBinPath(userHome, windows)

                if (!devrigInstalled(userHome, windows)) {
                    indicator.text = "Downloading and installing devrig…"
                    val install = runCommand(installerArgv(windows), timeoutMs = 30 * 60 * 1000)
                    if (install.exitCode != 0 || !devrigInstalled(userHome, windows)) {
                        notify(NotificationType.ERROR, "devrig install failed",
                            "The devrig installer exited with code ${install.exitCode}. See the IDE log for details.")
                        log.warn("devrig install failed: ${install.stderr.ifBlank { install.stdout }}")
                        return
                    }
                }

                indicator.text = "Connecting Claude Code to this IDE…"
                val connect = runCommand(connectClaudeArgv(devrig), timeoutMs = 60 * 1000)
                if (connect.exitCode != 0) {
                    notify(NotificationType.ERROR, "Could not connect Claude Code",
                        "`devrig connect claude` exited with code ${connect.exitCode}. See the IDE log for details.")
                    log.warn("devrig connect claude failed: ${connect.stderr.ifBlank { connect.stdout }}")
                    return
                }

                notify(NotificationType.INFORMATION, "Claude Code connected to this IDE",
                    "Restart Claude Code (or start a new session) to drive this IDE with devrig.")
            }
        })
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommand(argv: List<String>, timeoutMs: Int): CommandResult {
        val cmd = GeneralCommandLine(argv)
        val out = ExecUtil.execAndGetOutput(cmd, timeoutMs)
        return CommandResult(out.exitCode, out.stdout, out.stderr)
    }

    private fun notify(type: NotificationType, title: String, content: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("jonnyzzz.mcp.steroid.onboarding")
            .createNotification(title, content, type)
            .notify(null)
    }
}
