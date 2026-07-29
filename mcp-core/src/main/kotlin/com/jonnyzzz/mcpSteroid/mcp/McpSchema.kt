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

/**
 * A second CLI source for one parameter's value: an option whose argument is a filesystem path — or
 * `-` for standard input — and whose *content* becomes the value of the parameter that declares it.
 * Declaration only; opening the file is the CLI frontend's job.
 *
 * Because both [InputSchemaParamSpec.cliFlag] and [flag] fill the same value, a CLI frontend rejects
 * being given both without needing any per-parameter rule: the exclusivity follows from the shape.
 */
data class CliFileSource(
    /** The CLI option that takes the path, e.g. `--code-file`. Never `--<name>` of the parameter itself. */
    val flag: String,
    /** Short one-line help for [flag]; [InputSchemaParamSpec.cliSynopsis] describes the direct form. */
    val synopsis: String,
)

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
    /**
     * Short one-line flag help for the CLI. Required for every CLI-visible parameter (see
     * [ToolSchema.asCliParams]); [InputSchemaElement.Companion.param] seeds it with a temporary empty
     * string that a real call site must overwrite via [cliSynopsis].
     */
    val cliSynopsis: String,
    /**
     * An alternate CLI source for *this* parameter's value — a path-taking flag whose file content is
     * the value; null when the CLI offers only the direct form. See [CliFileSource] and the
     * [InputSchemaElement.cliFileSource] builder. Read only by the CLI projection; never affects
     * [ToolSchema.asMcpJson], because the parameter itself is an ordinary MCP parameter either way.
     */
    val cliFileSource: CliFileSource? = null,
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
    /**
     * CLI-only lower bound for a numeric parameter, enforced by the CLI frontend, not by the MCP
     * `inputSchema` (which has its own, unrelated `minimum`/`maximum` extras). Only valid when [type]
     * is `"integer"` or `"number"`; see [cliMinimum] builder.
     */
    val cliMinimum: Double? = null,
    /** CLI-only upper bound for a numeric parameter; see [cliMinimum] for the constraints. */
    val cliMaximum: Double? = null,
    /** Curated wording the CLI shows when this parameter is missing; data only, rendered by the CLI. */
    val cliMissingHint: String? = null,
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

    /**
     * Registers [e] into this schema, preserving declaration order, and returns it for chaining.
     * Fails fast when [e] declares a [CliFileSource] the CLI could never use on its own: a parameter
     * the CLI still demands directly (MCP-[InputSchemaParamSpec.required] and not
     * [InputSchemaParamSpec.cliOptional]) gains nothing from a path-taking alternative.
     */
    fun <R> register(e: InputSchemaElement<R>): InputSchemaElement<R> {
        val fileSource = e.spec.cliFileSource
        require(fileSource == null || !e.spec.required || e.spec.cliOptional) {
            "Parameter '${e.spec.name}' declares the CLI file source '${fileSource?.flag}' but the CLI " +
                "would still demand it directly; also declare cliOptional() so '${fileSource?.flag}' " +
                "alone is accepted"
        }
        elements.add(e)
        return e
    }

    /** MCP form: the JSON `inputSchema` sent to MCP clients; CLI metadata is never part of it. */
    fun asMcpJson(): JsonObject = InputSchemaElement.buildSchema(elements)

    /**
     * CLI form — the parameter metadata only; the parsers stay encapsulated on the elements. Fails
     * fast when a CLI-visible (non-[InputSchemaParamSpec.cliHidden]) parameter has a blank
     * [InputSchemaParamSpec.cliSynopsis]: every parameter the CLI shows the user must carry its own
     * one-line help rather than silently falling back to the (often much longer) MCP [description].
     */
    fun asCliParams(): List<InputSchemaParamSpec> = elements.map { it.spec }.onEach { spec ->
        require(spec.cliHidden || spec.cliSynopsis.isNotBlank()) {
            "Parameter '${spec.name}' is CLI-visible but has no cliSynopsis"
        }
    }
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
    spec = InputSchemaParamSpec(name = name, description = "Not Set", type = "Error", required = false, cliSynopsis = ""),
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

/**
 * Declares that the CLI also accepts this parameter's value as the *content of a file*: [flag] takes a
 * path (or `-` for standard input) and the text it yields becomes this parameter's value. The parameter
 * stays an ordinary MCP parameter — only the path form is CLI-exclusive — so this never changes
 * [ToolSchema.asMcpJson]. See [CliFileSource].
 *
 * Chain it after the type builder (`.string()`), and pair it with [cliOptional] when the parameter is
 * [required] so the CLI accepts [flag] on its own — [ToolSchema.register] fails fast otherwise.
 */
fun <R> InputSchemaElement<R>.cliFileSource(flag: String, synopsis: String): InputSchemaElement<R> {
    require(spec.type == "string") {
        "cliFileSource feeds file text into '${spec.name}', so it needs a string parameter, but the type " +
            "is '${spec.type}' (declare .string() before .cliFileSource())"
    }
    require(flag.startsWith("--")) {
        "cliFileSource flag for '${spec.name}' must be a long option starting with '--', was '$flag'"
    }
    require(flag != spec.cliFlag) {
        "cliFileSource flag for '${spec.name}' must differ from the parameter's own flag '$flag'"
    }
    require(synopsis.isNotBlank()) { "cliFileSource '$flag' for '${spec.name}' needs a one-line synopsis" }
    return copy(spec = spec.copy(cliFileSource = CliFileSource(flag = flag, synopsis = synopsis)))
}

/**
 * CLI-only lower bound, enforced by the CLI frontend; never serialized into the MCP `inputSchema`.
 * Fails fast unless the element's type is `"integer"` or `"number"`.
 */
fun <R> InputSchemaElement<R>.cliMinimum(value: Double): InputSchemaElement<R> {
    require(spec.type == "integer" || spec.type == "number") {
        "cliMinimum requires a numeric parameter, but '${spec.name}' has type '${spec.type}'"
    }
    return copy(spec = spec.copy(cliMinimum = value))
}

/**
 * CLI-only upper bound, enforced by the CLI frontend; never serialized into the MCP `inputSchema`.
 * Fails fast unless the element's type is `"integer"` or `"number"`.
 */
fun <R> InputSchemaElement<R>.cliMaximum(value: Double): InputSchemaElement<R> {
    require(spec.type == "integer" || spec.type == "number") {
        "cliMaximum requires a numeric parameter, but '${spec.name}' has type '${spec.type}'"
    }
    return copy(spec = spec.copy(cliMaximum = value))
}

/**
 * Curated wording the CLI shows when this parameter is missing (e.g. naming an env var or flag);
 * data only — rendering happens in the CLI frontend, never here. Absent from the MCP `inputSchema`.
 */
fun <R> InputSchemaElement<R>.cliMissingHint(text: String) = copy(spec = spec.copy(cliMissingHint = text))

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
