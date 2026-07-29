/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

// ---------------------------------------------------------------------------
//  Generic structural assertions (kept from the first round).
// ---------------------------------------------------------------------------

/**
 * Structural invariants every `*ToolSpec.inputSchema` must satisfy:
 *  1. Serialises to JSON and parses back without loss.
 *  2. Top-level `"type"` is the string `"object"`.
 *  3. `"properties"` is a JsonObject and each property value is itself a JsonObject.
 *  4. If `"required"` is present, it is a JsonArray of strings and every
 *     name listed appears as a key in `"properties"`.
 */
fun assertToolSpecHasValidJsonSchema(tool: McpTool) {
    val schema = tool.inputSchema

    val rendered = Json.encodeToString(JsonObject.serializer(), schema)
    val reparsed = Json.parseToJsonElement(rendered)
    assertEquals(schema, reparsed, "${tool.name}: inputSchema does not round-trip through JSON")

    assertJsonStringEquals(schema, "type", "object", "${tool.name}: inputSchema.type")

    val properties = schema.requireJsonObject("properties", "${tool.name}: inputSchema.properties")
    properties.entries.forEach { (propName, propValue) ->
        propValue as? JsonObject
            ?: error("${tool.name}: inputSchema.properties.\"$propName\" must be a JSON object; was $propValue")
    }

    val required = schema["required"] ?: return
    val requiredArray = required as? JsonArray
        ?: error("${tool.name}: inputSchema.required must be a JSON array; was $required")
    requiredArray.forEachIndexed { index, element ->
        val name = (element as? JsonPrimitive)?.let { if (it.isString) it.content else null }
            ?: error("${tool.name}: inputSchema.required[$index] must be a JSON string; was $element")
        assertTrue(
            properties.containsKey(name),
            "${tool.name}: inputSchema.required[$index]=\"$name\" is not present in inputSchema.properties",
        )
    }
}

// ---------------------------------------------------------------------------
//  Content-aware assertions.
//
//  Each helper takes the *schema* JsonObject (i.e. `tool.inputSchema`), the
//  property name to check, and asserts the precise shape of that property —
//  type, description-non-blank, optional bounds, etc. Failures cite the
//  offending key path so a regression is self-explaining.
// ---------------------------------------------------------------------------

/** Asserts the tool advertises the expected MCP name + a non-blank description. */
fun assertToolIdentity(tool: McpTool, expectedName: String) {
    assertEquals(expectedName, tool.name, "${tool::class.simpleName}: tool name")
    val description = tool.description
    assertNotNull(description, "${tool.name}: description must be present")
    assertFalse(description!!.isBlank(), "${tool.name}: description must be non-blank")
}

/** Asserts `inputSchema.required` is exactly the given names, in any order. */
fun assertRequiredExactly(schema: JsonObject, vararg names: String) {
    val requiredArray = schema["required"] as? JsonArray
        ?: error("inputSchema.required must be a JSON array; was ${schema["required"]}")
    val actual = requiredArray.map {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            ?: error("inputSchema.required entries must be JSON strings; got $it")
    }.toSet()
    assertEquals(names.toSet(), actual, "inputSchema.required mismatch")
}

/** Asserts the schema does NOT mark the named field as required (i.e. it's optional). */
fun assertOptional(schema: JsonObject, propertyName: String) {
    val requiredArray = schema["required"] as? JsonArray ?: return
    val present = requiredArray.any {
        (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == propertyName
    }
    assertFalse(present, "inputSchema.required must NOT contain \"$propertyName\"")
}

/** Asserts a property exists, has `type:"string"`, and carries a non-blank `description`. */
fun assertStringProperty(schema: JsonObject, propertyName: String): JsonObject =
    assertTypedProperty(schema, propertyName, "string")

/** Asserts a property exists, has `type:"integer"`, and carries a non-blank `description`. */
fun assertIntegerProperty(schema: JsonObject, propertyName: String): JsonObject =
    assertTypedProperty(schema, propertyName, "integer")

/** Asserts a property exists, has `type:"boolean"`, and carries a non-blank `description`. */
fun assertBooleanProperty(schema: JsonObject, propertyName: String): JsonObject =
    assertTypedProperty(schema, propertyName, "boolean")

/** Asserts a string property exposes exactly [values] (in order) as its JSON-schema `enum`. */
fun assertEnumProperty(schema: JsonObject, propertyName: String, vararg values: String): JsonObject {
    val prop = assertStringProperty(schema, propertyName)
    val enumArray = prop["enum"] as? JsonArray
        ?: error("inputSchema.properties.\"$propertyName\".enum is missing or not an array")
    val actual = enumArray.map { (it as JsonPrimitive).contentOrNull }
    assertEquals(values.toList(), actual, "properties.$propertyName.enum")
    return prop
}

/**
 * Asserts a property has `type:"number"` with optional `minimum`/`maximum` bounds.
 * Pass `null` for an unbounded edge.
 */
fun assertNumberProperty(
    schema: JsonObject,
    propertyName: String,
    minimum: Double? = null,
    maximum: Double? = null,
): JsonObject {
    val prop = assertTypedProperty(schema, propertyName, "number")
    if (minimum != null) {
        val actual = (prop["minimum"] as? JsonPrimitive)?.doubleOrNull
            ?: error("$propertyName: minimum must be a number; was ${prop["minimum"]}")
        assertEquals(minimum, actual, "$propertyName: minimum")
    }
    if (maximum != null) {
        val actual = (prop["maximum"] as? JsonPrimitive)?.doubleOrNull
            ?: error("$propertyName: maximum must be a number; was ${prop["maximum"]}")
        assertEquals(maximum, actual, "$propertyName: maximum")
    }
    return prop
}

/**
 * Asserts a property has `type:"array"` and a non-blank `description`. Returns the
 * property body so callers can drill into `items` if they want to pin element shape.
 */
fun assertArrayProperty(schema: JsonObject, propertyName: String): JsonObject =
    assertTypedProperty(schema, propertyName, "array")

/**
 * Drills into an array property's `items` schema. Returns the items JsonObject
 * so callers can run further property/required assertions on it.
 */
fun JsonObject.items(): JsonObject =
    requireJsonObject("items", "items schema")

/** Convenience getter for a properties block (e.g. `schema.properties["x"]`). */
val JsonObject.properties: JsonObject
    get() = requireJsonObject("properties", "properties block")

private fun assertTypedProperty(
    schema: JsonObject,
    propertyName: String,
    expectedType: String,
): JsonObject {
    val properties = schema.requireJsonObject("properties", "inputSchema.properties")
    val prop = properties[propertyName] as? JsonObject
        ?: error("inputSchema.properties.\"$propertyName\" is missing or not a JsonObject")
    assertJsonStringEquals(prop, "type", expectedType, "properties.$propertyName.type")
    val description = (prop["description"] as? JsonPrimitive)?.contentOrNull
    assertNotNull(description, "properties.$propertyName.description must be present")
    assertFalse(description!!.isBlank(), "properties.$propertyName.description must be non-blank")
    return prop
}

/**
 * Asserts the named schema property is **absent** from `inputSchema.properties` — useful
 * when the description text claims a field exists but the schema deliberately omits it.
 */
fun assertPropertyAbsent(schema: JsonObject, propertyName: String) {
    val properties = schema["properties"] as? JsonObject ?: return
    assertNull(properties[propertyName], "inputSchema.properties.\"$propertyName\" must NOT be present")
}

// ---------------------------------------------------------------------------
//  Internal helpers.
// ---------------------------------------------------------------------------

private fun JsonObject.requireJsonObject(key: String, label: String): JsonObject =
    this[key] as? JsonObject
        ?: error("$label must be a JSON object; was ${this[key]}")

private fun assertJsonStringEquals(
    obj: JsonObject,
    key: String,
    expected: String,
    label: String,
) {
    val primitive = obj[key] as? JsonPrimitive
        ?: error("$label must be a JSON primitive; was ${obj[key]}")
    assertTrue(primitive.isString, "$label must be a string primitive")
    assertEquals(expected, primitive.content, label)
}

/** Convenience for `*ToolSpec` constructor lambdas that must never be invoked. */
fun unreachableHandler(): Nothing =
    error("handler factory must not be invoked from an inputSchema test")

/**
 * A [McpSteroidTools] whose [McpSteroidTools.handler] throws — for tests that only need the
 * [CliToolSpec] metadata (schema, `cli.*`) of the real, canonical tool list and must never actually
 * resolve or invoke a handler.
 */
private class NoHandlerToolsForTest : McpSteroidTools() {
    override fun <T> handler(type: Class<T>): T =
        error("handler ${type.name} must not be resolved from a schema-only test")
}

/**
 * The exact 8 tool specs `devrig` exposes (see [McpSteroidTools.devrigToolSpecs]), built fresh with
 * handlers that must never be resolved. Tests that assert "every tool has X" should iterate this list
 * rather than a hand-picked one, so a ninth tool added to the canonical list is covered automatically.
 */
fun devrigToolSpecsForTest(): List<CliToolSpec> = NoHandlerToolsForTest().devrigToolSpecs()
