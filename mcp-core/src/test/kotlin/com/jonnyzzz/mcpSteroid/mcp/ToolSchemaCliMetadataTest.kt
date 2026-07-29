/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that CLI metadata can be read from the shared tool schema without changing the MCP
 * `inputSchema` projection.
 */
class ToolSchemaCliMetadataTest {

    private class SampleTool : McpToolBase() {
        override val name = "steroid_sample"
        override val description = "Sample tool for CLI metadata tests"
        override val cliSynopsis = "do a sample thing"

        val alpha = InputSchemaElement.param("alpha").description("Alpha").string().required()
            .cliSynopsis("Alpha value").registerToSchema()
        val beta = InputSchemaElement.param("beta").description("Beta").int()
            .cliSynopsis("Beta count").registerToSchema()
        val mode = InputSchemaElement.param("mode").description("Mode")
            .enumString(mapOf("a" to 1, "b" to 2, "c" to 3)).cliSynopsis("Selection mode").registerToSchema()
        val uri = InputSchemaElement.param("uri").description("URI").string().required().cliPositional()
            .cliSynopsis("Target URI").registerToSchema()
        val code = InputSchemaElement.param("code").description("Code").string()
            .cliFlag("--code-file").cliSynopsis("path to a file").registerToSchema()
        val secret = InputSchemaElement.param("secret").description("Secret").string().cliHidden().registerToSchema()
        val opt = InputSchemaElement.param("opt").description("Opt").string().required().cliOptional()
            .cliSynopsis("Optional value").registerToSchema()
        val tags = InputSchemaElement.param("tags").description("Tags").stringArray()
            .cliSynopsis("Tag list").registerToSchema()
        val script = InputSchemaElement.param("script").description("Script").string().required()
            .cliSynopsis("script body to run").cliOptional()
            .cliFileSource("--script-file", "path to a script file; pass \"-\" to read from stdin")
            .registerToSchema()

        override suspend fun call(context: ToolCallContext): ToolCallResult = error("not used")
    }

    /** A tool whose subcommand carries an extra CLI option the CLI itself acts on, not a tool input. */
    private class WaitingTool : McpToolBase() {
        override val name = "steroid_waiting"
        override val description = "Tool with a tool-level extra CLI option"
        override val cliSynopsis = "do a thing and optionally wait"
        override val cliExtraOptions = listOf(
            CliExtraOption("--wait", CliOptionType.BOOLEAN, "poll until the thing is done"),
        )

        // One ordinary parameter, so "the extra option is not among the parameters" has something to
        // distinguish itself from; the element itself is never read, only registered.
        init {
            InputSchemaElement.param("alpha").description("Alpha").string().required()
                .cliSynopsis("Alpha value").registerToSchema()
        }

        override suspend fun call(context: ToolCallContext): ToolCallResult = error("not used")
    }

    private val tool = SampleTool()

    @Test
    fun `cli name strips the steroid prefix`() {
        assertEquals("sample", tool.cli.name)
    }

    @Test
    fun `cliSynopsis surfaces in cli synopsis`() {
        assertEquals("do a sample thing", tool.cli.synopsis)
    }

    @Test
    fun `CliCommandSpec defaults are empty aliases, no extra options and not hidden`() {
        assertEquals(emptyList<String>(), tool.cli.aliases)
        assertEquals(emptyList<CliExtraOption>(), tool.cli.extraOptions)
        assertFalse(tool.cli.hidden)
    }

    @Test
    fun `a declared tool-level extra CLI option surfaces on the command spec`() {
        val waiting = WaitingTool()
        assertEquals(
            listOf(CliExtraOption("--wait", CliOptionType.BOOLEAN, "poll until the thing is done")),
            waiting.cli.extraOptions,
        )
    }

    @Test
    fun `a tool-level extra CLI option is not a parameter and cannot reach the MCP schema`() {
        val waiting = WaitingTool()
        assertEquals(listOf("alpha"), waiting.schema.asCliParams().map { it.name })
        assertEquals(setOf("alpha"), waiting.schema.asMcpJson()["properties"]!!.jsonObject.keys)
        val rendered = Json.encodeToString(JsonObject.serializer(), waiting.schema.asMcpJson())
        assertFalse(rendered.contains("wait"), "an extra CLI option must not reach the MCP schema: $rendered")
    }

    @Test
    fun `cliFlag defaults to dash-dash-name and can be overridden`() {
        assertEquals("--alpha", tool.alpha.spec.cliFlag)
        assertEquals("--code-file", tool.code.spec.cliFlag)
    }

    @Test
    fun `cliPositional and cliHidden are reflected in the spec`() {
        assertTrue(tool.uri.spec.cliPositional)
        assertFalse(tool.alpha.spec.cliPositional)
        assertTrue(tool.secret.spec.cliHidden)
        assertFalse(tool.alpha.spec.cliHidden)
    }

    @Test
    fun `cliSynopsis defaults to an empty string until set, and the builder sets it`() {
        val fresh = InputSchemaElement.param("fresh").description("Fresh").string()
        assertEquals("", fresh.spec.cliSynopsis)
        assertEquals("path to a file", tool.code.spec.cliSynopsis)
    }

    @Test
    fun `cliOptional defaults to false and the builder sets it without touching required`() {
        assertFalse(tool.alpha.spec.cliOptional)
        assertTrue(tool.opt.spec.cliOptional)
        // cliOptional is a CLI-only projection: the param stays MCP-required.
        assertTrue(tool.opt.spec.required)
    }

    @Test
    fun `cliOptional never leaks into the MCP required list`() {
        val required = tool.schema.asMcpJson()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("opt"), "cliOptional must not drop a param from MCP required: $required")
    }

    @Test
    fun `enumString records enumValues in registration order`() {
        assertEquals(listOf("a", "b", "c"), tool.mode.spec.enumValues)
        assertNull(tool.alpha.spec.enumValues)
    }

    @Test
    fun `asCliParams returns one spec per registered param in registration order`() {
        assertEquals(
            listOf("alpha", "beta", "mode", "uri", "code", "secret", "opt", "tags", "script"),
            tool.schema.asCliParams().map { it.name },
        )
    }

    @Test
    fun `asMcpJson renders the real MCP schema for the registered params`() {
        // inputSchema literally delegates to schema.asMcpJson() (see McpToolBase), so asserting their
        // equality would be a tautology; the byte-for-byte "unchanged vs before" guarantee lives in the
        // golden *ToolSpecSchemaTest. Here we assert asMcpJson produces the expected schema structure.
        val json = tool.schema.asMcpJson()
        val props = json["properties"]!!.jsonObject
        assertEquals("integer", tool.beta.spec.type)
        assertEquals("array", tool.tags.spec.type)
        assertEquals("string", props["alpha"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("integer", props["beta"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("array", props["tags"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = json["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("alpha"))
        assertFalse(required.contains("beta"))
    }

    @Test
    fun `CLI hint fields never leak into the MCP JSON schema`() {
        val rendered = Json.encodeToString(JsonObject.serializer(), tool.schema.asMcpJson())
        val leaks = listOf(
            "cliFlag", "cliSynopsis", "cliPositional", "cliHidden", "cliOptional", "enumValues",
            "cliFileSource", "--script-file",
        )
        for (leak in leaks) {
            assertFalse(rendered.contains(leak), "MCP schema must not contain CLI hint field '$leak': $rendered")
        }
    }

    @Test
    fun `McpToolBase is a CliToolSpec exposing cli and schema`() {
        val spec: CliToolSpec = tool
        assertEquals("sample", spec.cli.name)
        assertEquals(tool.schema.asCliParams(), spec.schema.asCliParams())
    }

    @Test
    fun `a plain McpTool double needs no cli metadata`() {
        val plain = object : McpTool {
            override val name = "steroid_plain"
            override val description = "a plain tool"
            override val inputSchema = InputSchemaElement.buildSchema(emptyList())
            override suspend fun call(context: ToolCallContext): ToolCallResult = error("not used")
        }
        assertEquals("steroid_plain", plain.name)
    }

    @Test
    fun `defaultCliName strips the steroid prefix`() {
        assertEquals("plain", defaultCliName("steroid_plain"))
        assertEquals("already_bare", defaultCliName("already_bare"))
    }

    @Test
    fun `ToolSchema register preserves order and feeds both projections`() {
        val schema = ToolSchema()
        val first = schema.register(
            InputSchemaElement.param("first").description("First").string().required().cliSynopsis("First value")
        )
        val second = schema.register(
            InputSchemaElement.param("second").description("Second").int().cliSynopsis("Second value")
        )
        // register returns the element it was handed, so declarations can chain.
        assertEquals("first", first.spec.name)
        assertEquals("second", second.spec.name)
        // Both projections read the same ordered list.
        assertEquals(listOf("first", "second"), schema.asCliParams().map { it.name })
        val props = schema.asMcpJson()["properties"]!!.jsonObject
        assertEquals(setOf("first", "second"), props.keys)
        val required = schema.asMcpJson()["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("first"), required)
    }

    @Test
    fun `asCliParams rejects a CLI-visible parameter with a blank synopsis`() {
        val schema = ToolSchema()
        schema.register(InputSchemaElement.param("bare").description("Bare").string())
        val error = assertThrows(IllegalArgumentException::class.java) { schema.asCliParams() }
        assertTrue(error.message!!.contains("bare"), "error should name the parameter: ${error.message}")
    }

    @Test
    fun `asCliParams allows a blank synopsis on a cliHidden parameter`() {
        val schema = ToolSchema()
        schema.register(InputSchemaElement.param("bare").description("Bare").string().cliHidden())
        assertEquals(listOf("bare"), schema.asCliParams().map { it.name })
    }

    @Test
    fun `a file source declares a second CLI source for the parameter's own value`() {
        val fileSource = tool.script.spec.cliFileSource
        assertEquals("--script-file", fileSource?.flag)
        assertEquals("path to a script file; pass \"-\" to read from stdin", fileSource?.synopsis)
        // The parameter keeps its own direct flag; the file source is the alternative, not a replacement.
        assertEquals("--script", tool.script.spec.cliFlag)
        assertNull(tool.alpha.spec.cliFileSource, "a parameter with no declared file source reports none")
    }

    @Test
    fun `a file-source parameter stays a normal MCP parameter on the wire`() {
        // Unlike the CLI-only parameter this replaces, the value still exists on the MCP wire — only the
        // *path* form is CLI-exclusive, so nothing is filtered out of asMcpJson.
        val json = tool.schema.asMcpJson()
        assertEquals("string", json["properties"]!!.jsonObject["script"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val required = json["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("script"), "a file source must not drop the param from MCP required: $required")
    }

    @Test
    fun `a file source renders the exact same MCP JSON as the same parameter without one`() {
        fun schemaOf(withFileSource: Boolean) = ToolSchema().also {
            var element = InputSchemaElement.param("code").description("Code").string().required()
                .cliSynopsis("code body").cliOptional()
            if (withFileSource) element = element.cliFileSource("--code-file", "path to a script file")
            it.register(element)
        }

        val plain = Json.encodeToString(JsonObject.serializer(), schemaOf(withFileSource = false).asMcpJson())
        val sourced = Json.encodeToString(JsonObject.serializer(), schemaOf(withFileSource = true).asMcpJson())
        assertEquals(plain, sourced)
    }

    @Test
    fun `a file source rejects a non-string parameter`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InputSchemaElement.param("count").description("Count").int().cliFileSource("--count-file", "path")
        }
        assertTrue(error.message!!.contains("count"), "error should name the parameter: ${error.message}")
    }

    @Test
    fun `a file source rejects a flag that collides with the parameter's own flag`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InputSchemaElement.param("code").description("Code").string().cliFileSource("--code", "path")
        }
        assertTrue(error.message!!.contains("--code"), "error should name the colliding flag: ${error.message}")
    }

    @Test
    fun `a file-source parameter that the CLI would still demand directly throws at registration time`() {
        // A file source only helps if the CLI may omit the direct value; required-and-not-cliOptional
        // would make the CLI reject `--code-file` alone, which is the whole point of declaring it.
        val schema = ToolSchema()
        val element = InputSchemaElement.param("code").description("Code").string().required()
            .cliSynopsis("code body").cliFileSource("--code-file", "path to a script file")
        val error = assertThrows(IllegalArgumentException::class.java) { schema.register(element) }
        assertTrue(error.message!!.contains("code"), "error should name the parameter: ${error.message}")
        assertTrue(
            error.message!!.contains("cliOptional"),
            "error should point at the missing declaration: ${error.message}",
        )
    }

    @Test
    fun `cliMinimum and cliMaximum surface in asCliParams`() {
        val bounded = InputSchemaElement.param("count").description("Count").int()
            .cliSynopsis("How many").cliMinimum(1.0).cliMaximum(10.0)
        assertEquals(1.0, bounded.spec.cliMinimum)
        assertEquals(10.0, bounded.spec.cliMaximum)
    }

    @Test
    fun `cliMinimum and cliMaximum never affect asMcpJson`() {
        fun withoutBounds() = ToolSchema().also {
            it.register(InputSchemaElement.param("count").description("Count").int().cliSynopsis("How many"))
        }

        fun withBounds() = ToolSchema().also {
            it.register(
                InputSchemaElement.param("count").description("Count").int()
                    .cliSynopsis("How many").cliMinimum(1.0).cliMaximum(10.0)
            )
        }

        val plain = Json.encodeToString(JsonObject.serializer(), withoutBounds().asMcpJson())
        val bounded = Json.encodeToString(JsonObject.serializer(), withBounds().asMcpJson())
        assertEquals(plain, bounded)
    }

    @Test
    fun `cliMinimum rejects a non-numeric parameter`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InputSchemaElement.param("name").description("Name").string().cliMinimum(1.0)
        }
        assertTrue(error.message!!.contains("name"), "error should name the parameter: ${error.message}")
    }

    @Test
    fun `cliMaximum rejects a non-numeric parameter`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InputSchemaElement.param("name").description("Name").string().cliMaximum(1.0)
        }
        assertTrue(error.message!!.contains("name"), "error should name the parameter: ${error.message}")
    }

    @Test
    fun `cliMissingHint round-trips through asCliParams and stays out of asMcpJson`() {
        val schema = ToolSchema()
        schema.register(
            InputSchemaElement.param("token").description("Token").string().required()
                .cliSynopsis("Auth token").cliMissingHint("Set MCP_STEROID_TOKEN or pass --token")
        )
        val hint = schema.asCliParams().single { it.name == "token" }.cliMissingHint
        assertEquals("Set MCP_STEROID_TOKEN or pass --token", hint)
        val rendered = Json.encodeToString(JsonObject.serializer(), schema.asMcpJson())
        assertFalse(rendered.contains("MCP_STEROID_TOKEN"), "cliMissingHint must not leak into the MCP schema: $rendered")
    }

    @Test
    fun `golden schema - every invisible CLI field leaves asMcpJson unchanged`() {
        fun plainSchema() = ToolSchema().also {
            it.register(InputSchemaElement.param("alpha").description("Alpha").string().required())
            it.register(InputSchemaElement.param("count").description("Count").int())
        }

        fun decoratedSchema() = ToolSchema().also {
            it.register(
                InputSchemaElement.param("alpha").description("Alpha").string().required()
                    .cliSynopsis("Alpha value").cliFlag("--alpha-value").cliMissingHint("pass --alpha-value")
                    .cliOptional().cliFileSource("--alpha-file", "read Alpha from this file")
            )
            it.register(
                InputSchemaElement.param("count").description("Count").int()
                    .cliSynopsis("Count value").cliMinimum(0.0).cliMaximum(100.0)
            )
        }

        val plain = Json.encodeToString(JsonObject.serializer(), plainSchema().asMcpJson())
        val decorated = Json.encodeToString(JsonObject.serializer(), decoratedSchema().asMcpJson())
        assertEquals(plain, decorated)
    }
}
