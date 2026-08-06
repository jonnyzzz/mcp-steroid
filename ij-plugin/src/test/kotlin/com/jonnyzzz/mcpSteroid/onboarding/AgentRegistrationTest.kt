/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.devrig.InstallCheckAgentStatus
import com.jonnyzzz.mcpSteroid.devrig.parseInstallCheckAgentLines
import com.jonnyzzz.mcpSteroid.devrig.renderInstallCheckAgentLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class AgentRegistrationTest {

    @Test
    fun `the register argv is devrig's own install verb, per agent`() {
        val bin = Path.of("/home/u/.mcp-steroid/bin/devrig")
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "claude"),
            devrigInstallAgentArgv(bin, AiAgentCli.CLAUDE),
        )
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "codex"),
            devrigInstallAgentArgv(bin, AiAgentCli.CODEX),
        )
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "gemini"),
            devrigInstallAgentArgv(bin, AiAgentCli.GEMINI),
        )
    }

    @Test
    fun `the check argv is the bare all-agents verb - one spawn answers every row`() {
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "--check"),
            devrigInstallCheckAllArgv(Path.of("/home/u/.mcp-steroid/bin/devrig")),
        )
    }

    @Test
    fun `check statuses map to states, and an unknown outcome is never reported as unregistered`() {
        assertEquals(
            AgentRegistrationState.REGISTERED,
            agentStateFromCheckStatus(InstallCheckAgentStatus.REGISTERED),
        )
        assertEquals(
            AgentRegistrationState.NOT_REGISTERED,
            agentStateFromCheckStatus(InstallCheckAgentStatus.DRIFT),
        )
        // Registered but switched off in the agent's own config — its own state, because "Registered"
        // would be a lie about a bridge the agent will never use.
        assertEquals(
            AgentRegistrationState.DISABLED,
            agentStateFromCheckStatus(InstallCheckAgentStatus.DISABLED),
        )
        // devrig now owns the PATH answer too — the page never probes PATH itself.
        assertEquals(
            AgentRegistrationState.CLI_MISSING,
            agentStateFromCheckStatus(InstallCheckAgentStatus.CLI_MISSING),
        )
        // devrig failing to find out is a different fact from "not registered"…
        assertEquals(
            AgentRegistrationState.CHECK_FAILED,
            agentStateFromCheckStatus(InstallCheckAgentStatus.CHECK_FAILED),
        )
        // …and so is devrig answering nothing for the agent (an older devrig prints no lines at all):
        // degrade, never misreport.
        assertEquals(AgentRegistrationState.CHECK_FAILED, agentStateFromCheckStatus(null))
    }

    /**
     * The one-spawn wire format end to end: what devrig prints ([renderInstallCheckAgentLine], amid
     * prose) parses back and maps onto row states — including the row devrig never answered for.
     */
    @Test
    fun `one devrig stdout answers every row - an unanswered agent folds into CHECK_FAILED`() {
        val stdout = buildString {
            appendLine("Checking the 'mcp-steroid' MCP registration for every supported agent (read-only — nothing is changed).")
            appendLine()
            appendLine(renderInstallCheckAgentLine(AiAgentCli.CLAUDE, InstallCheckAgentStatus.REGISTERED))
            appendLine(renderInstallCheckAgentLine(AiAgentCli.CODEX, InstallCheckAgentStatus.CLI_MISSING))
            // No gemini line: e.g. a devrig killed between the lines and the reachability probe.
            appendLine()
            appendLine("IDE backends with the MCP Steroid plugin (read-only discovery, same scan as 'devrig backend'):")
            appendLine("  1 of 1 discovered backend(s) reachable.")
        }
        val statuses = parseInstallCheckAgentLines(stdout)
        val states = AiAgentCli.entries.associateWith { agentStateFromCheckStatus(statuses[it]) }
        assertEquals(AgentRegistrationState.REGISTERED, states[AiAgentCli.CLAUDE])
        assertEquals(AgentRegistrationState.CLI_MISSING, states[AiAgentCli.CODEX])
        assertEquals(AgentRegistrationState.CHECK_FAILED, states[AiAgentCli.GEMINI])
    }

    /**
     * A failure notification must carry what the user needs, not point at the IDE log: the reason leads
     * (it is what says whether retrying is worth it), and the terminal command follows — the action that
     * still works when the IDE-side flow itself is broken. The Retry button rides next to this text.
     */
    @Test
    fun `the failure notification leads with the reason and names the terminal command, never the log`() {
        val content = agentRegistrationFailureContent(AiAgentCli.CLAUDE, "it timed out")
        assertEquals(
            "it timed out<br>Or run the command <code>devrig install claude</code> in a terminal.",
            content,
        )
        for (agent in AiAgentCli.entries) {
            val perAgent = agentRegistrationFailureContent(agent, "reason")
            assertTrue(
                "the fallback must name the exact per-agent command; got '$perAgent'",
                perAgent.contains("<code>devrig install ${agent.binary}</code>"),
            )
            assertFalse(
                "a notification must carry an action, not homework; got '$perAgent'",
                perAgent.contains("log", ignoreCase = true),
            )
        }
    }
}
