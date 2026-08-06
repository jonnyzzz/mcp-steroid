/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.notification.NotificationAction
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
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotificationKind
import com.jonnyzzz.mcpSteroid.notifications.McpSteroidNotifications
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * What a failed registration's notification says: the reason first — it is the one part that tells the
 * user whether retrying is worth it — then the terminal fallback, the action that still works when the
 * IDE-side flow itself is what is broken. No pointer at the IDE log: a notification either carries what
 * the user needs or it is not worth showing (the Retry button rides next to this text).
 */
fun agentRegistrationFailureContent(agent: AiAgentCli, reason: String): String =
    // "run the command:" and not just "run": the boundary between the prose and the command
    // must be unambiguous — the same rule as the settings page's register receipt.
    "$reason<br>Or run the command <code>devrig install ${agent.binary}</code> in a terminal."

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
 * Everything the settings page renders about the devrig bridge, as one snapshot: is the bridge installed,
 * and — only when it is — where each agent stands with it. [agents] is empty when [devrigInstalled] is
 * false (there is nothing to check a registration against) and holds every [AiAgentCli], in declaration
 * order, when it is true.
 *
 * A value, not a live object: every page show computes a fresh one ([DevrigAgentRegistrationService.status]),
 * so there is no cache to invalidate and nothing to listen to.
 */
data class OnboardingStatus(
    val devrigInstalled: Boolean,
    val agents: Map<AiAgentCli, AgentRegistrationState>,
)

/**
 * Runs `devrig install <agent>` on behalf of the settings page, and answers what state each agent is in.
 *
 * **Both entry points are user-initiated**: [status] because the user opened the page, [register]
 * because they pressed a button. Nothing here runs on its own — the same rule the install flow follows
 * (see [DevrigSetupRunner]), and the reason a failure may be reported at all.
 *
 * Both are suspend request/response calls: the settings page launches them from its own dialog-scoped
 * coroutine and just renders the answer. All I/O happens inside this service, off the caller's thread.
 *
 * [scope] is the platform-injected service scope — already a supervisor
 * (`ComponentManagerImpl.instanceCoroutineScope` hands out `childScope(supervisor = true)`), so a failed
 * child can never block later launches and no hand-rolled `SupervisorJob` copy is needed. It only carries
 * the Retry action of a failure notification, which must not die with whatever dialog started the
 * original attempt.
 */
@Service(Service.Level.APP)
class DevrigAgentRegistrationService(private val scope: CoroutineScope) {
    private val log = thisLogger()

    /**
     * The whole onboarding picture, computed fresh on [Dispatchers.IO]: the devrig probe, then — only
     * when the bridge is there — every agent's registration state, checked concurrently (each check may
     * spawn `devrig install <agent> --check`, and the page should wait for the slowest, not the sum).
     *
     * Agents whose CLI is absent are answered from a PATH lookup alone — no subprocess. That keeps the
     * page honest and cheap: on a machine with one agent installed, opening this page starts exactly one
     * process, not one per agent we happen to support.
     */
    suspend fun status(): OnboardingStatus = withContext(Dispatchers.IO) {
        if (!devrigInstalled()) return@withContext OnboardingStatus(devrigInstalled = false, agents = emptyMap())
        coroutineScope {
            val states = AiAgentCli.entries.map { agent ->
                async { agent to checkLogged(agent) }
            }
            OnboardingStatus(devrigInstalled = true, agents = states.awaitAll().toMap())
        }
    }

    /**
     * [check], with every failure folded into [AgentRegistrationState.CHECK_FAILED] and the answer
     * logged — one line per agent per page opening: cheap, and the only thing that tells us afterwards
     * whether a row stuck on "Checking…" never got an answer or never got it to the screen.
     */
    private fun checkLogged(agent: AiAgentCli): AgentRegistrationState {
        val state = try {
            check(agent)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("could not check the ${agent.displayName} registration", e)
            AgentRegistrationState.CHECK_FAILED
        }
        log.info("${agent.displayName} registration state: $state")
        return state
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
     * Register [agent] with devrig and answer the resulting state, so the row that was pressed can show
     * it. The work runs in a background progress [Task.Backgroundable] — a press must run to completion
     * even when the Settings dialog closes over it — and this suspend call just awaits the task's answer:
     * a cancelled caller (the dialog closing) stops listening, never the registration itself, and the
     * next page show recomputes the truth via [status].
     *
     * The success case is deliberately quiet: the row flipping to "Registered" is the confirmation, and a
     * balloon on top of it would be noise. A failure does get one — the user asked for this — carrying
     * devrig's own first line, which is the part that says whether retrying is worth it.
     */
    suspend fun register(agent: AiAgentCli, project: Project?): AgentRegistrationState {
        val result = CompletableDeferred<AgentRegistrationState>()
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
                    result.complete(if (ok) AgentRegistrationState.REGISTERED else checkLogged(agent))
                }
            }
        })
        return result.await()
    }

    /**
     * Report a failed registration with its two ways forward: Retry — most of these are transient, and
     * the alternative is making the user find the row's button again — and the terminal command in the
     * text ([agentRegistrationFailureContent]). Retry runs on the service's own [scope], not the dialog's:
     * a notification outlives the Settings page, so its action must too. The retried run reports the same
     * way — a failure re-notifies here, a success is quiet — and whatever it left behind is what the next
     * page show renders ([status]).
     */
    private fun notifyFailure(
        project: Project?,
        agent: AiAgentCli,
        reason: String,
    ) {
        McpSteroidNotifications.getInstance().notify(
            McpSteroidNotificationKind.AGENT_REGISTRATION, project, NotificationType.ERROR,
            "Could not register ${agent.displayName}",
            agentRegistrationFailureContent(agent, reason),
            NotificationAction.createSimpleExpiring("Retry") { scope.launch { register(agent, project) } },
        )
    }

    companion object {
        /** `--check` asks the agent's own CLI to list its MCP servers, which is not always quick. */
        const val CHECK_TIMEOUT_MS = 60_000

        /** Registration edits a config file after the same listing step. */
        const val REGISTER_TIMEOUT_MS = 120_000

        fun getInstance(): DevrigAgentRegistrationService = service()
    }
}
