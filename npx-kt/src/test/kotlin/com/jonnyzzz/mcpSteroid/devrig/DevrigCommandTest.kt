/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class DevrigCommandTest {
    @Test
    fun `command selects top level commands`() {
        assertIs<DevrigCommand.DevrigCommandHelp>(command())
        assertTrue(assertIs<DevrigCommand.DevrigCommandHelp>(command("--debug")).debug)
        assertIs<DevrigCommand.DevrigCommandHelp>(command("help"))
        assertIs<DevrigCommand.DevrigCommandVersion>(command("version"))
        assertIs<DevrigCommand.DevrigCommandVersion>(command("--version"))
        assertIs<DevrigCommand.MCP>(command("mcp"))
        assertIs<DevrigCommand.MCP>(command("mpc"))
        assertIs<DevrigCommand.DevrigCommandBackend>(command("backend"))
        assertIs<DevrigCommand.DevrigCommandProject>(command("project"))
        assertEquals(AiAgentCli.CLAUDE, assertIs<DevrigCommand.DevrigCommandInstall>(command("install", "claude")).agent)
        assertEquals(AiAgentCli.CODEX, assertIs<DevrigCommand.DevrigCommandInstall>(command("install", "codex")).agent)
        assertEquals(AiAgentCli.GEMINI, assertIs<DevrigCommand.DevrigCommandInstall>(command("install", "gemini")).agent)
    }

    @Test
    fun `command keeps command specific arguments on the subclass`() {
        val backend = assertIs<DevrigCommand.DevrigCommandBackend>(command("--json", "backend"))
        assertTrue(backend.json)

        val download = assertIs<DevrigCommand.DevrigCommandBackendDownload>(command("backend", "download", "idea-community", "--version", "2025.3", "--json"))
        assertEquals("idea-community", download.id)
        assertEquals("2025.3", download.version)
        assertTrue(download.json)

        val interspersed = assertIs<DevrigCommand.DevrigCommandBackendDownload>(command("backend", "--json", "download", "idea-community"))
        assertEquals("idea-community", interspersed.id)
        assertTrue(interspersed.json)

        val project = assertIs<DevrigCommand.DevrigCommandProject>(command("project", "--json"))
        assertTrue(project.json)

        val install = assertIs<DevrigCommand.DevrigCommandInstall>(command("--debug", "install", "claude", "--json"))
        assertEquals(AiAgentCli.CLAUDE, install.agent)
        assertTrue(install.debug)
        assertTrue(install.json)
    }

    @Test
    fun `mcp is the canonical spelling and mpc stays a working hidden alias`() {
        // Both spellings select the same MCP command (issue #85). `mcp` is the advertised,
        // visible primary; `mpc` is the legacy mis-spelling kept alive — registered hidden — so
        // existing agent registrations launching `devrig mpc` keep working.
        assertIs<DevrigCommand.MCP>(command("mcp"))
        assertIs<DevrigCommand.MCP>(command("mpc"))

        // generic switches resolve identically for both spellings
        assertTrue(assertIs<DevrigCommand.MCP>(command("--json", "mcp")).json)
        assertTrue(assertIs<DevrigCommand.MCP>(command("mcp", "--debug")).debug)
        assertIs<DevrigCommand.DevrigCommandHelp>(command("mcp", "--help"))

        // The hidden alias must never be advertised: clikt's error help for an unknown subcommand
        // must not leak `mpc`. (The user-facing help banner's mcp-yes/mpc-no contract is enforced
        // in DevrigCommandOutputTest.) The canonical `mcp` is the only spelling users should see.
        val parseError = assertIs<DevrigCommand.DevrigCommandParseError>(command("totally-unknown-subcommand"))
        assertFalse(parseError.text.contains("mpc"), "error help must not advertise the hidden mpc alias; got:\n${parseError.text}")
    }

    @Test
    fun `generic switches are accepted at every command level`() {
        assertTrue(assertIs<DevrigCommand.MCP>(command("--json", "mpc")).json)
        assertTrue(assertIs<DevrigCommand.MCP>(command("mpc", "--json")).json)
        assertIs<DevrigCommand.DevrigCommandHelp>(command("mpc", "--help"))

        val download = assertIs<DevrigCommand.DevrigCommandBackendDownload>(
            command("--debug", "backend", "--json", "download", "idea-community"),
        )
        assertTrue(download.debug)
        assertTrue(download.json)
        assertEquals("idea-community", download.id)
    }

    @Test
    fun `root version and backend version stay scoped`() {
        assertIs<DevrigCommand.DevrigCommandVersion>(command("--version"))

        val download = assertIs<DevrigCommand.DevrigCommandBackendDownload>(
            command("backend", "download", "idea-community", "--version", "2025.3"),
        )
        assertEquals("2025.3", download.version)
    }

    @Test
    fun `unknown command returns parse error`() {
        assertIs<DevrigCommand.DevrigCommandParseError>(command("foo"))
        assertIs<DevrigCommand.DevrigCommandParseError>(command("--no-such"))
        assertIs<DevrigCommand.DevrigCommandParseError>(command("backend", "download", "idea-community", "extra"))
        assertIs<DevrigCommand.DevrigCommandParseError>(command("install", "other"))
    }

    @Test
    fun `removed home flag is not a command`() {
        assertIs<DevrigCommand.DevrigCommandParseError>(command("--home", "/tmp/devrig-home"))
    }

    private fun command(vararg args: String): DevrigCommand =
        parseDevrigCommand(args.toList().toTypedArray())
}
