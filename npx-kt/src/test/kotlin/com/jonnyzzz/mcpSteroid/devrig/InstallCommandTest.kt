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
    // The install command registers the STABLE user-facing wrapper, not the install tree. Fixed home so
    // the expected launch command is deterministic across platforms (windows = false in tests).
    private val home = HomePaths(Path.of("/home/user/.mcp-steroid"))
    private val launcherPath = "/home/user/.mcp-steroid/bin/devrig"
    private val mcpCommand = DevrigUserLauncher.invocation(home, listOf("mcp"), windows = false)

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

    // A CANONICAL claude listing: exactly one 'mcp-steroid' entry whose command is the stable wrapper
    // install would register ("$launcherPath mcp"). Re-running install would change nothing.
    private val claudeCanonicalList = """
        Checking MCP server health…

        mcp-steroid: $launcherPath mcp - ✓ Connected
        playwright: npx @playwright/mcp@latest - ✓ Connected
    """.trimIndent()

    // The codex (--json) analog: command + args reconstruct to the exact canonical "$launcherPath mcp".
    private val codexCanonicalJson = """
        [
          {"name":"mcp-steroid","transport":{"type":"stdio","command":"$launcherPath","args":["mcp"]}},
          {"name":"playwright","transport":{"type":"stdio","command":"npx","args":["@playwright/mcp@latest"]}}
        ]
    """.trimIndent()

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
            listOf("mcp", "add", "--scope", "user", "mcp-steroid", "--", launcherPath, "mcp"),
            result.addInvocation.args,
        )
    }

    @Test
    fun `install add invocation per agent (codex, gemini)`() {
        val codex = runInstall(AiAgentCli.CODEX, RecordingRunner())
        assertEquals(
            listOf("mcp", "add", "mcp-steroid", "--", launcherPath, "mcp"),
            codex.addInvocation.args,
        )

        val gemini = runInstall(AiAgentCli.GEMINI, RecordingRunner())
        assertEquals(
            listOf(
                "mcp", "add", "--type", "stdio", "--scope", "user", "--trust", "mcp-steroid",
                launcherPath, "mcp",
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
        assertContains(out, "$launcherPath mcp")
        // The launch command is the stable bin launcher (no JAVA_HOME mentioned to the user).
        assertTrue(out.contains("~/.mcp-steroid/bin", ignoreCase = false), out)
        assertFalse(out.contains("JAVA_HOME"), "install output must not mention JAVA_HOME:\n$out")
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

    // ── install --check (read-only dry-run, issue #86) ──

    @Test
    fun `check reports no drift for a canonical registration and exits 0`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, claudeCanonicalList)),
            reachability = IdeReachabilityReport(reachable = 2, discovered = 3),
        )
        assertEquals(0, r.exitCode)
        assertContains(r.stdout, "already canonical")
        assertContains(r.stdout, "No drift")
        assertContains(r.stdout, "2 of 3 discovered backend(s) reachable")
    }

    @Test
    fun `check never mutates for ANY agent - only 'mcp list' is ever invoked (canonical and drift)`() {
        // The no-mutate guarantee is the most safety-critical property of --check, and parseMcpServerList
        // takes a DIFFERENT code path per agent (codex = JSON, claude/gemini = line) — so assert it for all
        // three, in both canonical and drift states.
        val cases = listOf(
            AiAgentCli.CLAUDE to claudeCanonicalList,
            AiAgentCli.CLAUDE to "mcp-steroid: /old/path/devrig mcp - ✓ Connected",
            AiAgentCli.CODEX to codexCanonicalJson,
            AiAgentCli.CODEX to codexListJson,
            AiAgentCli.GEMINI to claudeCanonicalList,
            AiAgentCli.GEMINI to "Checking MCP server health…\n\n",
        )
        for ((agent, list) in cases) {
            val r = runCheck(agent, RecordingRunner(listResult = AiAgentCliResult(0, list)))
            assertTrue(r.invocations.all { it.args.contains("list") }, "$agent: ${r.invocations}")
            assertFalse(
                r.invocations.any { it.args.contains("add") || it.args.contains("remove") },
                "$agent check must never add/remove: ${r.invocations}",
            )
        }
    }

    @Test
    fun `check reports no drift for a canonical codex (--json) registration and exits 0`() {
        val r = runCheck(AiAgentCli.CODEX, RecordingRunner(listResult = AiAgentCliResult(0, codexCanonicalJson)))
        assertEquals(0, r.exitCode)
        assertContains(r.stdout, "already canonical")
    }

    @Test
    fun `check detects a custom-named devrig entry as drift (by config), exit 1`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, "old-steroid: /usr/bin/env /custom/devrig mcp - ✓ Connected")),
        )
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        // Detected by its command (not name) → removed WITHOUT the "if present" qualifier.
        assertContains(r.stdout, "remove 'old-steroid'")
        assertFalse(r.stdout.contains("remove 'old-steroid', if present"), r.stdout)
        assertContains(r.stdout, "add 'mcp-steroid'")
    }

    @Test
    fun `check detects two simultaneous devrig entries as drift, both removed without 'if present'`() {
        val r = runCheck(AiAgentCli.CLAUDE, RecordingRunner(listResult = AiAgentCliResult(0, claudeListWithBothNames)))
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        assertContains(r.stdout, "remove 'mcp-steroid'")
        assertContains(r.stdout, "remove 'devrig'")
        // Both names were detected, so neither carries the defensive "if present" qualifier.
        assertFalse(r.stdout.contains(", if present"), r.stdout)
    }

    @Test
    fun `check reports the launcher is absent when it does not exist yet`() {
        // Canonical agent registration, but the wrapper file itself is missing — the diagnostic the
        // feature exists for (issue #86): the registration points at a launcher that is not there yet.
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, claudeCanonicalList)),
            missingLauncherPath = Path.of(launcherPath),
        )
        assertContains(r.stdout, "does not exist yet")
        assertContains(r.stdout, launcherPath)
    }

    @Test
    fun `check detects a stale command as drift and prints the repair plan, exit 1`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, "mcp-steroid: /old/path/devrig mcp - ✓ Connected")),
        )
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        assertContains(r.stdout, "Drift detected")
        assertContains(r.stdout, "remove 'mcp-steroid'")
        assertContains(r.stdout, "add 'mcp-steroid' → $launcherPath mcp")
    }

    @Test
    fun `check treats an empty list as drift (install would add) and exits 1`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, "Checking MCP server health…\n\n")),
        )
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        assertTrue(r.stdout.contains("no existing", ignoreCase = true), r.stdout)
        assertContains(r.stdout, "add 'mcp-steroid'")
    }

    @Test
    fun `check treats an unreadable list as drift (cannot verify) and includes the legacy name, exit 1`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(exitCode = 2, output = "boom: cannot list\n")),
        )
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        assertTrue(r.stdout.contains("could not read", ignoreCase = true), r.stdout)
        // Unreadable → install would defensively clear the legacy 'devrig' name too (shared installRemovalNames).
        assertContains(r.stdout, "remove 'devrig'")
    }

    @Test
    fun `check tolerates an IDE-discovery failure (reports it, does not abort the diagnosis)`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, "Checking MCP server health…\n\n")),
            reachabilityThrows = true,
        )
        assertEquals(INSTALL_CHECK_DRIFT_EXIT_CODE, r.exitCode)
        assertContains(r.stderr, "IDE discovery failed")
        assertContains(r.stdout, "discovery failed")
    }

    @Test
    fun `check reports when no IDE backends are discovered`() {
        val r = runCheck(
            AiAgentCli.CLAUDE,
            RecordingRunner(listResult = AiAgentCliResult(0, claudeCanonicalList)),
            reachability = IdeReachabilityReport(reachable = 0, discovered = 0),
        )
        assertContains(r.stdout, "none discovered")
    }

    private fun runCheck(
        agent: AiAgentCli,
        runner: RecordingRunner,
        reachability: IdeReachabilityReport = IdeReachabilityReport(reachable = 0, discovered = 0),
        reachabilityThrows: Boolean = false,
        missingLauncherPath: Path? = null,
    ): CheckRunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runInstallCheckCommand(
            command = DevrigCommand.DevrigCommandInstall(agent, check = true),
            mcpCommand = mcpCommand,
            out = PrintStream(stdout, true, Charsets.UTF_8),
            err = PrintStream(stderr, true, Charsets.UTF_8),
            runner = runner,
            ideReachability = { if (reachabilityThrows) error("probe boom") else reachability },
            missingLauncherPath = missingLauncherPath,
        )
        return CheckRunResult(
            exitCode = exitCode,
            invocations = runner.invocations,
            stdout = stdout.toString(Charsets.UTF_8),
            stderr = stderr.toString(Charsets.UTF_8),
        )
    }

    private data class CheckRunResult(
        val exitCode: Int,
        val invocations: List<AiAgentCliInvocation>,
        val stdout: String,
        val stderr: String,
    )

    private fun runInstall(agent: AiAgentCli, runner: RecordingRunner): InstallRunResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exitCode = runInstallCommand(
            command = DevrigCommand.DevrigCommandInstall(agent),
            mcpCommand = mcpCommand,
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
