/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.ArgumentDelegate
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.OptionDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the two foundations of the schema-driven CLI (issue #284, Phase B2):
 *
 * 1. The parse/runtime boundary — a generated [SchemaToolCliCommand] parses `list_windows --json` into an
 *    inert [DevrigCommand.RunTool] and never resolves a handler, service, or backend. Runtime dispatch is
 *    a strictly later lifecycle phase (`DevrigServices.runCli`), so a value object is all parsing yields.
 * 2. The Clikt 4.4.0 mechanism the typed bindings (Task 2) will build on — options created in `init` and
 *    registered with `registerOption(delegate)`, plus a programmatic `registerArgument(delegate)`, read
 *    back through `OptionDelegate.value` / `ArgumentDelegate.value` after `parse(...)`. No delegated
 *    Kotlin property (`by option()`), so the binding can be driven from generated metadata.
 */
class SchemaCliBindingTest {

    // ------------------------------ parse/runtime boundary ------------------------------

    /**
     * A root that registers exactly one generated [SchemaToolCliCommand] for [spec], mirroring how Task 3
     * will register the canonical tool commands under `DevrigRootCommand` — without pulling in the full
     * production root while the wiring is still being built.
     */
    private class SchemaTestRoot(
        selected: SelectedDevrigCommand,
        spec: CliToolSpec,
    ) : DevrigCliktCommand(
        name = "devrig",
        selected = selected,
        parent = null,
        invokeWithoutSubcommand = true,
    ) {
        init {
            subcommands(SchemaToolCliCommand(spec, selected, this))
        }

        override fun run() = Unit
    }

    @Test
    fun `parsing list_windows --json yields an inert RunTool with no handler resolution`() {
        // devrigCliTools() builds the specs with a tools double whose handler() throws if resolved, so a
        // green parse is itself the proof that parsing reads only metadata (name / cli) and never calls a
        // handler, service, or backend.
        val spec = devrigCliTools().single { it.cli.name == "list_windows" }

        val selected = SelectedDevrigCommand()
        SchemaTestRoot(selected, spec).parse(arrayOf("list_windows", "--json"))

        val command = selected.command
        assertTrue(command is DevrigCommand.RunTool, "expected a RunTool, got $command")
        command as DevrigCommand.RunTool
        assertEquals("steroid_list_windows", command.toolName)
        assertEquals("list_windows", command.commandName)
        assertTrue(command.json, "--json must ride onto the RunTool")
        assertTrue(command.arguments.isEmpty(), "list_windows takes no parameters")
        assertEquals(ToolCliExtras(), command.extras)
    }

    // ------------------------------ Clikt 4.4.0 mechanism spike ------------------------------

    /**
     * Programmatic Clikt command: options/argument are plain vals created in `init` and registered via
     * [CliktCommand.registerOption] / [CliktCommand.registerArgument] — the exact mechanism the generated
     * per-parameter bindings need (no `by` delegation, so names/types come from metadata, not properties).
     */
    private class SpikeCommand : CliktCommand(name = "spike") {
        val name: OptionDelegate<String?> = option("--name")
        val count: OptionDelegate<Int?> = option("--count").int()
        val loud: OptionDelegate<Boolean> = option("--loud").flag()
        val target: ArgumentDelegate<String?> = argument("target").optional()

        init {
            context { helpOptionNames = emptySet() }
            registerOption(name)
            registerOption(count)
            registerOption(loud)
            registerArgument(target)
        }

        override fun run() = Unit
    }

    @Test
    fun `registered options and argument read their typed values after parse`() {
        val command = SpikeCommand()
        command.parse(arrayOf("--name=hello", "--count=7", "--loud", "world"))

        assertEquals("hello", command.name.value)
        assertEquals(7, command.count.value)
        assertEquals(true, command.loud.value)
        assertEquals("world", command.target.value)
    }

    @Test
    fun `absent option and argument values stay null and an absent flag stays false`() {
        val command = SpikeCommand()
        command.parse(emptyArray())

        assertNull(command.name.value)
        assertNull(command.count.value)
        assertEquals(false, command.loud.value)
        assertNull(command.target.value)
    }
}
