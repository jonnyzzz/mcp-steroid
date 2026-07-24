package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    // CLI hints are exposed by [ToolSchema.asCliParams] and are never serialized into MCP inputSchema JSON.
    /** CLI flag for this parameter; defaults to `--<name>` (e.g. `project_name` -> `--project_name`). */
    val cliFlag: String = "--$name",
    /** Short one-line flag help for the CLI; when null the generator falls back to a trimmed [description]. */
    val cliSynopsis: String? = null,
    /** True when the parameter is a CLI positional argument rather than a flag (e.g. `uri`). */
    val cliPositional: Boolean = false,
    /** True when the parameter is not exposed as a CLI flag at all. */
    val cliHidden: Boolean = false,
    /**
     * True when the CLI treats this parameter as optional even though [required] is true for the MCP call.
     * Used for parameters the CLI can supply itself, such as a `project_name` inferred from the current
     * directory. The MCP `inputSchema` remains required.
     * Read only by the CLI projection/rendering; never affects [asMcpJson].
     */
    val cliOptional: Boolean = false,
    /** Allowed values recorded by [enumString], so CLI help can print `a | b | c`; null when unconstrained. */
    val enumValues: List<String>? = null,
)

/**
 * Owns the registered [InputSchemaElement]s of a single tool and offers two projections of them:
 * the MCP JSON `inputSchema` sent to clients, and the CLI parameter metadata consumed by the devrig
 * CLI frontend. The per-parameter parser stays encapsulated inside each [InputSchemaElement]; only
 * [InputSchemaParamSpec] values are exposed by [asCliParams].
 *
 * The schema is the mutable owner of the parameter set: elements are added via [register] (in
 * declaration order), and both projections read that single ordered list. [McpToolBase] holds one
 * `ToolSchema` and routes its `registerToSchema()` into [register].
 */
class ToolSchema {
    private val elements = mutableListOf<InputSchemaElement<*>>()

    /** Registers [e] into this schema, preserving declaration order, and returns it for chaining. */
    fun <R> register(e: InputSchemaElement<R>): InputSchemaElement<R> {
        elements.add(e)
        return e
    }

    /** MCP form: the JSON `inputSchema` sent to MCP clients. */
    fun asMcpJson(): JsonObject = InputSchemaElement.buildSchema(elements)

    /** CLI form — the parameter metadata only; the parsers stay encapsulated on the elements. */
    fun asCliParams(): List<InputSchemaParamSpec> = elements.map { it.spec }
}

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

/** Sets a short one-line CLI flag help; a CLI-only hint absent from the MCP `inputSchema`. */
fun <R> InputSchemaElement<R>.cliSynopsis(text: String) = copy(spec = spec.copy(cliSynopsis = text))

/** Marks this parameter as a CLI positional argument rather than a flag; a CLI-only hint. */
fun <R> InputSchemaElement<R>.cliPositional() = copy(spec = spec.copy(cliPositional = true))

/** Overrides the CLI flag (default `--<name>`); a CLI-only hint absent from the MCP `inputSchema`. */
fun <R> InputSchemaElement<R>.cliFlag(flag: String) = copy(spec = spec.copy(cliFlag = flag))

/** Hides this parameter from the CLI (still part of the MCP schema); a CLI-only hint. */
fun <R> InputSchemaElement<R>.cliHidden() = copy(spec = spec.copy(cliHidden = true))

/**
 * Marks this parameter as CLI-optional even when [required]; a CLI-only hint that never touches the MCP
 * `inputSchema`, which keeps it required.
 */
fun <R> InputSchemaElement<R>.cliOptional() = copy(spec = spec.copy(cliOptional = true))

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
        enumValues = values.keys.toList(),
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
