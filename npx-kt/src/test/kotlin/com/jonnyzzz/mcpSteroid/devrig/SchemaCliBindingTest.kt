/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.UsageError
import com.jonnyzzz.mcpSteroid.mcp.CliExtraOption
import com.jonnyzzz.mcpSteroid.mcp.CliFileSource
import com.jonnyzzz.mcpSteroid.mcp.CliOptionType
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaParamSpec
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolSpec
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolSpec
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mapping contract of [SchemaCliBinding]: one declared [InputSchemaParamSpec] in, one typed Clikt
 * parameter out, one already-typed JSON value back — plus the two side channels that must NEVER leak into
 * the tool call (a [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] path and a
 * [CliExtraOption] value).
 *
 * Every test drives the binding the way the generated command will: build a throwaway [CliktCommand],
 * bind metadata onto it, `parse(...)`, read [SchemaCliValues]. Tests use real tool specs wherever the
 * declaration they need already exists (`execute_code`, `execute_feedback`, `open_project`) and synthetic
 * specs only for shapes no tool declares yet (arrays, positionals, bound combinations), so the mapping is
 * pinned even before a tool uses it.
 *
 * Clikt behaviors these rely on (eager `--help` before required-option validation, `MissingOption` exposing
 * only `paramName`, `nullableFlag()` preserving absence) are pinned separately in [CliktBehaviorContractTest].
 */
class SchemaCliBindingTest {

    // ------------------------------- throwaway commands -------------------------------

    /** Binds raw parameter metadata; the shape the generated command will have, minus the tool plumbing. */
    private class BindingCommand(
        params: List<InputSchemaParamSpec>,
        extraOptions: List<CliExtraOption> = emptyList(),
    ) : CliktCommand(name = "bind") {
        val binding = SchemaCliBinding.bind(this, params, extraOptions)
        lateinit var values: SchemaCliValues

        override fun run() {
            values = binding.parsed()
        }
    }

    /** Binds a whole [CliToolSpec] — the entry point the generated per-tool command uses. */
    private class ToolCommand(spec: CliToolSpec) : CliktCommand(name = spec.cli.name) {
        val binding = SchemaCliBinding.bind(this, spec)
        lateinit var values: SchemaCliValues

        override fun run() {
            values = binding.parsed()
        }
    }

    private fun bind(params: List<InputSchemaParamSpec>, vararg args: String): SchemaCliValues =
        BindingCommand(params).also { it.parse(args.toList()) }.values

    private fun bind(spec: CliToolSpec, vararg args: String): SchemaCliValues =
        ToolCommand(spec).also { it.parse(args.toList()) }.values

    // ------------------------------- fixtures -------------------------------

    /** The canonical tool list, wired to a tool set that fails if any handler is resolved while parsing. */
    private class NoHandlerTools : McpSteroidTools() {
        override fun <T> handler(type: Class<T>): T =
            error("no handler may be resolved while parsing: ${type.name}")
    }

    /** A spec double whose handler must never be resolved: parsing reads metadata only. */
    private fun executeCode() = ExecuteCodeToolSpec { error("no handler may be resolved while parsing") }

    private fun executeFeedback() = ExecuteFeedbackToolSpec { error("no handler may be resolved while parsing") }

    private fun openProject() =
        OpenProjectToolSpec(includeBackendName = true) { error("no handler may be resolved while parsing") }

    private fun param(name: String, type: String, required: Boolean = false) = InputSchemaParamSpec(
        name = name,
        type = type,
        description = "",
        required = required,
        cliSynopsis = "synopsis of $name",
    )

    private fun arrayParam(name: String, itemType: String, positional: Boolean = false) = param(name, "array").copy(
        cliPositional = positional,
        extra = { putJsonObject("items") { put("type", itemType) } },
    )

    private fun toolParam(spec: CliToolSpec, name: String): InputSchemaParamSpec =
        spec.schema.asCliParams().single { it.name == name }

    // ------------------------------- type mapping -------------------------------

    @Test
    fun `a string parameter maps to a JSON string`() {
        val values = bind(listOf(param("who", "string")), "--who=world")

        assertEquals("world", values.arguments["who"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an enum parameter maps through Clikt choice to a JSON string`() {
        val modal = toolParam(executeCode(), "modal")

        val values = bind(listOf(modal), "--modal=non_modal")

        assertEquals("non_modal", values.arguments["modal"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a value outside the declared enum is a parse-time usage error`() {
        val modal = toolParam(executeCode(), "modal")

        assertFailsWith<UsageError> { BindingCommand(listOf(modal)).parse(listOf("--modal=bogus")) }
    }

    @Test
    fun `an integer parameter maps to a JSON number, never a quoted string`() {
        val timeout = toolParam(executeCode(), "timeout")

        val values = bind(listOf(timeout), "--timeout=42")

        val timeoutValue = values.arguments["timeout"]?.jsonPrimitive
        assertEquals(42, timeoutValue?.intOrNull)
        assertFalse(timeoutValue!!.isString, "an integer must serialize as a JSON number")
    }

    @Test
    fun `a non-numeric integer is a parse-time usage error`() {
        val timeout = toolParam(executeCode(), "timeout")

        assertFailsWith<UsageError> { BindingCommand(listOf(timeout)).parse(listOf("--timeout=soon")) }
    }

    @Test
    fun `a number parameter maps to a JSON number, never a quoted string`() {
        val rating = toolParam(executeFeedback(), "success_rating")

        val values = bind(listOf(rating), "--success_rating=0.75")

        val ratingValue = values.arguments["success_rating"]?.jsonPrimitive
        assertEquals(0.75, ratingValue?.doubleOrNull)
        assertFalse(ratingValue!!.isString, "a number must serialize as a JSON number")
    }

    @Test
    fun `an optional boolean present becomes true`() {
        val trust = toolParam(openProject(), "trust_project")

        val values = bind(listOf(trust), "--trust_project")

        assertEquals(true, values.arguments["trust_project"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `an optional boolean can be turned off through its negative flag`() {
        // Without a negative spelling `false` is unreachable: nullableFlag() with no secondary name has
        // arity 0..0, so `--trust_project` is the only accepted form and `--trust_project=false` fails as
        // IncorrectOptionValueCount. open_project's trust_project defaults to true tool-side, so without
        // this there is no way to open a project WITHOUT pre-trusting it — the one choice IntelliJ's trust
        // dialog exists for.
        val trust = toolParam(openProject(), "trust_project")

        val values = bind(listOf(trust), "--no-trust_project")

        val value = values.arguments["trust_project"]?.jsonPrimitive
        assertEquals(false, value?.boolean)
        assertFalse(value!!.isString, "a boolean must serialize as a JSON boolean, not a string")
    }

    @Test
    fun `an attached value on an optional boolean is rejected rather than silently ignored`() {
        // The negative flag is the supported spelling; `--flag=false` must not quietly parse as `true`.
        val trust = toolParam(openProject(), "trust_project")

        assertFailsWith<UsageError> { BindingCommand(listOf(trust)).parse(listOf("--trust_project=false")) }
    }

    @Test
    fun `a negative boolean flag maps back to the parameter that declares it`() {
        val trust = toolParam(openProject(), "trust_project")

        val command = BindingCommand(listOf(trust))

        assertEquals("trust_project", command.binding.paramFor("--no-trust_project")?.name)
    }

    @Test
    fun `a required boolean must be supplied in one of its two spellings`() {
        // Clikt's .required() does not apply to a flag pair, so the file's one requiredness rule
        // (required && !cliOptional) would otherwise have a boolean-shaped hole: absence would reach the
        // backend as a tool error, skipping the paramName -> paramFor -> cliMissingHint chain entirely.
        // Because the negative spelling exists, absence IS distinguishable from a deliberate false, so the
        // CLI can demand one of them. No tool declares a required boolean today.
        val flag = param("dry_run", "boolean", required = true).copy(cliMissingHint = "say --dry_run or --no-dry_run")

        val command = BindingCommand(listOf(flag))
        val error = assertFailsWith<UsageError> { command.parse(emptyList()) }

        assertTrue("--dry_run" in error.message!!, error.message!!)
        assertTrue("--no-dry_run" in error.message!!, error.message!!)
        assertEquals("--dry_run", error.paramName)
        assertEquals("say --dry_run or --no-dry_run", command.binding.paramFor(error.paramName!!)?.cliMissingHint)
    }

    @Test
    fun `a required boolean is satisfied by either spelling`() {
        val flag = param("dry_run", "boolean", required = true)

        assertEquals(true, bind(listOf(flag), "--dry_run").arguments["dry_run"]?.jsonPrimitive?.boolean)
        assertEquals(false, bind(listOf(flag), "--no-dry_run").arguments["dry_run"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `a CLI-optional required boolean is not demanded, and still contributes no key`() {
        // The gate is cliRequired, not required: a parameter the CLI can supply itself is never demanded,
        // exactly as for every other type.
        val flag = param("dry_run", "boolean", required = true).copy(cliOptional = true)

        assertTrue(bind(listOf(flag)).arguments.isEmpty())
    }

    @Test
    fun `an omitted optional boolean contributes no key, so the tool default survives`() {
        // open_project's trust_project defaults to TRUE inside the tool. A CLI-synthesized `false` would
        // flip it, silently re-enabling the trust dialog for every `devrig open_project` without the flag.
        val trust = toolParam(openProject(), "trust_project")

        val values = bind(listOf(trust))

        assertFalse(values.arguments.containsKey("trust_project"), "got ${values.arguments}")
    }

    // ------------------------------- arrays -------------------------------

    @Test
    fun `an array maps repeated occurrences to a JSON array`() {
        val values = bind(listOf(arrayParam("tag", "string")), "--tag=a", "--tag=b")

        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), values.arguments["tag"])
    }

    @Test
    fun `an array never splits one occurrence on a delimiter`() {
        val values = bind(listOf(arrayParam("tag", "string")), "--tag=a,b")

        assertEquals(JsonArray(listOf(JsonPrimitive("a,b"))), values.arguments["tag"])
    }

    @Test
    fun `the declared array item type drives the JSON primitive types`() {
        val values = bind(
            listOf(arrayParam("count", "integer"), arrayParam("ratio", "number"), arrayParam("on", "boolean")),
            "--count=2", "--count=7", "--ratio=0.5", "--on=true", "--on=false",
        )

        assertEquals(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(7))), values.arguments["count"])
        assertEquals(JsonArray(listOf(JsonPrimitive(0.5))), values.arguments["ratio"])
        assertEquals(listOf(true, false), values.arguments["on"]!!.jsonArray.map { it.jsonPrimitive.boolean })
    }

    @Test
    fun `an unsupported array item type fails while the command is built`() {
        val error = assertFailsWith<IllegalArgumentException> { BindingCommand(listOf(arrayParam("entry", "object"))) }

        assertTrue("entry" in error.message!!, error.message!!)
        assertTrue("object" in error.message!!, error.message!!)
    }

    @Test
    fun `an omitted array contributes no key`() {
        val values = bind(listOf(arrayParam("tag", "string")))

        assertFalse(values.arguments.containsKey("tag"), "got ${values.arguments}")
    }

    // ------------------------------- positionals -------------------------------

    @Test
    fun `a positional parameter binds as a Clikt argument`() {
        val uri = param("uri", "string").copy(cliPositional = true)

        val values = bind(listOf(uri), "mcp-steroid://skill/design-philosophy")

        assertEquals("mcp-steroid://skill/design-philosophy", values.arguments["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a positional parameter retains its hidden flag as a compatibility spelling`() {
        val uri = param("uri", "string", required = true).copy(cliPositional = true)

        val values = bind(listOf(uri), "--uri=mcp-steroid://skill/design-philosophy")

        assertEquals("mcp-steroid://skill/design-philosophy", values.arguments["uri"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a positional and its compatibility flag cannot both supply the same parameter`() {
        val uri = param("uri", "string", required = true).copy(cliPositional = true)

        val error = assertFailsWith<UsageError> {
            BindingCommand(listOf(uri)).parse(listOf("first", "--uri=second"))
        }

        assertTrue("not both" in error.message.orEmpty(), error.message.orEmpty())
    }

    @Test
    fun `a positional array binds as a repeated Clikt argument`() {
        val values = bind(listOf(arrayParam("count", "integer", positional = true)), "2", "7")

        assertEquals(JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(7))), values.arguments["count"])
    }

    @Test
    fun `an omitted optional positional contributes no key`() {
        val uri = param("uri", "string").copy(cliPositional = true)

        val values = bind(listOf(uri))

        assertTrue(values.arguments.isEmpty(), "got ${values.arguments}")
    }

    // ------------------------------- bounds -------------------------------

    @Test
    fun `a CLI-only minimum is enforced at parse time`() {
        // execute_code's timeout declares cliMinimum(1.0) alongside the schema minimum=1 (#469);
        // both agree, and the CLI-only bound alone must already reject at parse time.
        val timeout = toolParam(executeCode(), "timeout")

        assertFailsWith<UsageError> { BindingCommand(listOf(timeout)).parse(listOf("--timeout=0")) }
    }

    @Test
    fun `a schema minimum and maximum from extra are enforced at parse time`() {
        // success_rating carries minimum=0.0 / maximum=1.0 as JSON-schema extras: out of range must be a
        // USAGE error during parsing, never a backend tool error.
        val rating = toolParam(executeFeedback(), "success_rating")

        assertFailsWith<UsageError> { BindingCommand(listOf(rating)).parse(listOf("--success_rating=-0.5")) }
        assertFailsWith<UsageError> { BindingCommand(listOf(rating)).parse(listOf("--success_rating=2.0")) }
    }

    @Test
    fun `the tighter of the CLI minimum and the schema minimum applies`() {
        val bounded = param("n", "integer").copy(cliMinimum = 5.0, extra = { put("minimum", 1.0) })

        assertFailsWith<UsageError> { BindingCommand(listOf(bounded)).parse(listOf("--n=3")) }
        assertEquals(5, bind(listOf(bounded), "--n=5").arguments["n"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `the tighter of the CLI maximum and the schema maximum applies`() {
        val bounded = param("x", "number").copy(cliMaximum = 10.0, extra = { put("maximum", 100.0) })

        assertFailsWith<UsageError> { BindingCommand(listOf(bounded)).parse(listOf("--x=50")) }
        assertEquals(10.0, bind(listOf(bounded), "--x=10").arguments["x"]?.jsonPrimitive?.doubleOrNull)
    }

    // ------------------------------- requiredness and absence -------------------------------

    @Test
    fun `an MCP-required parameter that is not CLI-optional is required at parse time`() {
        val rating = toolParam(executeFeedback(), "success_rating")

        val error = assertFailsWith<MissingOption> { BindingCommand(listOf(rating)).parse(emptyList()) }
        assertEquals("--success_rating", error.paramName)
    }

    @Test
    fun `a CLI-optional MCP-required parameter is not demanded by the CLI`() {
        // The gate is cliRequired (required && !cliOptional), not required: a parameter the CLI can supply
        // itself is never demanded. No tool declares a required, file-source-free, cliOptional parameter
        // today, so this drives a synthetic one.
        val inferred = param("inferred", "string", required = true).copy(cliOptional = true)
        assertTrue(inferred.required && inferred.cliOptional, "fixture expects required + cliOptional")

        assertTrue(bind(listOf(inferred)).arguments.isEmpty())
    }

    @Test
    fun `a blank required string is rejected as a missing value, keeping its curated hint`() {
        // A present-but-empty required string (`--task_id=`) is no value at all: reported as MissingCliValue
        // so its cliMissingHint still reaches the user, never sent on to the tool as an empty string.
        val flag = param("task_id", "string", required = true).copy(cliMissingHint = "give a task id")

        val command = BindingCommand(listOf(flag))
        val error = assertFailsWith<MissingCliValue> { command.parse(listOf("--task_id=")) }

        assertEquals("--task_id", error.paramName)
        assertEquals("give a task id", command.binding.paramFor(error.paramName!!)?.cliMissingHint)
    }

    @Test
    fun `a whitespace-only required string is blank too`() {
        val flag = param("task_id", "string", required = true)

        assertFailsWith<MissingCliValue> { BindingCommand(listOf(flag)).parse(listOf("--task_id=   ")) }
    }

    @Test
    fun `a blank value for an optional string is left alone`() {
        // Only a REQUIRED string may not be blank; an optional one keeps whatever it was given, empty or not.
        val values = bind(listOf(param("note", "string")), "--note=")

        assertEquals("", values.arguments["note"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a repeated single-value option is a usage error, not last-wins`() {
        val error =
            assertFailsWith<UsageError> { BindingCommand(listOf(param("who", "string"))).parse(listOf("--who=a", "--who=b")) }

        assertTrue("--who" in error.message!!, error.message!!)
        assertEquals("--who", error.paramName)
    }

    @Test
    fun `a repeated required single-value option is a usage error`() {
        val flag = param("who", "string", required = true)

        assertFailsWith<UsageError> { BindingCommand(listOf(flag)).parse(listOf("--who=a", "--who=b")) }
    }

    @Test
    fun `a single-value option given once still yields its value`() {
        assertEquals("a", bind(listOf(param("who", "string")), "--who=a").arguments["who"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a boolean flag together with its negative spelling is a usage error`() {
        // nullableFlag keeps the last occurrence, so `--trust_project --no-trust_project` would silently
        // resolve to false; the two spellings contradict, so the CLI rejects the pair instead of ordering it.
        val trust = toolParam(openProject(), "trust_project")

        val error = assertFailsWith<UsageError> {
            BindingCommand(listOf(trust)).parse(listOf("--trust_project", "--no-trust_project"))
        }

        assertTrue("--trust_project" in error.message!!, error.message!!)
        assertTrue("--no-trust_project" in error.message!!, error.message!!)
    }

    @Test
    fun `absent optional values contribute no keys`() {
        val values = bind(listOf(param("who", "string"), param("timeout", "integer"), param("ratio", "number")))

        assertTrue(values.arguments.isEmpty(), "got ${values.arguments}")
    }

    @Test
    fun `a cliHidden parameter is neither bound nor serialized`() {
        val hidden = param("secret", "string").copy(cliHidden = true)

        // Not registered at all, so its flag is an unknown option rather than a value.
        assertFailsWith<UsageError> { BindingCommand(listOf(hidden)).parse(listOf("--secret=x")) }
        assertTrue(bind(listOf(hidden)).arguments.isEmpty())
    }

    @Test
    fun `declaration order is preserved in the tool-call arguments`() {
        val spec = executeFeedback()

        val values = bind(
            spec,
            "--explanation=ok", "--success_rating=0.9", "--task_id=t1", "--project_name=key",
        )

        assertEquals(listOf("project_name", "task_id", "success_rating", "explanation"), values.arguments.keys.toList())
    }

    @Test
    fun `a duplicate CLI flag across two parameters fails while the command is built`() {
        val first = param("a", "string").copy(cliFlag = "--same")
        val second = param("b", "string").copy(cliFlag = "--same")

        val error = assertFailsWith<IllegalArgumentException> { BindingCommand(listOf(first, second)) }

        assertTrue("--same" in error.message!!, error.message!!)
    }

    @Test
    fun `two extra options claiming one flag fail while the command is built`() {
        // Clikt keeps one option per name, so the second registration would silently shadow the first.
        // Two DIFFERENT names sharing one explicit flag, so this exercises the flag rule alone — with two
        // identical names it would also trip the name rule below and neither would be isolated.
        val extras = listOf(
            CliExtraOption(name = "wait", type = CliOptionType.BOOLEAN, synopsis = "poll until ready", flag = "--hold"),
            CliExtraOption(name = "linger", type = CliOptionType.BOOLEAN, synopsis = "something else", flag = "--hold"),
        )

        val error = assertFailsWith<IllegalArgumentException> { BindingCommand(emptyList(), extras) }

        assertTrue("--hold" in error.message!!, error.message!!)
    }

    @Test
    fun `two extra options claiming one name fail while the command is built`() {
        // The name is the runtime's key into SchemaCliValues.extraOptions, so a duplicate would silently
        // drop one of the two values instead of shadowing a flag.
        val extras = listOf(
            CliExtraOption(name = "wait", type = CliOptionType.BOOLEAN, synopsis = "poll until ready", flag = "--wait"),
            CliExtraOption(name = "wait", type = CliOptionType.BOOLEAN, synopsis = "something else", flag = "--linger"),
        )

        val error = assertFailsWith<IllegalArgumentException> { BindingCommand(emptyList(), extras) }

        assertTrue("wait" in error.message!!, error.message!!)
    }

    @Test
    fun `an extra option claiming a parameter's flag fails while the command is built`() {
        val extra = CliExtraOption(name = "who", type = CliOptionType.BOOLEAN, synopsis = "collides")

        val error = assertFailsWith<IllegalArgumentException> {
            BindingCommand(listOf(param("who", "string")), listOf(extra))
        }

        assertTrue("--who" in error.message!!, error.message!!)
    }

    @Test
    fun `an extra option claiming an optional boolean's negative flag fails while the command is built`() {
        val extra = CliExtraOption(name = "no-cache", type = CliOptionType.BOOLEAN, synopsis = "collides")

        val error = assertFailsWith<IllegalArgumentException> {
            BindingCommand(listOf(param("cache", "boolean")), listOf(extra))
        }

        assertTrue("--no-cache" in error.message!!, error.message!!)
    }

    // ------------------------------- file sources -------------------------------

    @Test
    fun `a file source flag yields a deferred path and no tool argument`() {
        val values = bind(
            executeCode(),
            "--code-file=repro.kts", "--task_id=t1", "--reason=reproduce", "--project_name=key",
        )

        assertEquals(mapOf("code" to "repro.kts"), values.fileSources)
        assertFalse(values.arguments.containsKey("code"), "the path must never be sent as the value: ${values.arguments}")
    }

    @Test
    fun `a file source path is not read while parsing`() {
        // Reading the file (and stdin for "-") is runtime work. Parsing a path that cannot be opened must
        // still succeed: the binding only records it.
        val values = bind(
            executeCode(),
            "--code-file=/no/such/directory/repro.kts", "--task_id=t1", "--reason=reproduce", "--project_name=key",
        )

        assertEquals(mapOf("code" to "/no/such/directory/repro.kts"), values.fileSources)
    }

    @Test
    fun `a stdin file source is recorded verbatim, never consumed while parsing`() {
        val values = bind(executeCode(), "--code-file=-", "--task_id=t1", "--reason=reproduce", "--project_name=key")

        assertEquals(mapOf("code" to "-"), values.fileSources)
    }

    @Test
    fun `the direct flag alone yields the value and no file source`() {
        val values = bind(executeCode(), "--code=println(1)", "--task_id=t1", "--reason=reproduce", "--project_name=key")

        assertEquals("println(1)", values.arguments["code"]?.jsonPrimitive?.content)
        assertTrue(values.fileSources.isEmpty(), "got ${values.fileSources}")
    }

    @Test
    fun `the direct flag together with its file source is a usage error`() {
        val error = assertFailsWith<UsageError> {
            ToolCommand(executeCode()).parse(
                listOf("--code=println(1)", "--code-file=repro.kts", "--task_id=t1", "--reason=reproduce", "--project_name=key")
            )
        }

        assertTrue("--code" in error.message!!, error.message!!)
        assertTrue("--code-file" in error.message!!, error.message!!)
    }

    @Test
    fun `neither the direct flag nor its file source is a usage error for an MCP-required parameter`() {
        // execute_code's code is MCP-required and cliOptional (so Clikt does not demand --code on its own);
        // the exclusivity rule is what makes "one of the two" mandatory. No per-tool code is involved.
        val error = assertFailsWith<UsageError> {
            ToolCommand(executeCode()).parse(listOf("--task_id=t1", "--reason=reproduce", "--project_name=key"))
        }

        assertEquals("--code", error.paramName)
        assertTrue("--code-file" in error.message!!, error.message!!)
    }

    @Test
    fun `a parameter's own form is registered before its file source`() {
        // Registration order is the order generated help lists options in, so the direct form has to come
        // first: `--code` above `--code-file`, not the reverse.
        val command = ToolCommand(executeCode())

        val names = command.registeredOptions().map { it.names.single() }
        assertTrue(
            names.indexOf("--code") < names.indexOf("--code-file"),
            "expected --code before --code-file, got $names",
        )
    }

    @Test
    fun `a positional with a file source raises a usage error the lookup can resolve`() {
        // The flag/positional asymmetry: a positional is reported by its bare name, so keying the error by
        // cliFlag would produce a paramName ('--uri') that paramFor() cannot resolve, silently losing the
        // cliMissingHint substitution for this shape. No tool declares it today.
        val uri = param("uri", "string", required = true).copy(
            cliPositional = true,
            cliOptional = true,
            cliFileSource = CliFileSource(flag = "--uri-file", synopsis = "read the uri from a file"),
            cliMissingHint = "pass a uri or --uri-file",
        )

        val command = BindingCommand(listOf(uri))
        val error = assertFailsWith<UsageError> { command.parse(emptyList()) }

        assertEquals("uri", error.paramName)
        assertEquals("pass a uri or --uri-file", command.binding.paramFor(error.paramName!!)?.cliMissingHint)
    }

    @Test
    fun `an eager --help short-circuits before the file-source rule can reject the invocation`() {
        // The exclusivity rule runs where the parsed values are read, i.e. after Clikt finalization — so
        // `devrig execute_code --help` must print help instead of "code is required". Clikt's own eager
        // --help is what guarantees that (pinned for required options in CliktBehaviorContractTest).
        assertFailsWith<PrintHelpMessage> { ToolCommand(executeCode()).parse(listOf("--help")) }
    }

    @Test
    fun `an optional parameter with a file source may be omitted entirely`() {
        // execute_feedback's code is NOT MCP-required: omitting both forms is legal. Same binding code.
        val values = bind(
            executeFeedback(),
            "--task_id=t1", "--success_rating=0.9", "--explanation=ok", "--project_name=key",
        )

        assertFalse(values.arguments.containsKey("code"), "got ${values.arguments}")
        assertTrue(values.fileSources.isEmpty(), "got ${values.fileSources}")
    }

    @Test
    fun `both file-source shapes in the metadata today bind through the same path`() {
        for (spec in listOf<CliToolSpec>(executeCode(), executeFeedback())) {
            // Arguments derived from the metadata alone: a placeholder for every CLI-required parameter,
            // plus the file source. No tool name is branched on anywhere, here or in the binding.
            val args = spec.schema.asCliParams()
                .filter { it.required && !it.cliOptional && !it.cliHidden }
                .map { "${it.cliFlag}=${if (it.type == "number") "0.5" else "placeholder"}" } + "--code-file=snippet.kts"

            val values = bind(spec, *args.toTypedArray())

            assertEquals(mapOf("code" to "snippet.kts"), values.fileSources, "for ${spec.cli.name}")
            assertFalse(values.arguments.containsKey("code"), "for ${spec.cli.name}: ${values.arguments}")
        }
    }

    // ------------------------------- extra options -------------------------------

    @Test
    fun `a boolean extra option is collected separately and never reaches the tool arguments`() {
        val values = bind(
            openProject(),
            "--project_path=/tmp/p", "--task_id=t1", "--reason=open", "--wait",
        )

        assertEquals(mapOf("wait" to true), values.extraOptions)
        assertEquals(listOf("project_path", "task_id", "reason"), values.arguments.keys.toList())
    }

    @Test
    fun `an absent boolean extra option is false`() {
        val values = bind(openProject(), "--project_path=/tmp/p", "--task_id=t1", "--reason=open")

        assertEquals(mapOf("wait" to false), values.extraOptions)
    }

    @Test
    fun `an extra option is bound even when the tool declares no parameters`() {
        val extra = CliExtraOption(name = "wait", type = CliOptionType.BOOLEAN, synopsis = "wait for it")

        val values = BindingCommand(emptyList(), listOf(extra)).also { it.parse(listOf("--wait")) }.values

        assertEquals(mapOf("wait" to true), values.extraOptions)
        assertTrue(values.arguments.isEmpty(), "got ${values.arguments}")
    }

    @Test
    fun `an extra option is keyed by its name, not by its CLI spelling`() {
        // The runtime indexes this map to decide what to do (poll after open_project, …). Keying it by the
        // flag would silently break that lookup the moment the flag is respelled, which is exactly why
        // CliExtraOption carries a name distinct from its flag.
        val extra = CliExtraOption(name = "wait", type = CliOptionType.BOOLEAN, synopsis = "wait for it", flag = "--hold")

        val values = BindingCommand(emptyList(), listOf(extra)).also { it.parse(listOf("--hold")) }.values

        assertEquals(mapOf("wait" to true), values.extraOptions)
    }

    // ------------------------------- flag to parameter lookup -------------------------------

    @Test
    fun `the name Clikt reports for a missing option maps back to its declaring parameter`() {
        // What Task 6's error wording needs: MissingOption exposes only paramName (the longest declared
        // name), so the binding must be able to turn that string back into the spec carrying cliMissingHint.
        val command = ToolCommand(executeCode())

        val error = assertFailsWith<MissingOption> { command.parse(listOf("--code=x", "--reason=r", "--project_name=key")) }

        val spec = command.binding.paramFor(error.paramName!!)
        assertEquals("task_id", spec?.name)
        assertEquals("missing --task_id. Any string works; reuse it across related calls.", spec?.cliMissingHint)
    }

    @Test
    fun `a file source flag maps back to the parameter that declares it`() {
        val command = ToolCommand(executeCode())

        assertEquals("code", command.binding.paramFor("--code-file")?.name)
        assertEquals("code", command.binding.paramFor("--code")?.name)
    }

    @Test
    fun `a missing positional with a compatibility flag maps back to its declaring parameter`() {
        // The positional is optional at Clikt's argument layer because its hidden compatibility option can
        // satisfy the same value. The aggregate requiredness check still reports the positional's declared
        // name, so its cliMissingHint is reachable the same way an option's is.
        val uri = param("uri", "string", required = true).copy(cliPositional = true, cliMissingHint = "pass a uri")

        val command = BindingCommand(listOf(uri))
        val error = assertFailsWith<MissingCliValue> { command.parse(emptyList()) }

        val spec = command.binding.paramFor(error.paramName!!)
        assertEquals("uri", spec?.name)
        assertEquals("pass a uri", spec?.cliMissingHint)
    }

    // ------------------------------- every canonical tool -------------------------------

    @Test
    fun `every devrig tool spec binds, and every CLI-visible parameter is reachable by name`() {
        // The generic claim, guarded: bind() rejects an unsupportable declaration with
        // IllegalArgumentException while the command is CONSTRUCTED, so without this loop a future tool
        // declaring, say, an object array would surface as a devrig startup crash instead of a unit-test
        // failure. Drives the canonical devrigToolSpecs() list, not a hand-picked subset.
        val specs = NoHandlerTools().devrigToolSpecs()
        assertTrue(specs.size >= 8, "expected the canonical tool list, got ${specs.map { it.cli.name }}")

        for (spec in specs) {
            val command = ToolCommand(spec)

            for (param in spec.schema.asCliParams().filterNot { it.cliHidden }) {
                val name = if (param.cliPositional) param.name else param.cliFlag
                assertEquals(
                    param.name,
                    command.binding.paramFor(name)?.name,
                    "${spec.cli.name}: '$name' must resolve back to '${param.name}'",
                )
                param.cliFileSource?.let { source ->
                    assertEquals(
                        param.name,
                        command.binding.paramFor(source.flag)?.name,
                        "${spec.cli.name}: '${source.flag}' must resolve back to '${param.name}'",
                    )
                }
            }
        }
    }

    @Test
    fun `an unknown parameter name has no declaring parameter`() {
        val command = ToolCommand(executeCode())

        assertEquals(null, command.binding.paramFor("--json"))
    }
}
