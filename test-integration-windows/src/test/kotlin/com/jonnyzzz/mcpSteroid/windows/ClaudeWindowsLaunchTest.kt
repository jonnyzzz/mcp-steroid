package com.jonnyzzz.mcpSteroid.windows

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Verifies — on a real Windows agent, against the real Windows build of Claude Code — HOW Claude
 * resolves and launches a plugin's stdio MCP `command` **and its hooks**. Live counterpart to the
 * static analysis + Docker/Linux findings in jonnyzzz/mcp-steroid#253.
 *
 * Goal: pin the **tested, script-only** way to call the installed devrig from a plugin (MCP + hooks),
 * with minimal dependencies. So this exercises EVERY candidate hook form and records which fire.
 *
 * Windows-only; gated OFF on macOS/Linux at the Gradle task level (build.gradle.kts →
 * `tasks.test { enabled = isWindows }`). No runtime `assumeTrue`/`@EnabledOnOs` skips.
 *
 * The probe writes one line per launch to `%USERPROFILE%\portable-probe.log`:
 *   `LAUNCHED tag=<tag> via=<cmd|sh|shell|script>`
 * so each entry says both WHAT launched (the tag) and THROUGH WHAT (a `.cmd`, an `sh` script, an inline
 * shell command, or a script path). No API key needed — MCP servers + hooks spawn during session init,
 * before the `-p` turn fails auth.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeWindowsLaunchTest {

    private lateinit var logLines: List<String>

    // Match the tag exactly up to the trailing " via=" so a tag that is a PREFIX of another (e.g.
    // hook-exec-cmd vs hook-exec-cmdc) doesn't false-match. Log format: "LAUNCHED tag=<tag> via=<x>".
    private fun fired(tag: String) = logLines.any { it.contains("tag=$tag ") }

    @BeforeAll
    fun runClaudeOnceAndCaptureProbeLog() {
        val cacheDir = Path.of(System.getProperty("windows.test.cache.dir"))
        cacheDir.createDirectories()

        val claudeExe = downloadWindowsClaude(cacheDir)
        val pluginDir = writeProbePlugin(cacheDir.resolve("probe-plugin"))

        val fakeHome = Files.createTempDirectory(cacheDir, "home").toAbsolutePath()
        val logFile = fakeHome.resolve("portable-probe.log")
        Files.deleteIfExists(logFile)

        val result = RunProcessRequest(
            args = listOf(
                claudeExe.absolutePathString(),
                "--plugin-dir", pluginDir.absolutePathString(),
                "-p", "reply with OK",
            ),
        )
            .withEnvironment(mapOf("USERPROFILE" to fakeHome.toString(), "HOME" to fakeHome.toString()))
            .withTimeout(Duration.ofMinutes(3))
            .withLogPrefix("claude-win")
            .withDescription("claude --plugin-dir <probe> -p (Windows launch + hook matrix)")
            .startProcess()
            .awaitForProcessFinish()

        logLines = if (Files.exists(logFile)) logFile.readText().lines() else emptyList()
        println("[probe] claude exit=${result.exitCode}")
        println("[probe] portable-probe.log:\n${if (logLines.isEmpty()) "(empty / not created)" else logLines.joinToString("\n")}")
    }

    // ---- MCP server (stdio) ------------------------------------------------------------------------

    @Test
    fun `MCP - extensionless command resolves to the cmd sibling (script-only launcher)`() {
        assertTrue(logLines.isNotEmpty()) { "probe log empty — Claude never spawned the MCP server." }
        assertTrue(logLines.any { it.contains("tag=mcp-fullpath via=cmd") }) {
            "Extensionless MCP command \${CLAUDE_PLUGIN_ROOT}/bin/probe should resolve to probe.cmd via " +
                "cross-spawn/PATHEXT. Log:\n${logLines.joinToString("\n")}"
        }
        assertTrue(logLines.none { it.contains("tag=mcp-fullpath via=sh") }) {
            "The extensionless Unix shell script ran on Windows — cross-spawn should only try PATHEXT " +
                "candidates. Log:\n${logLines.joinToString("\n")}"
        }
    }

    @Test
    fun `MCP - bare command name does not resolve (plugin bin not on subprocess PATH)`() {
        assertTrue(!fired("mcp-bare")) {
            "Bare 'probe' resolved — plugin bin/ must not be on the MCP subprocess PATH. Log:\n${logLines.joinToString("\n")}"
        }
    }

    // ---- Hook matrix: every candidate form, one test each ------------------------------------------

    @Test
    fun `hook exec-form extensionless does NOT launch on Windows`() {
        // Exec form spawns "command" directly as one executable; an extensionless path isn't runnable.
        assertTrue(!fired("hook-exec-extensionless")) { hookLog() }
    }

    @Test
    fun `hook exec-form dot-cmd does NOT launch on Windows`() {
        // Exec form can't spawn a .cmd directly (no shell, not cross-spawn).
        assertTrue(!fired("hook-exec-cmd")) { hookLog() }
    }

    @Test
    fun `hook exec-form via cmd slash c launches`() {
        // cmd.exe IS a real executable, so exec form can spawn it; it then runs the .cmd. Windows-only
        // (breaks on Unix — no cmd.exe), but a valid Windows exec-form workaround.
        assertTrue(fired("hook-exec-cmdc")) { hookLog() }
    }

    @Test
    fun `hook shell-form default (Git Bash) launches`() {
        // No "args" => Claude runs the command string through a shell (Git Bash by default on Windows).
        assertTrue(fired("hook-shell-default")) { hookLog() }
    }

    @Test
    fun `hook shell-form powershell launches`() {
        // No "args" + "shell":"powershell" => PowerShell (always present on Windows) runs the command.
        assertTrue(fired("hook-shell-powershell")) { hookLog() }
    }

    @Test
    fun `hook shell-form pointing at an sh script (the devrig hook pattern) launches`() {
        // devrig's own hooks are shell-form paths to #!/bin/sh scripts (no args). On Windows that runs
        // via Git Bash. This is THE test for whether devrig's shipped hooks work on Windows.
        assertTrue(fired("hook-devrig-pattern")) { hookLog() }
    }

    private fun hookLog() =
        "Hook launch outcome differs from expectation. Full probe log:\n${logLines.joinToString("\n")}"

    // ---- helpers -----------------------------------------------------------------------------------

    private fun downloadWindowsClaude(cacheDir: Path): Path {
        val base = "https://downloads.claude.ai/claude-code-releases"
        val http = HttpClient.newHttpClient()
        val version = http.send(
            HttpRequest.newBuilder(URI.create("$base/latest")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body().trim()
        require(version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Unexpected latest version: '$version'" }
        val exe = cacheDir.resolve("claude-$version-win32-x64.exe")
        if (Files.exists(exe) && Files.size(exe) > 50_000_000) return exe
        val url = "$base/$version/win32-x64/claude.exe"
        Files.deleteIfExists(exe)
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(exe))
        require(Files.exists(exe) && Files.size(exe) > 50_000_000) { "Bad claude.exe from $url" }
        return exe
    }

    private fun writeProbePlugin(root: Path): Path {
        root.resolve(".claude-plugin").createDirectories()
        root.resolve("hooks").createDirectories()
        root.resolve("bin").createDirectories()

        root.resolve(".claude-plugin/plugin.json").writeText(
            """{ "name": "windows-probe", "description": "launch probe", "version": "0.0.1", "author": {"name":"probe"} }""",
        )
        // MCP: extensionless full path (script-only resolution) + bare name (PATH resolution).
        root.resolve(".mcp.json").writeText(
            """
            {
              "mcpServers": {
                "probefull": { "type": "stdio", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/probe", "args": ["mcp-fullpath"] },
                "probebare": { "type": "stdio", "command": "probe", "args": ["mcp-bare"] }
              }
            }
            """.trimIndent(),
        )
        // Every candidate hook form, tagged so the probe log records which fire. See the per-form tests.
        root.resolve("hooks/hooks.json").writeText(
            """
            {
              "hooks": {
                "SessionStart": [ { "hooks": [
                  { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/probe", "args": ["hook-exec-extensionless"] },
                  { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/probe.cmd", "args": ["hook-exec-cmd"] },
                  { "type": "command", "command": "cmd", "args": ["/c", "${'$'}{CLAUDE_PLUGIN_ROOT}\\bin\\probe.cmd", "hook-exec-cmdc"] },
                  { "type": "command", "command": "printf 'LAUNCHED tag=hook-shell-default via=shell\\n' >> \"${'$'}HOME/portable-probe.log\"" },
                  { "type": "command", "command": "& \"${'$'}{CLAUDE_PLUGIN_ROOT}\\bin\\probe.cmd\" hook-shell-powershell", "shell": "powershell" },
                  { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/devrig-style-hook" }
                ] } ]
              }
            }
            """.trimIndent(),
        )
        // Windows probe (batch): logs tag + via=cmd. `%~1` strips cross-spawn's argument quoting.
        root.resolve("bin/probe.cmd").writeText(
            "@echo off\r\n" +
                "set \"TAG=%~1\"\r\n" +
                ">>\"%USERPROFILE%\\portable-probe.log\" echo LAUNCHED tag=%TAG% via=cmd\r\n" +
                "exit /b 0\r\n",
        )
        // Unix probe (sh): logs tag + via=sh. Negative control for MCP on Windows (must NOT run there).
        root.resolve("bin/probe").writeText(
            "#!/bin/sh\n" +
                "echo \"LAUNCHED tag=\$1 via=sh\" >> \"\$HOME/portable-probe.log\"\n",
        )
        root.resolve("bin/probe").toFile().setExecutable(true)
        // The devrig-style hook: a #!/bin/sh script referenced shell-form (no args) — exactly how
        // check-devrig / devrig-progress / devrig-recover are written.
        root.resolve("bin/devrig-style-hook").writeText(
            "#!/bin/sh\n" +
                "echo \"LAUNCHED tag=hook-devrig-pattern via=script\" >> \"\$HOME/portable-probe.log\"\n",
        )
        root.resolve("bin/devrig-style-hook").toFile().setExecutable(true)
        return root
    }
}
