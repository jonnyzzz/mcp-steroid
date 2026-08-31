/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generated usage line brackets a token when the invocation is legal without it and parenthesizes an
 * alternation when one of its two spellings is mandatory. Nothing but a convention makes those two claims
 * true — so these tests check the *rendering* against the *real parser* rather than against a restatement
 * of the rule, for the one shape where the two could plausibly disagree.
 *
 * That shape is a parameter which is schema-`required`, `cliOptional`, and declares a
 * [com.jonnyzzz.mcpSteroid.mcp.CliFileSource] — `execute_code`'s `code`. It is the case where the two
 * branches of the renderer test requiredness differently (`required && !cliOptional` without a file source,
 * plain `required` with one), and reading the renderer alone cannot tell you which is right. The parser
 * settles it: the parse-time check `SchemaCliBinding` registers raises `MissingCliValue` when `required` and
 * neither spelling supplied a non-blank token (#460), because a required
 * parameter offering a file source is `cliOptional` only so Clikt stops demanding the direct flag — not
 * because the value became optional.
 *
 * If the binding ever relaxes that to `cliRequired`, the second test here fails and the help stops
 * promising a mandatory alternation the CLI no longer enforces.
 */
class CliFileSourceUsageTokenTest {

    @TempDir
    lateinit var home: Path

    private fun section(): String = renderMcpToolsCliSection(devrigCliTools())

    private fun toolsWithRequiredFileSource() = devrigCliTools()
        .filterNot { it.cli.hidden }
        .flatMap { tool -> tool.schema.asCliParams().filter { it.cliFileSource != null && it.required }.map { tool to it } }

    @Test
    fun `a required parameter with a file source renders as a mandatory alternation`() {
        val cases = toolsWithRequiredFileSource()
        assertTrue(cases.isNotEmpty(), "no tool declares a required parameter with a file source")

        for ((tool, param) in cases) {
            val expected = "(${param.cliFlag}=<${param.name}> | ${param.cliFileSource?.flag}=<path>)"
            assertTrue(
                expected in section(),
                "${tool.cli.name}.${param.name} must render as the mandatory alternation $expected:\n${section()}",
            )
        }
    }

    @Test
    fun `and the parser really does reject an invocation that supplies neither spelling`() {
        // The claim the parenthesis makes, driven through the real command tree. `execute_code` is invoked
        // with every OTHER required parameter present, so the only thing missing is the alternation itself.
        for ((tool, param) in toolsWithRequiredFileSource()) {
            val others = tool.schema.asCliParams()
                .filterNot { it.cliHidden || it.name == param.name || !it.required || it.cliOptional }
                .map { "${it.cliFlag}=x" }
            val command = parseDevrigCommand((listOf(tool.cli.name) + others).toTypedArray())

            assertEquals(
                "parse-error",
                command.commandPath,
                "${tool.cli.name} must reject an invocation supplying neither ${param.cliFlag} nor " +
                    "${param.cliFileSource?.flag}; the help renders that pair as mandatory",
            )
            val error = requireNotNull(command.informationalText)
            assertTrue(
                param.name in error || param.cliFlag in error,
                "the failure must name '${param.name}'; got:\n$error",
            )
        }
    }

    @Test
    fun `a plain required parameter renders bare, demanded`() {
        // The contrast case, and the one the brackets are easiest to get wrong on. `project_name` is
        // schema-`required` with no file source, so the token is neither parenthesized (there is no
        // alternation) nor bracketed (the invocation is NOT legal without it — the parser demands it).
        //
        // Bare, not parenthesized, is also what keeps the first test here honest: a renderer that simply
        // wrapped everything `required` in parentheses would fail this one.
        assertTrue(
            " --project_name=<project_name> " in section(),
            "a required parameter must render as demanded, bare:\n${section()}",
        )
        assertTrue(
            "[--project_name" !in section(),
            "project_name is required, so no usage line may bracket it:\n${section()}",
        )
        assertTrue(
            "(--project_name" !in section(),
            "project_name declares no file source, so it is not an alternation:\n${section()}",
        )
    }

    @Test
    fun `and the parser really does demand it`() {
        // The claim the un-bracketed token makes, driven through the real command tree: omitting
        // `project_name` fails at PARSE time (Clikt demands the required parameter) — the parse never
        // reaches an inert RunTool. `CliErrorEnvelopeTest` pins the message the runtime path would print.
        val command = parseDevrigCommand(arrayOf("execute_code", "--code=x", "--task_id=t", "--reason=r"))

        assertEquals("parse-error", command.commandPath, "got: $command")
        val error = requireNotNull(command.informationalText)
        assertTrue("project_name" in error, "the failure must name project_name; got:\n$error")
    }
}
