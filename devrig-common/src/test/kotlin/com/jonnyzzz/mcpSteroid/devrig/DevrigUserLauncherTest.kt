/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals

class DevrigUserLauncherTest {

    @Test
    fun `the launcher path is per-OS under the shared bin dir`() {
        // No literal absolute expectations: path() runs toAbsolutePath(), which prepends a drive
        // letter to "/home/u/…" on a Windows JVM — assert the per-OS file name and the parent dir.
        val home = HomePaths(Path.of("/home/u/.mcp-steroid"))
        val posix = DevrigUserLauncher.path(home, windows = false)
        val windows = DevrigUserLauncher.path(home, windows = true)
        assertEquals("devrig", posix.fileName.toString())
        assertEquals("devrig.cmd", windows.fileName.toString())
        assertEquals(home.binDir.toAbsolutePath().normalize(), posix.parent)
        assertEquals(posix.parent, windows.parent)
    }

    @Test
    fun `invocation execs the launcher directly on POSIX with mcp as the default verb`() {
        val home = HomePaths(Path.of("/home/user/.mcp-steroid"))

        val command = DevrigUserLauncher.invocation(home, windows = false)

        assertEquals(DevrigUserLauncher.path(home, windows = false).toString(), command.command)
        assertEquals(listOf("mcp"), command.args)
    }

    @Test
    fun `invocation wraps the cmd launcher and quotes a path with spaces`() {
        // A .cmd is not executable as a process image, so it goes through cmd.exe; /d skips AutoRun.
        // cmd.exe parses everything after /c as ONE command line, so an unquoted "First Last" would split.
        // The launcher path is asserted via DevrigUserLauncher.path (pinned above) because a literal
        // C:\ shape cannot be rendered by java.nio.file.Path on a POSIX JVM.
        val home = HomePaths(Path.of("/Users/First Last/.mcp-steroid"))
        val launcher = DevrigUserLauncher.path(home, windows = true).toString()

        val command = DevrigUserLauncher.invocation(home, windows = true)

        assertEquals("cmd.exe", command.command)
        assertEquals(listOf("/d", "/c", "\"$launcher\" mcp"), command.args)
    }

    // Display policy for user-visible devrig paths: the REAL absolute home (never `~`), OS-native
    // separators — so what the settings page shows equals what its copy buttons put on the clipboard.
    // POSIX and Windows are pinned as explicit methods (no parameterized tests in this repo).

    @Test
    fun `devrigLauncherDisplayPath names the plain launcher on POSIX`() {
        assertEquals(
            "/home/user/.mcp-steroid/bin/devrig",
            devrigLauncherDisplayPath("/home/user", windows = false),
        )
    }

    @Test
    fun `devrigLauncherDisplayPath names the cmd shim with backslashes on Windows`() {
        assertEquals(
            "C:\\Users\\me\\.mcp-steroid\\bin\\devrig.cmd",
            devrigLauncherDisplayPath("C:\\Users\\me", windows = true),
        )
    }

    @Test
    fun `devrigLauncherDisplayPath normalizes a forward-slash Windows home and a trailing separator`() {
        assertEquals(
            "C:\\Users\\me\\.mcp-steroid\\bin\\devrig.cmd",
            devrigLauncherDisplayPath("C:/Users/me/", windows = true),
        )
    }

    @Test
    fun `devrigMcpCommandLine is the absolute launcher plus the mcp subcommand on POSIX`() {
        assertEquals(
            "/home/user/.mcp-steroid/bin/devrig mcp",
            devrigMcpCommandLine("/home/user", windows = false),
        )
    }

    @Test
    fun `devrigMcpCommandLine leaves a space-free Windows launcher unquoted`() {
        assertEquals(
            "C:\\Users\\me\\.mcp-steroid\\bin\\devrig.cmd mcp",
            devrigMcpCommandLine("C:\\Users\\me", windows = true),
        )
    }

    @Test
    fun `devrigMcpCommandLine quotes a launcher path containing a space`() {
        // An unquoted "C:\Users\First Last\..." splits into two arguments in every shell and client.
        // No PowerShell call operator: this line goes into an MCP client's command field, and the
        // client spawns the process from it directly — a leading '&' would be read as the program path.
        assertEquals(
            "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp",
            devrigMcpCommandLine("C:\\Users\\First Last", windows = true),
        )
    }

    // The per-agent registration command the IDE settings page displays: the absolute launcher plus
    // devrig's own canonical, idempotent 'install <agent>' verb (issue #399) — never a bare 'devrig',
    // which would silently depend on PATH.

    @Test
    fun `devrigInstallAgentCommandLine is the absolute launcher plus install claude on POSIX`() {
        assertEquals(
            "/home/user/.mcp-steroid/bin/devrig install claude",
            devrigInstallAgentCommandLine("/home/user", windows = false, agent = AiAgentCli.CLAUDE),
        )
    }

    @Test
    fun `devrigInstallAgentCommandLine names every agent by its CLI binary on POSIX`() {
        assertEquals(
            "/home/user/.mcp-steroid/bin/devrig install codex",
            devrigInstallAgentCommandLine("/home/user", windows = false, agent = AiAgentCli.CODEX),
        )
        assertEquals(
            "/home/user/.mcp-steroid/bin/devrig install gemini",
            devrigInstallAgentCommandLine("/home/user", windows = false, agent = AiAgentCli.GEMINI),
        )
    }

    @Test
    fun `devrigInstallAgentCommandLine names the cmd shim with backslashes on Windows`() {
        assertEquals(
            "C:\\Users\\me\\.mcp-steroid\\bin\\devrig.cmd install claude",
            devrigInstallAgentCommandLine("C:\\Users\\me", windows = true, agent = AiAgentCli.CLAUDE),
        )
    }

    @Test
    fun `devrigInstallAgentCommandLine renders a spaced launcher path in PowerShell call-operator form`() {
        // A terminal command, unlike the client-field mcp line above. PowerShell — the default shell of
        // Windows Terminal and the IDE terminal — parses a bare quoted leading token as a string
        // expression, so the copy-pasted command needs the call operator to actually run.
        assertEquals(
            "& \"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" install gemini",
            devrigInstallAgentCommandLine("C:\\Users\\First Last", windows = true, agent = AiAgentCli.GEMINI),
        )
    }
}
