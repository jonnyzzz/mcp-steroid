/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.devrig.INSTALL_CHECK_DISABLED_EXIT_CODE
import com.jonnyzzz.mcpSteroid.devrig.INSTALL_CHECK_DRIFT_EXIT_CODE
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the settings page can say about one agent's MCP registration.
 *
 * [CLI_MISSING] is a separate state on purpose: "press Register" is a lie when the agent's CLI is not on
 * the machine, and the failure the press would produce ("claude: command not found") explains far less
 * than not offering the button in the first place.
 */
enum class AgentRegistrationState {
    CHECKING,
    REGISTERED,

    /**
     * Registered correctly, and switched off in the agent's own config — a state no `mcp list` reports,
     * so without this the page would say "Registered" about a bridge the agent will never use.
     */
    DISABLED,
    NOT_REGISTERED,
    CLI_MISSING,
    CHECK_FAILED,
}

/**
 * `devrig install <agent>`, or its read-only dry-run. The IDE never re-implements the registration: it
 * runs the same verb the docs tell users to run, so there is exactly one implementation of what a
 * canonical registration is.
 */
fun devrigInstallAgentArgv(devrigBin: Path, agent: AiAgentCli, check: Boolean): List<String> =
    buildList {
        add(devrigBin.toString())
        add("install")
        add(agent.binary)
        if (check) add("--check")
    }

/**
 * Find an executable on PATH. Generic on purpose — every agent gets the same treatment, and the plugin
 * has no business knowing which one is fashionable.
 *
 * The separator and the candidate extensions come from [windows] rather than the runtime OS, so the
 * function is pure enough to test for either platform on any host.
 */
fun findOnPath(binary: String, pathEnv: String?, windows: Boolean): Path? {
    val names = if (windows) listOf("$binary.exe", "$binary.cmd", "$binary.bat", binary) else listOf(binary)
    val separator = if (windows) ';' else ':'
    for (entry in pathEnv?.split(separator).orEmpty()) {
        if (entry.isBlank()) continue
        for (name in names) {
            val candidate = Path.of(entry).resolve(name)
            if (Files.isRegularFile(candidate)) return candidate
        }
    }
    return null
}

/**
 * Map `devrig install <agent> --check`'s outcome onto a row state.
 *
 * Exit 0 means the registration is already canonical; [INSTALL_CHECK_DRIFT_EXIT_CODE] (1) means install
 * would change something — no entry, a stale command, duplicates, a custom name; and
 * [INSTALL_CHECK_DISABLED_EXIT_CODE] (2) means it is registered but switched off in the agent's own
 * config. All three are answers. Anything else, including a timeout, is us failing to find out, which is
 * not the same as "not registered" and must not be reported as one.
 *
 * A devrig older than the disabled check never returns 2; it reports a disabled registration as canonical
 * (0), which is what the page showed before this existed. Wrong, but not newly wrong.
 */
fun agentStateFromCheck(exitCode: Int, timedOut: Boolean): AgentRegistrationState = when {
    timedOut -> AgentRegistrationState.CHECK_FAILED
    exitCode == 0 -> AgentRegistrationState.REGISTERED
    exitCode == INSTALL_CHECK_DRIFT_EXIT_CODE -> AgentRegistrationState.NOT_REGISTERED
    exitCode == INSTALL_CHECK_DISABLED_EXIT_CODE -> AgentRegistrationState.DISABLED
    else -> AgentRegistrationState.CHECK_FAILED
}

/**
 * Runs `devrig install <agent>` on behalf of the settings page, and answers what state each agent is in.
 *
 * **Both entry points are user-initiated**: [checkAsync] because the user opened the page, [register]
 * because they pressed a button. Nothing here runs on its own — the same rule the install flow follows
 * (see [DevrigSetupRunner]), and the reason a failure may be reported at all.
 */
@Service(Service.Level.APP)
class DevrigAgentRegistrationService(private val scope: CoroutineScope) {
    private val log = thisLogger()

    /**
     * Answer [onResult] (on a background thread) with the current state of [agent].
     *
     * Agents whose CLI is absent are answered from a PATH lookup alone — no subprocess. That keeps the
     * page honest and cheap: on a machine with one agent installed, opening this page starts exactly one
     * process, not one per agent we happen to support.
     */
    fun checkAsync(agent: AiAgentCli, onResult: (AgentRegistrationState) -> Unit) {
        scope.launch {
            val state = try {
                withContext(Dispatchers.IO) { check(agent) }
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("could not check the ${agent.displayName} registration", e)
                AgentRegistrationState.CHECK_FAILED
            }
            // One line per agent per page opening: cheap, and the only thing that tells us afterwards
            // whether a row stuck on "Checking…" never got an answer or never got it to the screen.
            log.info("${agent.displayName} registration state: $state")
            onResult(state)
        }
    }

    private fun check(agent: AiAgentCli): AgentRegistrationState {
        val userHome = Path.of(System.getProperty("user.home"))
        val windows = SystemInfo.isWindows
        if (!devrigInstalled(userHome, windows)) return AgentRegistrationState.CHECK_FAILED
        if (findOnPath(agent.binary, System.getenv("PATH"), windows) == null) {
            return AgentRegistrationState.CLI_MISSING
        }
        val argv = devrigInstallAgentArgv(devrigBinPath(userHome, windows), agent, check = true)
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine(argv), CHECK_TIMEOUT_MS)
        if (output.isTimeout || output.exitCode > INSTALL_CHECK_DISABLED_EXIT_CODE) {
            log.warn("`${argv.joinToString(" ")}` exited with ${output.exitCode}: ${output.stderr.take(2000)}")
        }
        return agentStateFromCheck(output.exitCode, output.isTimeout)
    }

    /**
     * Register [agent] with devrig, in a background progress task. [onFinished] receives the resulting
     * state so the row that was pressed can show it.
     *
     * The success case is deliberately quiet: the row flipping to "Registered" is the confirmation, and a
     * balloon on top of it would be noise. A failure does get one — the user asked for this — carrying
     * devrig's own first line, which is the part that says whether retrying is worth it.
     */
    fun register(agent: AiAgentCli, project: Project?, onFinished: (AgentRegistrationState) -> Unit) {
        val title = "Registering ${agent.displayName} with devrig…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val userHome = Path.of(System.getProperty("user.home"))
                val windows = SystemInfo.isWindows
                val argv = devrigInstallAgentArgv(devrigBinPath(userHome, windows), agent, check = false)
                var ok = false
                try {
                    val output = ExecUtil.execAndGetOutput(GeneralCommandLine(argv), REGISTER_TIMEOUT_MS)
                    ok = !output.isTimeout && output.exitCode == 0
                    if (!ok) {
                        val reason = if (output.isTimeout) {
                            "it timed out"
                        } else {
                            output.stderr.lineSequence().firstOrNull { it.isNotBlank() }
                                ?: "it exited with code ${output.exitCode}"
                        }
                        log.warn("`${argv.joinToString(" ")}` failed: ${output.stderr.take(2000)}")
                        notifyFailure(project, agent, reason)
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("could not register ${agent.displayName}", e)
                    notifyFailure(project, agent, e.message ?: e.javaClass.simpleName)
                } finally {
                    analyticsBeacon.capture(
                        "devrig_agent_registered", project,
                        mapOf("agent" to agent.binary, "ok" to ok),
                    )
                    // Re-check rather than assume: the row must show what is actually there now.
                    onFinished(if (ok) AgentRegistrationState.REGISTERED else check(agent))
                }
            }
        })
    }

    private fun notifyFailure(project: Project?, agent: AiAgentCli, reason: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(ONBOARDING_NOTIFICATION_GROUP)
            .createNotification(
                "Could not register ${agent.displayName}",
                // "run the command:" and not just "run": the boundary between the prose and the command
                // must be unambiguous — the same rule as the settings page's register receipt.
                "$reason<br>See the IDE log for details, or run the command " +
                    "<code>devrig install ${agent.binary}</code> in a terminal.",
                NotificationType.ERROR,
            )
            .notify(project)
    }

    companion object {
        /** `--check` asks the agent's own CLI to list its MCP servers, which is not always quick. */
        const val CHECK_TIMEOUT_MS = 60_000

        /** Registration edits a config file after the same listing step. */
        const val REGISTER_TIMEOUT_MS = 120_000

        fun getInstance(): DevrigAgentRegistrationService = service()
    }
}
