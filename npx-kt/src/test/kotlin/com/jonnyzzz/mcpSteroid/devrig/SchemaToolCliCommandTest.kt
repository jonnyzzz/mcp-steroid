/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.subcommands
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Pins the generic registration of schema-driven tool commands (issue #284): the eight
 * visible [devrigCliTools] specs each produce exactly one canonical `SchemaToolCliCommand` in factory
 * order, hidden specs produce none, an unknown flag is a USAGE (exit 64) parse error, and every root
 * token (canonical names + aliases + the fixed non-tool/nested verbs) is unique so nothing is registered
 * twice. Also pins that the parse-error command-name recovery set is derived from that same metadata.
 */
class SchemaToolCliCommandTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    /** A root that registers the generated tool commands, mirroring how they slot under `devrig`. */
    private class GeneratedToolsRoot(
        selected: SelectedDevrigCommand,
        tools: List<CliToolSpec> = devrigCliTools(),
    ) : DevrigCliktCommand("devrig", selected, parent = null, invokeWithoutSubcommand = true) {
        val generated: List<SchemaToolCliCommand> = schemaToolCliCommands(selected, this, tools)

        init {
            subcommands(generated)
        }

        override fun run() = Unit
    }

    /** A hidden tool spec: its `cli.hidden` must keep it out of the generated command set. */
    private class HiddenProbeTool : McpToolBase() {
        override val name = "steroid_hidden_probe"
        override val description = "hidden probe"
        override val cliSynopsis = "hidden probe tool"
        override val cliHidden = true
        override suspend fun call(context: ToolCallContext): ToolCallResult = error("must not be called")
    }

    private val expectedCanonicalCommands = listOf(
        "list_projects", "list_windows", "execute_code", "execute_feedback",
        "take_screenshot", "input", "fetch_resource", "open_project",
    )

    @Test
    fun `all eight visible specs register exactly one canonical command in factory order`() {
        val root = GeneratedToolsRoot(SelectedDevrigCommand())
        assertEquals(expectedCanonicalCommands, root.generated.map { it.commandName })
        assertEquals(8, root.generated.size, "one command per visible spec, no duplicates")
    }

    @Test
    fun `hidden specs do not register`() {
        val visible = devrigCliTools().filterNot { it.cli.hidden }
        val withHidden = devrigCliTools() + HiddenProbeTool()
        val generated = schemaToolCliCommands(SelectedDevrigCommand(), parent = null, tools = withHidden)
        assertEquals(visible.map { it.cli.name }, generated.map { it.commandName })
        assertFalse(generated.any { it.commandName == "hidden_probe" }, "a hidden spec must not register a command")
    }

    @Test
    fun `an unknown flag is a USAGE parse error exiting 64`() {
        val selected = SelectedDevrigCommand()
        val command = parseDevrigCommandWithRoot(GeneratedToolsRoot(selected), selected, arrayOf("list_windows", "--nope"))
        assertTrue(command is DevrigCommand.DevrigCommandParseError, "expected a parse error, got $command")
        command as DevrigCommand.DevrigCommandParseError
        assertEquals("list_windows", command.commandName)
        val run = runCliCommand(homePaths()) { runCli(command) }
        assertEquals(CliExit.USAGE, run.exit)
    }

    @Test
    fun `every root token is unique - no duplicate registration`() {
        val allTokens = FIXED_DEVRIG_SUBCOMMAND_NAMES.toList() +
            devrigCliTools().flatMap { listOf(it.cli.name) + it.cli.aliases }
        val duplicates = allTokens.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "duplicate root token(s): $duplicates")
    }

    @Test
    fun `the prompt alias is not auto-registered as a canonical grammar command`() {
        val root = GeneratedToolsRoot(SelectedDevrigCommand())
        assertFalse("prompt" in root.generated.map { it.commandName }, "prompt is an alias, not a canonical command")
        assertTrue("fetch_resource" in root.generated.map { it.commandName }, "fetch_resource is the canonical command")
        // The alias still travels on the tool metadata so recovery/help can see it.
        assertTrue("prompt" in devrigCliTools().single { it.cli.name == "fetch_resource" }.cli.aliases)
    }

    @Test
    fun `DEVRIG_SUBCOMMAND_NAMES is the exact set of recognised root tokens`() {
        // A frozen golden of every token `recoverCommandName` must recognise: the fixed non-tool/nested
        // verbs plus each tool's canonical name and alias. Pinned as a literal (not re-derived from the
        // same expression that defines it) so renaming, dropping, or adding a tool token is caught here.
        assertEquals(
            setOf(
                "mcp", "mpc", "backend", "project", "install", "help", "version",
                "download", "start", "stop", "provision",
                "list_projects", "list_windows", "execute_code", "execute_feedback",
                "take_screenshot", "input", "open_project", "fetch_resource", "prompt",
            ),
            DEVRIG_SUBCOMMAND_NAMES,
        )
    }
}
