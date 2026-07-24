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

        val alpha = InputSchemaElement.param("alpha").description("Alpha").string().required().registerToSchema()
        val beta = InputSchemaElement.param("beta").description("Beta").int().registerToSchema()
        val mode = InputSchemaElement.param("mode").description("Mode")
            .enumString(mapOf("a" to 1, "b" to 2, "c" to 3)).registerToSchema()
        val uri = InputSchemaElement.param("uri").description("URI").string().required().cliPositional().registerToSchema()
        val code = InputSchemaElement.param("code").description("Code").string()
            .cliFlag("--code-file").cliSynopsis("path to a file").registerToSchema()
        val secret = InputSchemaElement.param("secret").description("Secret").string().cliHidden().registerToSchema()
        val opt = InputSchemaElement.param("opt").description("Opt").string().required().cliOptional().registerToSchema()
        val tags = InputSchemaElement.param("tags").description("Tags").stringArray().registerToSchema()

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
    fun `CliCommandSpec defaults are empty aliases and not hidden`() {
        assertEquals(emptyList<String>(), tool.cli.aliases)
        assertFalse(tool.cli.hidden)
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
    fun `cliSynopsis on a param defaults to null and can be set`() {
        assertNull(tool.alpha.spec.cliSynopsis)
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
            listOf("alpha", "beta", "mode", "uri", "code", "secret", "opt", "tags"),
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
        for (leak in listOf("cliFlag", "cliSynopsis", "cliPositional", "cliHidden", "cliOptional", "enumValues")) {
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
        val first = schema.register(InputSchemaElement.param("first").description("First").string().required())
        val second = schema.register(InputSchemaElement.param("second").description("Second").int())
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
}
