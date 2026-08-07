/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DevrigCliHelpTest {
    @TempDir
    lateinit var testHome: Path

    @Test
    fun `root help is generated from the command tree`() {
        val result = runHelp("--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        val visibleTools = devrigCliTools().filterNot { it.cli.hidden }
        for (command in listOf("backend", "install", "mcp", "tools", "help", "version") + visibleTools.map { it.cli.name }) {
            assertTrue(result.stdout.contains(command), "missing $command in:\n${result.stdout}")
        }
        assertTrue(!result.stdout.contains("mpc"), result.stdout)
        assertTrue(result.stdout.contains("--json"), result.stdout)
    }

    @Test
    fun `the tools reference advertises every declared command alias`() {
        // The alias notes moved out of root help together with the MCP-tools reference; `devrig tools`
        // is now the surface that must advertise them.
        val result = runHelp("tools")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        for (tool in devrigCliTools().filterNot { it.cli.hidden }.filter { it.cli.aliases.isNotEmpty() }) {
            val aliasNote = if (tool.cli.aliases.size == 1) {
                "(alias: ${tool.cli.aliases.single()})"
            } else {
                "(aliases: ${tool.cli.aliases.joinToString(", ")})"
            }
            assertTrue(aliasNote in result.stdout, "missing $aliasNote in:\n${result.stdout}")
        }
    }

    @Test
    fun `backend help describes backend commands only`() {
        val result = runHelp("backend", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig backend"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        for (command in listOf("download", "provision", "start", "stop")) {
            assertTrue(result.stdout.contains(command), "missing $command in:\n${result.stdout}")
        }
        assertTrue(!result.stdout.contains("install claude"), result.stdout)
    }

    @Test
    fun `install help exposes each target as a real subcommand`() {
        val result = runHelp("install", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig install"), result.stdout)
        assertTrue(result.stdout.contains("Commands:"), result.stdout)
        for (target in listOf("claude", "codex", "gemini", "config", "devrig", "plugin")) {
            assertTrue(result.stdout.contains(target), "missing $target in:\n${result.stdout}")
        }
    }

    @Test
    fun `nested action help is specific and complete`() {
        val result = runHelp("backend", "download", "--help")

        assertEquals(0, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue(result.stdout.contains("Usage: devrig backend download"), result.stdout)
        assertTrue(result.stdout.contains("<id>"), result.stdout)
        assertTrue(result.stdout.contains("--version"), result.stdout)
        assertTrue(result.stdout.contains("--json"), result.stdout)
    }

    @Test
    fun `missing values with json produce one canonical structured help error`() {
        val result = runHelp("execute_code", "--json")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue('\u001B' !in result.stdout, "JSON must not contain ANSI escapes: ${result.stdout}")
        val envelope = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("execute_code", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
        val message = envelope.getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content
        for (expected in listOf(
            "Usage: devrig execute_code",
            "missing --project_name",
            "Pass --code-file=<path>",
            "missing --task_id",
            "missing --reason",
        )) {
            assertTrue(expected in message, "missing '$expected' in:\n$message")
        }
    }

    @Test
    fun `a json flag swallowed as a value still produces one complete structured help error`() {
        val result = runHelp("execute_code", "--task_id", "--json")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        assertTrue('\u001B' !in result.stdout, "JSON must not contain ANSI escapes: ${result.stdout}")
        val envelope = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("execute_code", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
        val message = envelope.getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content
        for (expected in listOf(
            "'--json' is a devrig flag, not a value",
            "missing --project_name",
            "Pass --code-file=<path>",
            "missing --reason",
        )) {
            assertTrue(expected in message, "missing '$expected' in:\n$message")
        }
    }

    @Test
    fun `a help flag swallowed as a value produces focused human guidance`() {
        val result = runHelp("execute_code", "--task_id", "--help")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stdout.isEmpty(), result.stdout)
        for (expected in listOf(
            "Usage: devrig execute_code",
            "'--help' is a devrig flag, not a value",
            "missing --project_name",
            "Pass --code-file=<path>",
            "missing --reason",
        )) {
            assertTrue(expected in result.stderr, "missing '$expected' in:\n${result.stderr}")
        }
    }

    @Test
    fun `nested json parse errors identify the full command path`() {
        val result = runHelp("backend", "download", "idea-community", "--version", "--json")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.isEmpty(), result.stderr)
        val envelope = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("backend download", envelope.getValue("command").jsonPrimitive.content)
        assertEquals(true, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `json parse errors through project aliases use canonical list_projects identity`() {
        for (alias in listOf("projects", "project")) {
            val result = runHelp(alias, "--json", "--bogus")
            assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
            assertTrue(result.stderr.isEmpty(), result.stderr)
            val envelope = Json.parseToJsonElement(result.stdout).jsonObject
            assertEquals("list_projects", envelope.getValue("command").jsonPrimitive.content)
            assertEquals(true, envelope.getValue("isError").jsonPrimitive.content.toBoolean())
        }
    }

    @Test
    fun `json-like option values do not switch missing help to JSON presentation`() {
        val result = runHelp("execute_code", "--reason=--json")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stdout.isEmpty(), result.stdout)
        assertTrue("Usage: devrig execute_code" in result.stderr, result.stderr)
    }

    @Test
    fun `json token after end of options does not switch missing help to JSON presentation`() {
        val result = runHelp("execute_code", "--", "--json")

        assertEquals(DEVRIG_USAGE_EXIT_CODE, result.exitCode)
        assertTrue(result.stdout.isEmpty(), result.stdout)
        assertTrue("Usage: devrig execute_code" in result.stderr, result.stderr)
    }

    private fun runHelp(vararg args: String): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val lifetime = CloseableStackHost()
        val originalErr = System.err
        return try {
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            val exitCode = runBlocking {
                val services = DevrigServices(
                    lifetime = lifetime,
                    homePaths = HomePaths(testHome).also { it.mkdirsAll() },
                    mcpStdin = ByteArrayInputStream(ByteArray(0)),
                    mcpStdout = PrintStream(stdout, true, Charsets.UTF_8),
                )
                parseDevrigCommand(args.toList().toTypedArray()).execute(services)
            }
            CliResult(
                exitCode = exitCode,
                stdout = stdout.toString(Charsets.UTF_8).replace("\r\n", "\n"),
                stderr = stderr.toString(Charsets.UTF_8).replace("\r\n", "\n"),
            )
        } finally {
            System.setErr(originalErr)
            lifetime.closeAllStacks()
        }
    }

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )
}
