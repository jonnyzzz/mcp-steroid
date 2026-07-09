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
 * resolves and launches a plugin's stdio MCP `command`. This is the live counterpart to the static
 * analysis + Docker/Linux findings in jonnyzzz/mcp-steroid#253.
 *
 * The suite is Windows-only and is gated OFF on macOS/Linux at the Gradle task level
 * (`test-integration-windows/build.gradle.kts` → `tasks.test { enabled = isWindows }`), per the repo
 * rule that the only acceptable skip is a structurally-incompatible suite disabled at the task level.
 * There is deliberately NO runtime `assumeTrue` / `@EnabledOnOs` skip here.
 *
 * What it proves (script-only launcher design, no native binary):
 *  - An **extensionless** MCP `command` (`${CLAUDE_PLUGIN_ROOT}/bin/probe`) is resolved by Claude's
 *    bundled cross-spawn via PATHEXT to the sibling `probe.cmd` and run through `cmd.exe` — so a pure
 *    `.cmd` script can serve as the launcher. (Tag `mcp-fullpath`.)
 *  - A **bare** command name (`probe`) does NOT resolve — the plugin `bin/` is not on the MCP
 *    subprocess PATH (Option B is dead; matches the Linux finding). (Tag `mcp-bare` must be absent.)
 *  - The extensionless Unix `probe` shell script is NEVER executed on Windows (cross-spawn skips the
 *    exact no-extension name and only tries PATHEXT candidates). (Tag `UNIX-*` must be absent.)
 *  - Whether a `SessionStart` hook (exec-form) resolves the same way. (Tag `hook-sessionstart`.)
 *
 * No API key is needed: Claude spawns plugin MCP servers + hooks during session init, before the
 * `-p` turn fails auth. The probe just appends a line to `%USERPROFILE%\portable-probe.log` and exits.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeWindowsLaunchTest {

    private lateinit var logLines: List<String>

    @BeforeAll
    fun runClaudeOnceAndCaptureProbeLog() {
        val cacheDir = Path.of(System.getProperty("windows.test.cache.dir"))
        cacheDir.createDirectories()

        val claudeExe = downloadWindowsClaude(cacheDir)
        val pluginDir = writeProbePlugin(cacheDir.resolve("probe-plugin"))

        // Isolated fake home so (a) the probe log is easy to find and (b) Claude has no real auth/config.
        val fakeHome = Files.createTempDirectory(cacheDir, "home").toAbsolutePath()
        val logFile = fakeHome.resolve("portable-probe.log")
        Files.deleteIfExists(logFile)

        // Drive Claude through the repo's shared process-runner util (RunProcessRequest → ProcessRunner):
        // consistent [claude-win] logging, timeout + destroyForcibly, captured stdout/stderr.
        val result = RunProcessRequest(
            args = listOf(
                claudeExe.absolutePathString(),
                "--plugin-dir", pluginDir.absolutePathString(),
                "-p", "reply with OK",
            ),
        )
            // Override only HOME/USERPROFILE (isolated fake home) — ProcessRunner inherits the rest of
            // the environment (PATH, SystemRoot, …) which Claude needs.
            .withEnvironment(mapOf("USERPROFILE" to fakeHome.toString(), "HOME" to fakeHome.toString()))
            // Claude fails auth quickly, but MCP servers + hooks spawn during init first. Bound it anyway.
            .withTimeout(Duration.ofMinutes(3))
            .withLogPrefix("claude-win")
            .withDescription("claude --plugin-dir <probe> -p (Windows launch probe)")
            .startProcess()
            .awaitForProcessFinish()

        logLines = if (Files.exists(logFile)) logFile.readText().lines() else emptyList()
        println("[probe] claude exit=${result.exitCode}")
        println("[probe] portable-probe.log:\n${if (logLines.isEmpty()) "(empty / not created)" else logLines.joinToString("\n")}")
    }

    @Test
    fun `extensionless MCP command resolves to the cmd sibling (script-only launcher)`() {
        assertTrue(logLines.isNotEmpty()) {
            "probe log is empty — Claude never spawned the MCP server. See claude-stderr.txt in the cache dir."
        }
        assertTrue(logLines.any { it.contains("tag=mcp-fullpath") }) {
            "Expected the extensionless MCP command \${CLAUDE_PLUGIN_ROOT}/bin/probe to resolve to probe.cmd " +
                "and run (tag=mcp-fullpath). Log:\n${logLines.joinToString("\n")}"
        }
        assertTrue(logLines.none { it.contains("UNIX-SHOULD-NOT-RUN") }) {
            "The extensionless Unix shell script bin/probe was executed on Windows — cross-spawn should " +
                "skip the exact no-extension name and only try PATHEXT candidates. Log:\n${logLines.joinToString("\n")}"
        }
    }

    @Test
    fun `bare command name does not resolve (plugin bin not on MCP subprocess PATH)`() {
        assertTrue(logLines.none { it.contains("tag=mcp-bare") }) {
            "The bare command name 'probe' resolved and launched — this would mean the plugin bin/ IS on " +
                "the MCP subprocess PATH on Windows, contradicting the Linux finding. Log:\n${logLines.joinToString("\n")}"
        }
    }

    @Test
    fun `SessionStart hook (exec-form) launches`() {
        assertTrue(logLines.any { it.contains("tag=hook-sessionstart") }) {
            "The exec-form SessionStart hook (command + args, extensionless) did not launch on Windows. " +
                "This reveals hooks resolve differently from MCP (possibly Bun-native spawn, not cross-spawn). " +
                "Log:\n${logLines.joinToString("\n")}"
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** Download the official win32-x64 Claude Code binary (latest), cached by version. */
    private fun downloadWindowsClaude(cacheDir: Path): Path {
        val base = "https://downloads.claude.ai/claude-code-releases"
        val http = HttpClient.newHttpClient()
        val version = http.send(
            HttpRequest.newBuilder(URI.create("$base/latest")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body().trim()
        require(version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Unexpected latest version from $base/latest: '$version'" }

        val exe = cacheDir.resolve("claude-$version-win32-x64.exe")
        if (Files.exists(exe) && Files.size(exe) > 50_000_000) return exe

        val url = "$base/$version/win32-x64/claude.exe"
        Files.deleteIfExists(exe)
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(exe),
        )
        require(Files.exists(exe) && Files.size(exe) > 50_000_000) {
            "Downloaded claude.exe looks wrong (size=${runCatching { Files.size(exe) }.getOrDefault(-1)}) from $url"
        }
        return exe
    }

    /** Write a throwaway plugin whose "servers"/hooks are the launch probe. Returns the plugin dir. */
    private fun writeProbePlugin(root: Path): Path {
        root.resolve(".claude-plugin").createDirectories()
        root.resolve("hooks").createDirectories()
        root.resolve("bin").createDirectories()

        root.resolve(".claude-plugin/plugin.json").writeText(
            """{ "name": "windows-probe", "description": "launch probe", "version": "0.0.1", "author": {"name":"probe"} }""",
        )
        // Two MCP servers: extensionless full path (script-only resolution) + bare name (PATH resolution).
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
        // Exec-form SessionStart hook (command + args, extensionless).
        root.resolve("hooks/hooks.json").writeText(
            """
            {
              "hooks": {
                "SessionStart": [ { "hooks": [
                  { "type": "command", "command": "${'$'}{CLAUDE_PLUGIN_ROOT}/bin/probe", "args": ["hook-sessionstart"] }
                ] } ]
              }
            }
            """.trimIndent(),
        )
        // The Windows probe: a pure batch script that records how it was launched, then exits.
        // (No JSON-RPC handshake — the launch + log line is what we assert; Claude marking the server
        //  "failed" afterwards is irrelevant.)
        root.resolve("bin/probe.cmd").writeText(
            "@echo off\r\n" +
                ">>\"%USERPROFILE%\\portable-probe.log\" echo LAUNCHED tag=%1 root=%CLAUDE_PLUGIN_ROOT%\r\n" +
                "exit /b 0\r\n",
        )
        // Negative control: the extensionless Unix shell script. On Windows, cross-spawn must NOT run
        // this (it resolves probe -> probe.cmd via PATHEXT). If it ever fires we see the UNIX tag.
        root.resolve("bin/probe").writeText(
            "#!/bin/sh\n" +
                "echo \"LAUNCHED tag=UNIX-SHOULD-NOT-RUN arg=\$1\" >> \"\$HOME/portable-probe.log\"\n",
        )
        root.resolve("bin/probe").toFile().setExecutable(true)
        return root
    }
}
