/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The global `devrig --help` "MCP tools as CLI" section is GENERATED from each tool's [CliCommandSpec]
 * (`cli`) and its schema (`asCliParams()`) — never hand-written (PR #272 review r3579479002). These tests
 * pin that generation: adding a tool or a parameter must automatically surface in the help, so the two can
 * never drift. Assertions are metadata-derived (they read the same specs the generator reads), not frozen
 * against a specific wording.
 */
class McpToolsCliHelpTest {

    private fun globalHelp(): String {
        val buf = ByteArrayOutputStream()
        printHelp(PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun nonHiddenTools() = devrigCliTools().filterNot { it.cli.hidden }

    @Test
    fun `every non-hidden tool surfaces its cli name and synopsis in the global help`() {
        val help = globalHelp()
        for (tool in nonHiddenTools()) {
            assertTrue(help.contains("devrig ${tool.cli.name}"), "missing command line for ${tool.cli.name}:\n$help")
            assertTrue(help.contains(tool.cli.synopsis), "missing synopsis for ${tool.cli.name}: '${tool.cli.synopsis}'\n$help")
        }
    }

    @Test
    fun `every non-hidden parameter flag or positional appears in the help - drift guard`() {
        val help = globalHelp()
        for (tool in nonHiddenTools()) {
            val params = tool.schema.asCliParams().filterNot { it.cliHidden }
            for (param in params) {
                val token = if (param.cliPositional) "<${param.name}>" else param.cliFlag
                assertTrue(help.contains(token), "help missing '$token' of tool ${tool.cli.name}:\n$help")
                param.enumValues?.forEach { value ->
                    assertTrue(help.contains(value), "help missing enum value '$value' of ${tool.cli.name}.${param.name}:\n$help")
                }
            }
        }
    }

    @Test
    fun `execute_code enum modal values are rendered`() {
        val help = globalHelp()
        for (v in listOf("smart_non_modal", "non_modal", "unleashed")) {
            assertTrue(help.contains(v), "missing modal enum value '$v':\n$help")
        }
    }

    @Test
    fun `fetch_resource shows the prompt alias and the --uri flag matching the real parser`() {
        val help = globalHelp()
        assertTrue(help.contains("devrig fetch_resource"), help)
        // Must match the canonical parser grammar (`fetch_resource --uri=…`, McpAsCliParseTest), NOT a bare
        // positional — the generated-help-vs-parser divergence caught in review r3579479002.
        assertTrue(help.contains("--uri"), "fetch_resource must render the --uri flag:\n$help")
        assertFalse(help.contains("fetch_resource <uri>"), "fetch_resource must not render a bare positional <uri>:\n$help")
        assertTrue(help.contains("prompt"), "fetch_resource must advertise its `prompt` alias:\n$help")
    }

    @Test
    fun `open_project advertises the devrig-only backend_name flag`() {
        // The generated surface is the devrig one (includeBackendName = true), matching runStubStdioMcpServer.
        val help = globalHelp()
        assertTrue(help.contains("--backend_name"), "devrig open_project must expose --backend_name:\n$help")
    }

    @Test
    fun `non-tool sections are preserved verbatim`() {
        val help = globalHelp()
        for (marker in listOf(
            "devrig mcp",
            "devrig backend provision",
            "devrig install claude|codex|gemini",
            "devrig --version | -v",
            "Options applicable to every mode:",
            "--debug",
            "Environment variables:",
            "DEVRIG_JVM_OPTS",
        )) {
            assertTrue(help.contains(marker), "non-tool help section lost: '$marker'\n$help")
        }
    }

    @Test
    fun `project_name renders CLI-optional (bracketed) despite being MCP-required`() {
        // project_name is MCP-required but cliOptional (cwd-inference, issue #266): its usage token must be
        // bracketed, and the required (unbracketed) form must NOT appear anywhere in the help.
        val help = globalHelp()
        assertTrue(help.contains("[--project_name=<project_name>]"), "project_name must render bracketed/optional:\n$help")
        assertFalse(help.contains(" --project_name=<project_name>"), "project_name must not render as required:\n$help")
    }

    @Test
    fun `the Common CLI flags footer documents the framework and hook flags`() {
        val help = globalHelp()
        for (marker in listOf(
            "Common CLI flags",
            "--json",
            "--code-file",
            "--out",
            "--wait",
            "inferred from the current directory",
        )) {
            assertTrue(help.contains(marker), "Common CLI flags footer missing '$marker':\n$help")
        }
    }

    @Test
    fun `the generated section is wired into the global help`() {
        val section = renderMcpToolsCliSection(devrigCliTools())
        assertTrue(globalHelp().contains(section.trim()), "printHelp must embed the generated section")
    }
}
