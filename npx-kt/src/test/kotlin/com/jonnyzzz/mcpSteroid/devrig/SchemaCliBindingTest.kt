/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
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
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the two foundations of the schema-driven CLI (issue #284):
 *
 * 1. The parse/runtime boundary — a generated [SchemaToolCliCommand] parses `list_windows --json` into an
 *    inert [DevrigCommand.RunTool] and never resolves a handler, service, or backend. Runtime dispatch is
 *    a strictly later lifecycle phase (`DevrigServices.runCli`), so a value object is all parsing yields.
 * 2. The Clikt 4.4.0 mechanism the typed bindings build on — options created in `init` and
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

    // ------------------------------ typed schema bindings ------------------------------

    /**
     * Binds [specs] onto one command with [SchemaCliBinding] and, after parsing, exposes the JSON built by
     * serializing each binding's ALREADY-TYPED delegate value. Mirrors what the generated
     * [SchemaToolCliCommand] does at runtime, but isolated so a single mapping can be exercised without the
     * full tool grammar.
     */
    private class BindingTestCommand(specs: List<InputSchemaParamSpec>) : CliktCommand(name = "bind") {
        private val bindings: List<SchemaCliBinding>
        var result: JsonObject = JsonObject(emptyMap())
            private set

        init {
            context { helpOptionNames = emptySet() }
            bindings = SchemaCliBinding.bindAll(this, specs)
        }

        override fun run() {
            result = buildJsonObject { bindings.forEach { it.appendTo(this) } }
        }
    }

    private fun parseArguments(specs: List<InputSchemaParamSpec>, vararg args: String): JsonObject =
        BindingTestCommand(specs).also { it.parse(arrayOf(*args)) }.result

    /** The single param [paramName] as declared by the real tool [toolName] in [devrigCliTools]. */
    private fun toolParam(toolName: String, paramName: String): InputSchemaParamSpec =
        devrigCliTools().single { it.cli.name == toolName }.schema.asCliParams().single { it.name == paramName }

    private fun stringParam(name: String, required: Boolean = false) =
        InputSchemaParamSpec(name = name, type = "string", description = "", required = required)

    private fun arrayParam(name: String, itemType: String, positional: Boolean = false) = InputSchemaParamSpec(
        name = name,
        type = "array",
        description = "",
        required = false,
        cliPositional = positional,
        extra = { putJsonObject("items") { put("type", itemType) } },
    )

    @Test
    fun `string maps to a JsonPrimitive string`() {
        val json = parseArguments(listOf(stringParam("who")), "--who=world")
        assertEquals("world", json["who"]?.jsonPrimitive?.content)
    }

    @Test
    fun `enum maps through Clikt choice to a JsonPrimitive string`() {
        val modal = toolParam("execute_code", "modal")
        val json = parseArguments(listOf(modal), "--modal=non_modal")
        assertEquals("non_modal", json["modal"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an out-of-set enum value fails at parse time`() {
        val modal = toolParam("execute_code", "modal")
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(modal)).parse(arrayOf("--modal=bogus")) }
    }

    @Test
    fun `integer maps through Clikt int to a JsonPrimitive int`() {
        val timeout = toolParam("execute_code", "timeout")
        val json = parseArguments(listOf(timeout), "--timeout=42")
        val element = json["timeout"]?.jsonPrimitive
        assertEquals(42, element?.intOrNull)
        // A typed int, not a quoted string (no toString() staging).
        assertFalse(element!!.isString, "timeout must serialize as a JSON number, not a string")
    }

    @Test
    fun `a non-numeric integer fails at parse time`() {
        val timeout = toolParam("execute_code", "timeout")
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(timeout)).parse(arrayOf("--timeout=soon")) }
    }

    @Test
    fun `number maps through Clikt double to a JsonPrimitive number`() {
        val rating = toolParam("execute_feedback", "success_rating")
        val json = parseArguments(listOf(rating), "--success_rating=0.9")
        val element = json["success_rating"]?.jsonPrimitive
        assertEquals(0.9, element?.doubleOrNull)
        assertFalse(element!!.isString, "success_rating must serialize as a JSON number, not a string")
    }

    @Test
    fun `numeric maximum from the schema fails at parse time not as a backend error`() {
        // success_rating carries maximum=1.0 in spec.extra; restrictTo(...) enforces it during parsing so
        // 2.0 is a USAGE (exit 64) error, never a backend tool error.
        val rating = toolParam("execute_feedback", "success_rating")
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(rating)).parse(arrayOf("--success_rating=2.0")) }
    }

    @Test
    fun `numeric minimum from the schema fails at parse time`() {
        val rating = toolParam("execute_feedback", "success_rating")
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(rating)).parse(arrayOf("--success_rating=-0.5")) }
    }

    @Test
    fun `an optional boolean present becomes true`() {
        val trust = toolParam("open_project", "trust_project")
        val json = parseArguments(listOf(trust), "--trust_project")
        assertEquals(true, json["trust_project"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `an omitted optional boolean is absent from the JSON - never serialized as false`() {
        // CRITICAL: if trust_project serialized as false, the tool default (true) would flip.
        val trust = toolParam("open_project", "trust_project")
        val json = parseArguments(listOf(trust))
        assertFalse(json.containsKey("trust_project"), "an omitted optional boolean must be omitted, got $json")
    }

    @Test
    fun `an array maps repeated option occurrences to a JsonArray - no delimiter splitting`() {
        val tags = InputSchemaParamSpec(name = "tag", type = "array", description = "", required = false)
        val json = parseArguments(listOf(tags), "--tag=a", "--tag=b")
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), json["tag"])
    }

    @Test
    fun `an array never splits a single occurrence on commas`() {
        val tags = InputSchemaParamSpec(name = "tag", type = "array", description = "", required = false)
        val json = parseArguments(listOf(tags), "--tag=a,b")
        assertEquals(JsonArray(listOf(JsonPrimitive("a,b"))), json["tag"])
    }

    @Test
    fun `a positional array uses Clikt multiple`() {
        val files = InputSchemaParamSpec(name = "files", type = "array", description = "", required = false, cliPositional = true)
        val json = parseArguments(listOf(files), "one", "two", "three")
        assertEquals(JsonArray(listOf(JsonPrimitive("one"), JsonPrimitive("two"), JsonPrimitive("three"))), json["files"])
    }

    @Test
    fun `array item schema controls JSON primitive types`() {
        val json = parseArguments(
            listOf(arrayParam("count", "integer"), arrayParam("ratio", "number"), arrayParam("enabled", "boolean")),
            "--count=2", "--count=7", "--ratio=0.5", "--enabled=true", "--enabled=false",
        )
        assertEquals(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(7))), json["count"])
        assertEquals(JsonArray(listOf(JsonPrimitive(0.5))), json["ratio"])
        assertEquals(listOf(true, false), json["enabled"]!!.jsonArray.map { it.jsonPrimitive.boolean })
    }

    @Test
    fun `typed positional array uses its item schema`() {
        val json = parseArguments(listOf(arrayParam("count", "integer", positional = true)), "2", "7")
        assertEquals(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(7))), json["count"])
    }

    @Test
    fun `object array fails while constructing the generated command`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            BindingTestCommand(listOf(arrayParam("entry", "object")))
        }
        assertTrue(error.message!!.contains("object"), error.message)
        assertTrue(error.message!!.contains("entry"), error.message)
    }

    @Test
    fun `absent optional values are omitted from the JSON`() {
        val json = parseArguments(listOf(stringParam("who")))
        assertTrue(json.isEmpty(), "an absent optional value must be omitted, got $json")
    }

    @Test
    fun `cliHidden parameters are never bound and never appear in the JSON`() {
        val hidden = InputSchemaParamSpec(name = "secret", type = "string", description = "", required = false, cliHidden = true)
        // A hidden flag is not registered, so passing it is an unknown option (parse error) rather than a value.
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(hidden)).parse(arrayOf("--secret=x")) }
        assertTrue(parseArguments(listOf(hidden)).isEmpty(), "a cliHidden param must never be serialized")
    }

    @Test
    fun `a required CLI parameter missing fails at parse time`() {
        // required && !cliOptional -> Clikt required. success_rating is MCP-required and not cliOptional.
        val rating = toolParam("execute_feedback", "success_rating")
        assertThrows(CliktError::class.java) { BindingTestCommand(listOf(rating)).parse(emptyArray()) }
    }

    @Test
    fun `a CLI-optional MCP-required parameter is not required at parse time`() {
        // project_name is MCP-required but cliOptional (cwd inference): the CLI must not force it.
        val projectName = toolParam("execute_code", "project_name")
        assertTrue(projectName.required && projectName.cliOptional, "fixture expects project_name required+cliOptional")
        assertTrue(parseArguments(listOf(projectName)).isEmpty(), "a cliOptional param must not be required by the CLI")
    }

    @Test
    fun `the generated command populates RunTool arguments with typed values`() {
        // End-to-end through SchemaToolCliCommand: typed delegate values land directly on the RunTool JSON.
        val spec = devrigCliTools().single { it.cli.name == "execute_feedback" }
        val selected = SelectedDevrigCommand()
        SchemaTestRoot(selected, spec).parse(
            arrayOf("execute_feedback", "--task_id=t1", "--success_rating=0.75", "--explanation=ok", "--json")
        )
        val command = assertInstanceOf(DevrigCommand.RunTool::class.java, selected.command)
        assertEquals("steroid_execute_feedback", command.toolName)
        assertEquals("t1", command.arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals(0.75, command.arguments["success_rating"]?.jsonPrimitive?.doubleOrNull)
        assertEquals("ok", command.arguments["explanation"]?.jsonPrimitive?.content)
        assertFalse(command.arguments.containsKey("execution_id"), "an absent optional must be omitted")
    }
}
