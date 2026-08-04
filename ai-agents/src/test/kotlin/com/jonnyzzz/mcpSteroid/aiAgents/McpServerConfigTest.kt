/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerConfigTest {

    @Test
    fun `stdioMcpServersJson emits a pretty-printed mcpServers entry with command and args`() {
        val json = stdioMcpServersJson(StdioMcpCommand("/home/user/.mcp-steroid/bin/devrig", listOf("mcp")))

        assertTrue(json.lines().size > 1, "must be pretty-printed (multi-line), got: $json")
        val server = Json.parseToJsonElement(json)
            .jsonObject.getValue("mcpServers")
            .jsonObject.getValue(DEFAULT_SERVER_NAME)
            .jsonObject
        assertEquals("/home/user/.mcp-steroid/bin/devrig", server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("mcp"), server.getValue("args").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `stdioMcpServersJson escapes Windows backslash paths`() {
        // The Windows launcher invocation: cmd.exe /d /c "<quoted .cmd path> mcp". Both the backslashes
        // and the embedded quotes MUST be JSON-escaped — hand-concatenation would emit invalid JSON.
        val line = "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp"
        val json = stdioMcpServersJson(StdioMcpCommand("cmd.exe", listOf("/d", "/c", line)))

        assertTrue(
            json.contains("C:\\\\Users\\\\First Last"),
            "backslashes must appear JSON-escaped in the output, got: $json",
        )
        // Round-trip: the emitted JSON must decode back to the exact input command line.
        val server = Json.parseToJsonElement(json)
            .jsonObject.getValue("mcpServers")
            .jsonObject.getValue(DEFAULT_SERVER_NAME)
            .jsonObject
        assertEquals("cmd.exe", server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("/d", "/c", line), server.getValue("args").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `devrigStdioMcpCommand execs the launcher directly on POSIX`() {
        val command = devrigStdioMcpCommand("/home/user/.mcp-steroid/bin/devrig", windows = false)

        assertEquals("/home/user/.mcp-steroid/bin/devrig", command.command)
        assertEquals(listOf("mcp"), command.args)
    }

    @Test
    fun `devrigStdioMcpCommand wraps the cmd launcher and quotes a path with spaces`() {
        // A .cmd is not executable as a process image, so it goes through cmd.exe; /d skips AutoRun.
        // cmd.exe parses everything after /c as ONE command line, so an unquoted "First Last" would split.
        val command = devrigStdioMcpCommand("C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd", windows = true)

        assertEquals("cmd.exe", command.command)
        assertEquals(
            listOf("/d", "/c", "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp"),
            command.args,
        )
    }

    @Test
    fun `stdioMcpServersJson honors a custom server name`() {
        val json = stdioMcpServersJson(StdioMcpCommand("/opt/devrig", listOf("mcp")), serverName = "custom-name")
        val servers = Json.parseToJsonElement(json).jsonObject.getValue("mcpServers").jsonObject
        assertEquals(setOf("custom-name"), servers.keys)
    }

    // Display policy for user-visible devrig paths: the REAL absolute home (never `~`), OS-native
    // separators — so what the settings page shows equals what its copy buttons put on the clipboard.
    // POSIX and Windows are pinned as explicit methods (no parameterized tests in this repo).

    @Test
    fun `devrigHomeDisplayPath renders the real home on POSIX`() {
        assertEquals("/home/user/.mcp-steroid", devrigHomeDisplayPath("/home/user", windows = false))
    }

    @Test
    fun `devrigHomeDisplayPath renders the real home with backslashes on Windows`() {
        assertEquals("C:\\Users\\me\\.mcp-steroid", devrigHomeDisplayPath("C:\\Users\\me", windows = true))
    }

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
        assertEquals(
            "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp",
            devrigMcpCommandLine("C:\\Users\\First Last", windows = true),
        )
    }
}
