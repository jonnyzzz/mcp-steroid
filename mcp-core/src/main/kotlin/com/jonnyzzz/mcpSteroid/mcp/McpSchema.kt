package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class InputSchemaParamSpec(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
    /** Additional JSON keys merged into the property body (e.g. `minimum`, `maximum`, `items`). */
    val extra: JsonObjectBuilder.() -> Unit = {},
)

data class InputSchemaElement<R>(
    val spec: InputSchemaParamSpec,
    val parser: InputSchemaParamParser<R>
) {
    companion object
}

interface InputSchemaParamParser<R> {
    @Throws(ToolCallErrorException::class)
    fun parseParameter(context: ToolCallContext): R
}

fun InputSchemaElement.Companion.param(name: String) = InputSchemaElement(
    spec = InputSchemaParamSpec(name = name, description = "Not Set", type = "Error", required = false),
    parser = object : InputSchemaParamParser<Nothing> {
        override fun parseParameter(context: ToolCallContext): Nothing {
            throw ToolCallErrorException("Not implemented for $name")
        }
    }
)

fun <R> InputSchemaElement<R>.description(description: String) = InputSchemaElement(
    spec = spec.copy(description = description),
    parser
)

fun InputSchemaElement<Nothing>.boolean() = InputSchemaElement(
    spec = spec.copy(type = "boolean"),
    parser = object : InputSchemaParamParser<Boolean?> {
        override fun parseParameter(context: ToolCallContext): Boolean? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.booleanOrNull
        }
    }
)

fun InputSchemaElement<Nothing>.string() = InputSchemaElement(
    spec = spec.copy(type = "string"),
    parser = object : InputSchemaParamParser<String?> {
        override fun parseParameter(context: ToolCallContext): String? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.contentOrNull
        }
    }
)

/**
 * A string parameter constrained to a fixed set of [values] (rendered as JSON-schema `enum`).
 * The parser returns the raw string; callers map it to their enum and validate.
 */
fun <R : Any> InputSchemaElement<Nothing>.enumString(values: Map<String, R>) = InputSchemaElement(
    spec = spec.copy(
        type = "string",
        extra = {
            putJsonArray("enum") {
                values.keys.forEach {
                    add(it)
                }
            }
        },
    ),
    parser = object : InputSchemaParamParser<R?> {
        override fun parseParameter(context: ToolCallContext): R? {
            val text = context.params.arguments[spec.name]
                ?.jsonPrimitive
                ?.contentOrNull ?: return null

            return values[text]
                ?: throw ToolCallErrorException(
                    "Unknown value '$text' for ${spec.name}. " +
                        "Expected one of: ${values.keys.joinToString(", ")}"
                )
        }
    }
)

fun InputSchemaElement<Nothing>.int() = InputSchemaElement(
    spec = spec.copy(type = "integer"),
    parser = object : InputSchemaParamParser<Int?> {
        override fun parseParameter(context: ToolCallContext): Int? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.intOrNull
        }
    }
)

fun InputSchemaElement<Nothing>.number() = InputSchemaElement(
    spec = spec.copy(type = "number"),
    parser = object : InputSchemaParamParser<Double?> {
        override fun parseParameter(context: ToolCallContext): Double? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.doubleOrNull
        }
    }
)

fun <T : Any> InputSchemaElement<T?>.withDefaultValue(defaultValue: T): InputSchemaElement<T> {
    val that = this
    return InputSchemaElement(
        spec = spec.copy(),
        parser = object : InputSchemaParamParser<T> {
            override fun parseParameter(context: ToolCallContext): T {
                return that.parser.parseParameter(context) ?: defaultValue
            }
        }
    )
}

private fun <R> InputSchemaElement<R>.withExtra(block: JsonObjectBuilder.() -> Unit): InputSchemaElement<R> {
    val previous = spec.extra
    return copy(spec = spec.copy(extra = {
        previous()
        block()
    }))
}

fun InputSchemaElement<Double?>.minimum(value: Double) = withExtra { put("minimum", value) }

fun InputSchemaElement<Double?>.maximum(value: Double) = withExtra { put("maximum", value) }

fun InputSchemaElement<Nothing>.stringArray() = InputSchemaElement(
    spec = spec.copy(
        type = "array",
        extra = { putJsonObject("items") { put("type", "string") } },
    ),
    parser = object : InputSchemaParamParser<List<String>> {
        override fun parseParameter(context: ToolCallContext): List<String> {
            val raw = context.params.arguments[spec.name] as? JsonArray ?: return emptyList()
            return raw.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
    }
)

/** Declares an array whose per-element schema is built by [items]. */
fun InputSchemaElement<Nothing>.array(items: JsonObjectBuilder.() -> Unit) = InputSchemaElement(
    spec = spec.copy(
        type = "array",
        extra = { putJsonObject("items", items) },
    ),
    parser = object : InputSchemaParamParser<JsonArray?> {
        override fun parseParameter(context: ToolCallContext): JsonArray? {
            return context.params.arguments[spec.name] as? JsonArray
        }
    }
)

/** Marks a parameter required in the emitted schema without changing its parsed (nullable) type. */
fun <R> InputSchemaElement<R>.markRequired(): InputSchemaElement<R> =
    copy(spec = spec.copy(required = true))

fun <R : Any> InputSchemaElement<R?>.required(): InputSchemaElement<R> {
    val that = this
    return InputSchemaElement(
        this.spec.copy(required = true),
        object : InputSchemaParamParser<R> {
            override fun parseParameter(context: ToolCallContext): R {
                return that.parser.parseParameter(context)
                    ?: throw ToolCallErrorException("Parameter ${spec.name} of type ${spec.type} is required")
            }
        }
    )
}

fun InputSchemaElement.Companion.buildSchema(elements: List<InputSchemaElement<*>>) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        elements.map { it.spec }.forEach { element ->
            putJsonObject(element.name) {
                put("type", element.type)
                put("description", element.description)
                element.extra(this)
            }
        }
    }
    putJsonArray("required") {
        elements.map { it.spec }.filter { it.required }.forEach { element ->
            add(element.name)
        }
    }
}

@Throws(ToolCallErrorException::class)
operator fun <R> ToolCallContext.get(p: InputSchemaElement<R>) = p.parser.parseParameter(this)
