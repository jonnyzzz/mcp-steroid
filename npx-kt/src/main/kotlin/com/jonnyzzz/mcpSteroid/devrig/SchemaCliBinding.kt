/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One Clikt parameter binding per non-hidden [InputSchemaParamSpec], created from tool metadata (issue
 * #284). Clikt converts the raw token exactly once — into an `Int`, `Double`, `Boolean`, `String`, or
 * typed list held by the stored delegate — and [appendTo] serializes that already-typed value
 * straight into the tool-call [JsonObject]. There is no `Map<String, String?>` staging, no `toString()`
 * round trip, and no `split(delimiter)`/`toInt()`/`toDouble()`/`toBoolean()` re-parse: a
 * typed→string→typed detour would parse the same value twice. `ToolSpec.call()` stays the authoritative
 * tool parser.
 *
 * A binding maps a value to JSON only when the parameter is present. An absent optional value — including
 * an unset boolean — contributes no key, so a tool default owned by `ToolSpec.call()` (e.g.
 * `trust_project = true`) is never overwritten by a CLI-synthesized `false`.
 */
class SchemaCliBinding private constructor(
    val spec: InputSchemaParamSpec,
    private val encode: () -> JsonElement?,
) {
    /** Serializes the typed delegate value into [builder] under [spec] name; a no-op when the value is absent. */
    fun appendTo(builder: JsonObjectBuilder) {
        val value = encode() ?: return
        builder.put(spec.name, value)
    }

    companion object {
        /**
         * Binds every non-hidden parameter in [specs] onto [command], preserving declaration order.
         *
         * When [optionalizeRequired] is true, an MCP-required parameter is bound WITHOUT Clikt
         * `.required()`, so parsing does not abort at Clikt finalization before the command's `run()` gets
         * a chance to short-circuit `--help`; the generated command then re-checks presence itself after
         * that short-circuit. Left false (the default), an MCP-required parameter is a Clikt-required
         * option — the standalone binding contract exercised without a `run()` help hook (issue #284).
         */
        fun bindAll(
            command: CliktCommand,
            specs: List<InputSchemaParamSpec>,
            optionalizeRequired: Boolean = false,
        ): List<SchemaCliBinding> =
            specs.filterNot { it.cliHidden }.map { bind(command, it, optionalizeRequired) }

        /**
         * Creates and registers a single typed Clikt binding for [spec] on [command]. An enum ([spec]
         * `enumValues`) becomes a Clikt `choice`; a numeric `minimum`/`maximum` from [spec] `extra` becomes
         * a Clikt `restrictTo`, so an out-of-range value is a parse-time USAGE error rather than a backend
         * error. A parameter counts as required only when it is MCP-required, not CLI-optional, and
         * [optionalizeRequired] is false.
         */
        fun bind(
            command: CliktCommand,
            spec: InputSchemaParamSpec,
            optionalizeRequired: Boolean = false,
        ): SchemaCliBinding {
            require(!spec.cliHidden) { "cliHidden parameter ${spec.name} must not be bound to the CLI" }
            val required = isRequired(spec) && !optionalizeRequired
            return if (spec.cliPositional) bindArgument(command, spec, required) else bindOption(command, spec, required)
        }

        private fun isRequired(spec: InputSchemaParamSpec): Boolean = spec.required && !spec.cliOptional

        private fun bindOption(command: CliktCommand, spec: InputSchemaParamSpec, required: Boolean): SchemaCliBinding {
            val flag = spec.cliFlag
            return when (spec.type) {
                "boolean" -> {
                    // A nullable flag: absent stays null (omitted from JSON), presence becomes true.
                    val option = command.option(flag).nullableFlag()
                    command.registerOption(option)
                    SchemaCliBinding(spec) { option.value?.let { JsonPrimitive(it) } }
                }

                "integer" -> {
                    val typed = command.option(flag).int().applyIntRange(spec)
                    val option = if (required) typed.required() else typed
                    command.registerOption(option)
                    SchemaCliBinding(spec) { option.value?.let { JsonPrimitive(it) } }
                }

                "number" -> {
                    val typed = command.option(flag).double().applyDoubleRange(spec)
                    val option = if (required) typed.required() else typed
                    command.registerOption(option)
                    SchemaCliBinding(spec) { option.value?.let { JsonPrimitive(it) } }
                }

                "array" -> {
                    // Repeated occurrences (`--flag=a --flag=b`) — never a delimiter protocol.
                    when (spec.arrayItemType()) {
                        "string" -> {
                            val option = command.option(flag).multiple(required = required)
                            command.registerOption(option)
                            SchemaCliBinding(spec) { option.value.toJsonArrayOrNull() }
                        }
                        "integer" -> {
                            val option = command.option(flag).int().multiple(required = required)
                            command.registerOption(option)
                            SchemaCliBinding(spec) { option.value.toJsonArrayOrNull() }
                        }
                        "number" -> {
                            val option = command.option(flag).double().multiple(required = required)
                            command.registerOption(option)
                            SchemaCliBinding(spec) { option.value.toJsonArrayOrNull() }
                        }
                        "boolean" -> {
                            val option = command.option(flag).choice("true" to true, "false" to false)
                                .multiple(required = required)
                            command.registerOption(option)
                            SchemaCliBinding(spec) { option.value.toJsonArrayOrNull() }
                        }
                        else -> error("unreachable")
                    }
                }

                "string" -> {
                    val enumValues = spec.enumValues
                    if (enumValues != null) {
                        val typed = command.option(flag).choice(*enumValues.toTypedArray())
                        val option = if (required) typed.required() else typed
                        command.registerOption(option)
                        SchemaCliBinding(spec) { option.value?.let { JsonPrimitive(it) } }
                    } else {
                        val typed = command.option(flag)
                        val option = if (required) typed.required() else typed
                        command.registerOption(option)
                        SchemaCliBinding(spec) { option.value?.let { JsonPrimitive(it) } }
                    }
                }

                else -> error("unsupported CLI parameter type '${spec.type}' for option ${spec.name}")
            }
        }

        private fun bindArgument(command: CliktCommand, spec: InputSchemaParamSpec, required: Boolean): SchemaCliBinding {
            return when (spec.type) {
                "array" -> {
                    when (spec.arrayItemType()) {
                        "string" -> {
                            val argument = command.argument(spec.name).multiple(required = required)
                            command.registerArgument(argument)
                            SchemaCliBinding(spec) { argument.value.toJsonArrayOrNull() }
                        }
                        "integer" -> {
                            val argument = command.argument(spec.name).int().multiple(required = required)
                            command.registerArgument(argument)
                            SchemaCliBinding(spec) { argument.value.toJsonArrayOrNull() }
                        }
                        "number" -> {
                            val argument = command.argument(spec.name).double().multiple(required = required)
                            command.registerArgument(argument)
                            SchemaCliBinding(spec) { argument.value.toJsonArrayOrNull() }
                        }
                        "boolean" -> {
                            val argument = command.argument(spec.name).choice("true" to true, "false" to false)
                                .multiple(required = required)
                            command.registerArgument(argument)
                            SchemaCliBinding(spec) { argument.value.toJsonArrayOrNull() }
                        }
                        else -> error("unreachable")
                    }
                }

                "string" -> {
                    val typed = command.argument(spec.name)
                    val argument = if (required) typed else typed.optional()
                    command.registerArgument(argument)
                    SchemaCliBinding(spec) { argument.value?.let { JsonPrimitive(it) } }
                }

                else -> error("unsupported CLI parameter type '${spec.type}' for positional ${spec.name}")
            }
        }

        private fun List<*>.toJsonArrayOrNull(): JsonElement? =
            takeIf { it.isNotEmpty() }?.let { values ->
                JsonArray(values.map { value ->
                    when (value) {
                        is String -> JsonPrimitive(value)
                        is Int -> JsonPrimitive(value)
                        is Double -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> error("unsupported typed CLI array value ${value?.let { it::class.simpleName }}")
                    }
                })
            }

        private fun InputSchemaParamSpec.arrayItemType(): String {
            val itemType = buildJsonObject { extra(this) }["items"]
                ?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"
            require(itemType in setOf("string", "integer", "number", "boolean")) {
                "unsupported CLI array item type '$itemType' for $name; use string, integer, number, or boolean"
            }
            return itemType
        }
    }
}

/** Serializes the typed value of every binding into one [JsonObject], omitting the absent ones. */
fun List<SchemaCliBinding>.toJsonObject(): JsonObject = buildJsonObject { forEach { it.appendTo(this) } }

/**
 * The numeric `minimum`/`maximum` recorded in [spec] `extra` (JSON-schema keywords), each null when that
 * bound is absent; null overall when neither is set. Read to reuse the schema's own bounds as Clikt
 * `restrictTo` constraints — never to re-parse a value. One-sided bounds are honoured independently.
 */
private fun InputSchemaParamSpec.numericBounds(): Pair<Double?, Double?>? {
    val extra = buildJsonObject { extra(this) }
    val min = extra["minimum"]?.jsonPrimitive?.doubleOrNull
    val max = extra["maximum"]?.jsonPrimitive?.doubleOrNull
    return if (min == null && max == null) null else min to max
}

private fun OptionWithValues<Int?, Int, Int>.applyIntRange(spec: InputSchemaParamSpec) =
    spec.numericBounds()?.let { (min, max) -> restrictTo(min?.toInt(), max?.toInt()) } ?: this

private fun OptionWithValues<Double?, Double, Double>.applyDoubleRange(spec: InputSchemaParamSpec) =
    spec.numericBounds()?.let { (min, max) -> restrictTo(min, max) } ?: this
