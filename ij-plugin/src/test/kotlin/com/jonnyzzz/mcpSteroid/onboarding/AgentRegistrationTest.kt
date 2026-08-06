/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.devrig.INSTALL_CHECK_DISABLED_EXIT_CODE
import com.jonnyzzz.mcpSteroid.devrig.INSTALL_CHECK_DRIFT_EXIT_CODE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class AgentRegistrationTest {

    @Test
    fun `the argv is devrig's own install verb, per agent`() {
        val bin = Path.of("/home/u/.mcp-steroid/bin/devrig")
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "claude"),
            devrigInstallAgentArgv(bin, AiAgentCli.CLAUDE, check = false),
        )
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "codex", "--check"),
            devrigInstallAgentArgv(bin, AiAgentCli.CODEX, check = true),
        )
        assertEquals(
            listOf("/home/u/.mcp-steroid/bin/devrig", "install", "gemini"),
            devrigInstallAgentArgv(bin, AiAgentCli.GEMINI, check = false),
        )
    }

    @Test
    fun `check outcomes map to states, and an unknown outcome is never reported as unregistered`() {
        assertEquals(AgentRegistrationState.REGISTERED, agentStateFromCheck(0, timedOut = false))
        assertEquals(
            AgentRegistrationState.NOT_REGISTERED,
            agentStateFromCheck(INSTALL_CHECK_DRIFT_EXIT_CODE, timedOut = false),
        )
        // Registered but switched off in the agent's own config — its own state, because "Registered"
        // would be a lie about a bridge the agent will never use.
        assertEquals(
            AgentRegistrationState.DISABLED,
            agentStateFromCheck(INSTALL_CHECK_DISABLED_EXIT_CODE, timedOut = false),
        )
        // Anything else is us failing to find out — a different fact from "not registered".
        assertEquals(AgentRegistrationState.CHECK_FAILED, agentStateFromCheck(64, timedOut = false))
        assertEquals(AgentRegistrationState.CHECK_FAILED, agentStateFromCheck(-1, timedOut = false))
        assertEquals(AgentRegistrationState.CHECK_FAILED, agentStateFromCheck(0, timedOut = true))
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

    @Test
    fun `findOnPath scans PATH entries in order`() {
        val first = Files.createTempDirectory("p1")
        val second = Files.createTempDirectory("p2")
        val claude = Files.createFile(second.resolve("claude"))

        assertNull(findOnPath("claude", pathEnv = null, windows = false))
        assertNull(findOnPath("claude", pathEnv = "", windows = false))
        assertNull(findOnPath("claude", pathEnv = first.toString(), windows = false))
        assertEquals(claude, findOnPath("claude", pathEnv = "$first:$second", windows = false))

        // An earlier entry wins.
        val shadow = Files.createFile(first.resolve("claude"))
        assertEquals(shadow, findOnPath("claude", pathEnv = "$first:$second", windows = false))
    }

    @Test
    fun `findOnPath uses the windows separator and executable extensions`() {
        val dir = Files.createTempDirectory("p-win")
        val cmd = Files.createFile(dir.resolve("gemini.cmd"))
        // A ':' separator would parse "C:\dir" as two entries and find nothing.
        assertEquals(cmd, findOnPath("gemini", pathEnv = "$dir;$dir", windows = true))
        // The extensionless name is not what Windows would launch, so it must not be found on POSIX rules.
        assertNull(findOnPath("gemini", pathEnv = dir.toString(), windows = false))
    }
}
