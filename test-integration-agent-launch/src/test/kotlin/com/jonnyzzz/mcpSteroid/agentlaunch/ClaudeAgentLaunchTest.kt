package com.jonnyzzz.mcpSteroid.agentlaunch

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
 * Cross-OS validation of HOW Claude Code resolves and launches a plugin's stdio MCP `command` and its
 * hooks — the tested, script-only, minimal-dependency way to call an installed tool from a plugin
 * (jonnyzzz/mcp-steroid#253). Uses MOCK probe scripts (no real devrig).
 *
 * The SAME test runs on Windows AND Linux (the Gradle task is gated `enabled = isWindows || isLinux`;
 * skipped on macOS). Each test asserts the per-OS-correct outcome — an OS-conditional assertion, not a
 * skip. The probe writes `LAUNCHED tag=<tag> via=<cmd|sh|shell|script>` per launch, so the log records
 * both WHAT launched and THROUGH WHAT. No API key needed: MCP servers + hooks spawn during session
 * init, before the `-p` turn fails auth.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClaudeAgentLaunchTest {

    private enum class Host { WINDOWS, LINUX, OTHER }

    private val host: Host = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("win") -> Host.WINDOWS
            it.contains("linux") -> Host.LINUX
            else -> Host.OTHER
        }
    }

    private lateinit var logLines: List<String>

    private fun fired(tag: String) = logLines.any { it.contains("tag=$tag ") }

    @BeforeAll
    fun runClaudeOnceAndCaptureProbeLog() {
        val cacheDir = Path.of(System.getProperty("agent.launch.cache.dir"))
        cacheDir.createDirectories()

        val claudeBin = downloadClaude(cacheDir)
        val pluginDir = writeProbePlugin(cacheDir.resolve("probe-plugin"))

        val fakeHome = Files.createTempDirectory(cacheDir, "home").toAbsolutePath()
        val logFile = fakeHome.resolve("portable-probe.log")
        Files.deleteIfExists(logFile)

        val result = RunProcessRequest(
            args = listOf(
                claudeBin.absolutePathString(),
                "--plugin-dir", pluginDir.absolutePathString(),
                "-p", "reply with OK",
            ),
        )
            // HOME (Unix) + USERPROFILE (Windows) → isolated fake home; ProcessRunner inherits the rest.
            .withEnvironment(mapOf("USERPROFILE" to fakeHome.toString(), "HOME" to fakeHome.toString()))
            .withTimeout(Duration.ofMinutes(3))
            .withLogPrefix("claude-$host")
            .withDescription("claude --plugin-dir <probe> -p ($host launch + hook matrix)")
            .startProcess()
            .awaitForProcessFinish()

        logLines = if (Files.exists(logFile)) logFile.readText().lines() else emptyList()
        println("[probe] host=$host claude exit=${result.exitCode}")
        println("[probe] portable-probe.log:\n${if (logLines.isEmpty()) "(empty / not created)" else logLines.joinToString("\n")}")
    }

    // ---- MCP server (stdio) ------------------------------------------------------------------------

    @Test
    fun `MCP - extensionless command resolves per-OS (cmd on Windows, sh on Unix)`() {
        assertTrue(logLines.isNotEmpty()) { "probe log empty — Claude never spawned the MCP server." }
        assertTrue(fired("mcp-fullpath")) { "extensionless MCP command should launch. Log:\n${log()}" }
        when (host) {
            Host.WINDOWS -> {
                assertTrue(logLines.any { it.contains("tag=mcp-fullpath via=cmd") }) { "Windows: expected via=cmd. Log:\n${log()}" }
                assertTrue(logLines.none { it.contains("tag=mcp-fullpath via=sh") }) { "Windows: sh probe must NOT run. Log:\n${log()}" }
            }
            else -> assertTrue(logLines.any { it.contains("tag=mcp-fullpath via=sh") }) { "Unix: expected via=sh. Log:\n${log()}" }
        }
    }

    @Test
    fun `MCP - bare command name does not resolve on any OS`() {
        assertTrue(!fired("mcp-bare")) { "bare 'probe' resolved — plugin bin/ must not be on PATH. Log:\n${log()}" }
    }

    // ---- Hook matrix (per-OS expected outcomes) ----------------------------------------------------

    @Test
    fun `hook exec-form extensionless - fires on Unix, NOT on Windows`() {
        // exec-form spawns "command" directly: on Unix an extensionless +x script runs; on Windows an
        // extensionless path is not runnable (no PATHEXT/cross-spawn for exec-form hooks).
        if (host == Host.WINDOWS) assertTrue(!fired("hook-exec-extensionless")) { log() }
        else assertTrue(fired("hook-exec-extensionless")) { log() }
    }

    @Test
    fun `hook exec-form dot-cmd - does NOT fire on any OS`() {
        // .cmd is not a valid Unix executable (ENOEXEC) and can't be direct-spawned on Windows.
        assertTrue(!fired("hook-exec-cmd")) { log() }
    }

    @Test
    fun `hook exec-form cmd slash c - fires on Windows only`() {
        // cmd.exe is a real exe (Windows); on Unix there is no cmd.
        if (host == Host.WINDOWS) assertTrue(fired("hook-exec-cmdc")) { log() }
        else assertTrue(!fired("hook-exec-cmdc")) { log() }
    }

    @Test
    fun `hook shell-form default shell - fires on both`() {
        // No "args" => run via the default shell: sh on Unix, Git Bash on Windows.
        assertTrue(fired("hook-shell-default")) { log() }
    }

    @Test
    fun `hook shell-form powershell - fires on Windows (Linux depends on pwsh, recorded only)`() {
        if (host == Host.WINDOWS) assertTrue(fired("hook-shell-powershell")) { log() }
        else println("[probe] Linux shell:powershell hook fired=${fired("hook-shell-powershell")} (needs pwsh on PATH)")
    }

    @Test
    fun `hook shell-form pointing at an sh script (the devrig hook pattern) - fires on both`() {
        // devrig's hooks are shell-form paths to #!/bin/sh scripts; runs natively on Unix and via Git
        // Bash on Windows. THE test for whether devrig's shipped hooks work cross-platform.
        assertTrue(fired("hook-devrig-pattern")) { log() }
    }

    private fun log() = logLines.joinToString("\n")

    // ---- helpers -----------------------------------------------------------------------------------

    private fun claudePlatform(): Pair<String, String> {
        val arch = System.getProperty("os.arch").lowercase()
        val a = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
        return when (host) {
            Host.WINDOWS -> "win32-$a" to "claude.exe"
            Host.LINUX -> "linux-$a" to "claude"
            Host.OTHER -> "darwin-$a" to "claude" // task is skipped on macOS anyway
        }
    }

    private fun downloadClaude(cacheDir: Path): Path {
        val base = "https://downloads.claude.ai/claude-code-releases"
        val http = HttpClient.newHttpClient()
        val version = http.send(
            HttpRequest.newBuilder(URI.create("$base/latest")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body().trim()
        require(version.matches(Regex("""\d+\.\d+\.\d+"""))) { "Unexpected latest version: '$version'" }
        val (platform, binName) = claudePlatform()
        val dest = cacheDir.resolve("claude-$version-$platform${if (binName.endsWith(".exe")) ".exe" else ""}")
        if (!(Files.exists(dest) && Files.size(dest) > 30_000_000)) {
            val url = "$base/$version/$platform/$binName"
            Files.deleteIfExists(dest)
            http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofFile(dest))
            require(Files.exists(dest) && Files.size(dest) > 30_000_000) { "Bad claude download from $url" }
        }
        if (host != Host.WINDOWS) dest.toFile().setExecutable(true)
        return dest
    }

    private fun writeProbePlugin(root: Path): Path {
        root.resolve(".claude-plugin").createDirectories()
        root.resolve("hooks").createDirectories()
        root.resolve("bin").createDirectories()

        root.resolve(".claude-plugin/plugin.json").writeText(
            """{ "name": "agent-launch-probe", "description": "launch probe", "version": "0.0.1", "author": {"name":"probe"} }""",
        )
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
        // Every candidate hook form, tagged. See the per-form tests for the per-OS expected outcome.
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
        // Windows probe (batch): tag + via=cmd. `%~1` strips cross-spawn's argument quoting.
        root.resolve("bin/probe.cmd").writeText(
            "@echo off\r\n" +
                "set \"TAG=%~1\"\r\n" +
                ">>\"%USERPROFILE%\\portable-probe.log\" echo LAUNCHED tag=%TAG% via=cmd\r\n" +
                "exit /b 0\r\n",
        )
        // Unix probe (sh): tag + via=sh. On Windows this is the MCP negative control (must NOT run).
        writeExecutableSh(
            root.resolve("bin/probe"),
            "#!/bin/sh\n" + "echo \"LAUNCHED tag=\$1 via=sh\" >> \"\$HOME/portable-probe.log\"\n",
        )
        // devrig-style hook: a #!/bin/sh script referenced shell-form (no args) — exactly how
        // check-devrig / devrig-progress / devrig-recover are written.
        writeExecutableSh(
            root.resolve("bin/devrig-style-hook"),
            "#!/bin/sh\n" + "echo \"LAUNCHED tag=hook-devrig-pattern via=script\" >> \"\$HOME/portable-probe.log\"\n",
        )
        return root
    }

    private fun writeExecutableSh(path: Path, content: String) {
        path.writeText(content)
        path.toFile().setExecutable(true)
    }
}
