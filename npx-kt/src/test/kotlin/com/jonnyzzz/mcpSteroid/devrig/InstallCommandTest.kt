/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliInvocation
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliResult
import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCliRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InstallCommandTest {
    private val launcher = Path.of("/opt/devrig/bin/devrig")
    private val javaHome = Path.of("/opt/jdk-21")

    private val claudeListWithBothNames = """
        Checking MCP server health…

        playwright: npx @playwright/mcp@latest - ✓ Connected
        mcp-steroid: /usr/bin/env JAVA_HOME=/opt/jdk /opt/devrig/bin/devrig mpc - ✓ Connected
        devrig: /usr/bin/env /old/devrig mcp - ✗ Failed to connect
    """.trimIndent()

    private val codexListJson = """
        [
          {"name":"mcp-steroid","transport":{"type":"stdio","command":"/usr/bin/env",
            "args":["JAVA_HOME=/opt/jdk","/opt/devrig/bin/devrig","mpc"]}},
          {"name":"playwright","transport":{"type":"stdio","command":"npx","args":["@playwright/mcp@latest"]}}
        ]
    """.trimIndent()

    @Test
    fun `self mcp command uses current java home on unix`() {
        val command = selfMcpCommand(launcher, javaHome, windows = false)

        assertEquals("/usr/bin/env", command.command)
        assertEquals(listOf("JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"), command.args)
    }

    @Test
    fun `self mcp command uses cmd exe for bat launchers on windows`() {
        val command = selfMcpCommand(Path.of("/opt/devrig/bin/devrig.bat"), Path.of("/opt/jdk-21"), windows = true)

        assertEquals("cmd.exe", command.command)
        assertEquals(
            listOf("/d", "/c", "set \"JAVA_HOME=/opt/jdk-21\" && call \"/opt/devrig/bin/devrig.bat\" mcp"),
            command.args,
        )
    }

    @Test
    fun `install reviews the list first, then consolidates, then adds`() {
        // First invocation must be the list (review), last must be the add.
        val result = runInstall(AiAgentCli.CLAUDE, RecordingRunner())
        assertTrue(result.invocations.first().args.contains("list"), result.invocations.toString())
        assertEquals(listOf("mcp", "list"), result.invocations.first().args)
        assertTrue(result.invocations.last().args.contains("add"), result.invocations.toString())
    }

    @Test
    fun `install add invocation carries the canonical mcp launch command (claude)`() {
        val result = runInstall(AiAgentCli.CLAUDE, RecordingRunner())
        assertEquals(0, result.exitCode)
        assertEquals(
            listOf("mcp", "add", "--scope", "user", "mcp-steroid", "--",
                "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"),
            result.addInvocation.args,
        )
    }

    @Test
    fun `install add invocation per agent (codex, gemini)`() {
        val codex = runInstall(AiAgentCli.CODEX, RecordingRunner())
        assertEquals(
            listOf("mcp", "add", "mcp-steroid", "--", "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp"),
            codex.addInvocation.args,
        )

        val gemini = runInstall(AiAgentCli.GEMINI, RecordingRunner())
        assertEquals(
            listOf(
                "mcp", "add", "--type", "stdio", "--scope", "user", "--trust", "mcp-steroid",
                "/usr/bin/env", "JAVA_HOME=/opt/jdk-21", "/opt/devrig/bin/devrig", "mcp",
            ),
            gemini.addInvocation.args,
        )
    }

    @Test
    fun `install consolidates both 'mcp-steroid' and 'devrig' entries, leaving unrelated servers alone`() {
        val result = runInstall(
            AiAgentCli.CLAUDE,
            RecordingRunner(
                listResult = AiAgentCliResult(0, claudeListWithBothNames),
                presentForRemoval = setOf("mcp-steroid", "devrig"),
            ),
        )

        assertEquals(0, result.exitCode)
        // Both devrig-owned names are removed, in list order; the unrelated 'playwright' is never touched.
        assertEquals(listOf("mcp-steroid", "devrig"), result.removedNames)
        assertFalse(result.removeNames.contains("playwright"), result.removeNames.toString())
        assertContains(result.stdout, "found 2 devrig registration(s)")
        // The review reports BOTH detection signals (name and configuration).
        assertContains(result.stdout, "'mcp-steroid' (matched by name + config)")
        assertContains(result.stdout, "'devrig' (matched by name + config)")
        assertContains(result.stdout, "removed 'mcp-steroid'")
        assertContains(result.stdout, "removed 'devrig'")
    }

    @Test
    fun `install detects a devrig server registered under a custom name (by its command)`() {
        val list = """
            old-steroid: /usr/bin/env JAVA_HOME=/x /custom/devrig mpc - ✓ Connected
            github: docker run ghcr.io/github/github-mcp-server - ✓ Connected
        """.trimIndent()
        val result = runInstall(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, list), presentForRemoval = setOf("old-steroid")),
        )

        assertEquals(0, result.exitCode)
        assertContains(result.removeNames, "old-steroid")
        assertFalse(result.removeNames.contains("github"), result.removeNames.toString())
        // Detected purely by its configuration (command runs devrig), not its name.
        assertContains(result.stdout, "'old-steroid' (matched by config)")
    }

    @Test
    fun `install consolidates a codex registration parsed from --json`() {
        val result = runInstall(
            AiAgentCli.CODEX,
            RecordingRunner(listResult = AiAgentCliResult(0, codexListJson), presentForRemoval = setOf("mcp-steroid")),
        )

        assertEquals(0, result.exitCode)
        assertEquals(listOf("mcp", "list", "--json"), result.invocations.first().args)
        assertContains(result.removedNames, "mcp-steroid")
        assertFalse(result.removeNames.contains("playwright"), result.removeNames.toString())
    }

    @Test
    fun `first install with an empty list adds cleanly and reports nothing to clean up`() {
        val result = runInstall(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, "Checking MCP server health…\n\n"), presentForRemoval = emptySet()),
        )

        assertEquals(0, result.exitCode)
        assertTrue(
            result.stdout.contains("no existing", ignoreCase = true),
            "should report no existing registration; got:\n${result.stdout}",
        )
        assertContains(result.stdout, "nothing to clean up")
    }

    @Test
    fun `when listing fails, install falls back to clearing the known devrig names`() {
        val result = runInstall(
            AiAgentCli.CLAUDE,
            RecordingRunner(
                listResult = AiAgentCliResult(exitCode = 2, output = "boom: cannot list\n"),
                presentForRemoval = setOf("mcp-steroid", "devrig"),
            ),
        )

        assertEquals(0, result.exitCode)
        assertContains(result.removeNames, "mcp-steroid")
        assertContains(result.removeNames, "devrig")
        assertTrue(
            result.stdout.contains("could not read the server list", ignoreCase = true),
            result.stdout,
        )
    }

    @Test
    fun `install output explains what it does, the launch command, and how to verify`() {
        val out = runInstall(AiAgentCli.CLAUDE, RecordingRunner()).stdout

        assertContains(out, "Claude")
        assertTrue(out.contains("review", ignoreCase = true), out)
        assertTrue(out.contains("consolidat", ignoreCase = true), out)
        assertContains(out, "/usr/bin/env JAVA_HOME=/opt/jdk-21 /opt/devrig/bin/devrig mcp")
        assertContains(out, "/opt/jdk-21")
        assertTrue(out.contains("Re-running", ignoreCase = true) || out.contains("safe", ignoreCase = true), out)
        assertContains(out, "claude mcp list")
    }

    @Test
    fun `install reports a registration failure clearly and returns the agent exit code`() {
        val result = runInstall(
            AiAgentCli.CLAUDE,
            RecordingRunner(addResult = AiAgentCliResult(exitCode = 17, output = "boom: could not write config\n")),
        )

        assertEquals(17, result.exitCode)
        assertContains(result.stderr, "boom: could not write config")
        assertContains(result.stderr, "17")
        assertTrue(result.stderr.contains("fail", ignoreCase = true), result.stderr)
    }

    private fun runInstall(agent: AiAgentCli, runner: RecordingRunner): InstallRunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runInstallCommand(
            command = DevrigCommand.DevrigCommandInstall(agent),
            launcher = launcher,
            javaHome = javaHome,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            runner = runner,
        )
        val removeInvocations = runner.invocations.filter { it.args.contains("remove") }
        return InstallRunResult(
            exitCode = exitCode,
            invocations = runner.invocations,
            removeNames = removeInvocations.map { it.args.last() },
            removedNames = runner.removed,
            addInvocation = runner.invocations.last { it.args.contains("add") },
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8),
        )
    }

    /**
     * Replays a recorded agent CLI: returns [listResult] for `mcp list`, a success for removing any name
     * in [presentForRemoval] (non-zero "not found" otherwise), and [addResult] for `mcp add`.
     */
    private class RecordingRunner(
        private val listResult: AiAgentCliResult = AiAgentCliResult(0, ""),
        private val presentForRemoval: Set<String> = emptySet(),
        private val addResult: AiAgentCliResult = AiAgentCliResult(0, "registered\n"),
    ) : AiAgentCliRunner {
        val invocations = mutableListOf<AiAgentCliInvocation>()
        val removed = mutableListOf<String>()
        override fun run(invocation: AiAgentCliInvocation): AiAgentCliResult = when {
            invocation.args.contains("list") -> listResult
            invocation.args.contains("remove") -> {
                val name = invocation.args.last()
                if (name in presentForRemoval) {
                    removed += name
                    AiAgentCliResult(0, "Removed MCP server $name\n")
                } else {
                    AiAgentCliResult(1, "No MCP server $name found\n")
                }
            }
            else -> addResult
        }.also { invocations += invocation }
    }

    private data class InstallRunResult(
        val exitCode: Int,
        val invocations: List<AiAgentCliInvocation>,
        val removeNames: List<String>,
        val removedNames: List<String>,
        val addInvocation: AiAgentCliInvocation,
        val stdout: String,
        val stderr: String,
    )
}
