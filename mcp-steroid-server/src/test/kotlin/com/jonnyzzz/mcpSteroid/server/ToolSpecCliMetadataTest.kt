/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliOptionType
import com.jonnyzzz.mcpSteroid.mcp.CliOutputStyle
import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.prompts.Generic
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies CLI metadata on the real tool specs and ensures it stays outside the MCP schema projection.
 */
class ToolSpecCliMetadataTest {

    private val executeCode = ExecuteCodeToolSpec { unreachableHandler() }
    private val executeFeedback = ExecuteFeedbackToolSpec { unreachableHandler() }
    private val listProjects = ListProjectsToolSpec { unreachableHandler() }
    private val listWindows = ListWindowsToolSpec { unreachableHandler() }
    private val openProject = OpenProjectToolSpec(handler = { unreachableHandler() })
    private val takeScreenshot = VisionScreenshotToolSpec { unreachableHandler() }
    private val input = VisionInputToolSpec(handler = { unreachableHandler() })
    private val fetchResource = FetchResourceToolHandler { unreachableHandler() }

    private val allTools: List<CliToolSpec> = listOf(
        executeCode, executeFeedback, listProjects, listWindows,
        openProject, takeScreenshot, input, fetchResource,
    )

    @Test
    fun `cli name strips the steroid prefix for every tool`() {
        assertEquals("execute_code", executeCode.cli.name)
        assertEquals("execute_feedback", executeFeedback.cli.name)
        assertEquals("list_projects", listProjects.cli.name)
        assertEquals("list_windows", listWindows.cli.name)
        assertEquals("open_project", openProject.cli.name)
        assertEquals("take_screenshot", takeScreenshot.cli.name)
        assertEquals("input", input.cli.name)
        assertEquals("fetch_resource", fetchResource.cli.name)
    }

    @Test
    fun `list_projects is canonical with plural and legacy singular aliases`() {
        assertEquals("list_projects", listProjects.cli.name)
        assertEquals(listOf("projects", "project"), listProjects.cli.aliases)
        assertEquals(CliOutputStyle.PROJECTS_TABLE, listProjects.cli.outputStyle)
        assertTrue(allTools.filterNot { it === listProjects }.all { it.cli.outputStyle == CliOutputStyle.CONTENT })
    }

    @Test
    fun `every required CLI parameter explains the value to provide when it is missing`() {
        for (tool in devrigToolSpecsForTest().filterNot { it.cli.hidden }) {
            for (param in tool.schema.asCliParams().filter { it.required && !it.cliHidden }) {
                assertTrue(
                    !param.cliMissingHint.isNullOrBlank(),
                    "${tool.cli.name}.${param.name} is required on the CLI and needs an actionable cliMissingHint",
                )
            }
        }
    }

    @Test
    fun `every tool has a short non-blank one-line synopsis`() {
        // Iterates the canonical devrigToolSpecs() list (not a hand-picked one) so a tool added there in
        // the future is checked automatically instead of silently shipping without a subcommand synopsis.
        for (tool in devrigToolSpecsForTest()) {
            val synopsis = tool.cli.synopsis
            assertFalse(synopsis.isBlank(), "${tool.name}: cli.synopsis must be non-blank")
            assertFalse(synopsis.contains("\n"), "${tool.name}: cli.synopsis must be a single line")
            assertTrue(
                synopsis.length < 80,
                "${tool.name}: cli.synopsis must be short (<80 chars), was ${synopsis.length}: $synopsis",
            )
        }
    }

    @Test
    fun `fetch_resource exposes the prompt alias and maps uri to a bare positional`() {
        // bindPositional (SchemaCliBinding.kt) makes the bare URI canonical and visible in help.
        // The generated command separately retains hidden `--uri` compatibility for existing scripts;
        // this metadata test pins only the advertised positional form.
        assertTrue(fetchResource.cli.aliases.contains("prompt"), "fetch_resource should alias 'prompt'")
        val uri = fetchResource.schema.asCliParams().single { it.name == "uri" }
        assertTrue(uri.cliPositional, "fetch_resource must advertise its uri as a bare positional")
    }

    @Test
    fun `asCliParams returns one spec per registered param for execute_code`() {
        assertEquals(
            listOf("project_name", "code", "task_id", "reason", "timeout", "modal"),
            executeCode.schema.asCliParams().map { it.name },
        )
    }

    @Test
    fun `modal param carries its enum values for CLI help`() {
        val modal = executeCode.schema.asCliParams().single { it.name == "modal" }
        assertEquals(listOf("smart_non_modal", "non_modal", "unleashed"), modal.enumValues)
    }

    @Test
    fun `project-scoped tools demand project_name on the CLI, not just MCP-required`() {
        // No cwd inference runs yet (see TODO), so project_name is a plain mandatory parameter: it is NOT
        // cliOptional, which is what makes the command-line parser demand it rather than defer the check to
        // the tool. When cwd inference lands, `.cliOptional()` returns to CommonToolParams.projectName().
        val projectScoped = listOf(executeCode, executeFeedback, takeScreenshot, input, fetchResource)
        for (tool in projectScoped) {
            val projectName = tool.schema.asCliParams().single { it.name == "project_name" }
            assertFalse(projectName.cliOptional, "${tool.name}: project_name must NOT be cliOptional")
            assertTrue(projectName.required, "${tool.name}: project_name must stay MCP-required")
            assertFalse(projectName.cliSynopsis.isBlank(), "${tool.name}: project_name needs a curated cliSynopsis")
        }
    }

    @Test
    fun `only take_screenshot and execute_code mark their result as image-producing`() {
        // producesImage gates the framework --out flag onto a subcommand. Exactly these two tools can return
        // a ContentItem.Image (take_screenshot always; execute_code via a script's logImage or a
        // dialog-failure screenshot); every other command must leave --out unregistered.
        val imageProducing = devrigToolSpecsForTest().filter { it.cli.producesImage }.map { it.name }.toSet()
        assertEquals(setOf("steroid_take_screenshot", "steroid_execute_code"), imageProducing)
    }

    @Test
    fun `execute_code code is CLI-optional but remains MCP-required`() {
        val code = executeCode.schema.asCliParams().single { it.name == "code" }
        assertTrue(code.cliOptional)
        assertTrue(code.required)
        val required = executeCode.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("code"), "code must stay in the MCP required list: $required")
    }

    @Test
    fun `asMcpJson equals inputSchema and CLI hints never leak into the MCP schema`() {
        for (tool in allTools) {
            assertEquals(tool.inputSchema, tool.schema.asMcpJson(), "${tool.name}: asMcpJson must equal inputSchema")
            val rendered = Json.encodeToString(JsonObject.serializer(), tool.inputSchema)
            val leakStrings = listOf(
                "cliFlag", "cliSynopsis", "cliPositional", "cliHidden", "cliOptional", "enumValues",
                "cliFileSource", "cliMinimum", "cliMaximum", "cliMissingHint", "extraOptions",
                // The declared CLI-only flag names themselves: a file-source flag and a tool-level extra
                // must be as invisible on the wire as the metadata field that declares them.
                "--code-file", "--wait",
            )
            for (leak in leakStrings) {
                assertFalse(rendered.contains(leak), "${tool.name}: MCP schema leaked CLI hint '$leak'")
            }
        }
    }

    @Test
    fun `every CLI-visible flag of every devrig tool has a short one-line synopsis`() {
        // Iterates the canonical devrigToolSpecs() list (not a hand-picked one) so a tool added there
        // in the future is checked automatically instead of silently shipping without help text. Covers
        // all three flag kinds the CLI renders: parameters, their file sources, and tool-level extras.
        for (tool in devrigToolSpecsForTest()) {
            val synopses = buildList {
                for (param in tool.schema.asCliParams().filterNot { it.cliHidden }) {
                    add(param.name to param.cliSynopsis)
                    param.cliFileSource?.let { add("${param.name}${it.flag}" to it.synopsis) }
                }
                for (extra in tool.cli.extraOptions) add(extra.flag to extra.synopsis)
            }
            for ((label, synopsis) in synopses) {
                assertFalse(
                    synopsis.isBlank(),
                    "${tool.name}.$label: synopsis must be non-blank",
                )
                assertFalse(
                    synopsis.contains("\n"),
                    "${tool.name}.$label: synopsis must be a single line",
                )
                assertFalse(
                    synopsis.endsWith("."),
                    "${tool.name}.$label: synopsis must not end with a trailing period: $synopsis",
                )
                assertTrue(
                    synopsis.length <= 72,
                    "${tool.name}.$label: synopsis must be <= 72 chars, was ${synopsis.length}: $synopsis",
                )
            }
        }
    }

    @Test
    fun `each devrig tool declares exactly its expected CLI file sources`() {
        // parameter name -> the flag that reads that parameter's value from a file.
        val expectedFileSourcesByTool = mapOf(
            "steroid_list_projects" to emptyMap(),
            "steroid_list_windows" to emptyMap(),
            "steroid_execute_code" to mapOf("code" to "--code-file"),
            "steroid_execute_feedback" to mapOf("code" to "--code-file"),
            "steroid_take_screenshot" to emptyMap(),
            "steroid_input" to emptyMap(),
            "steroid_fetch_resource" to emptyMap(),
            "steroid_open_project" to emptyMap(),
        )

        // Iterate the canonical devrigToolSpecs() list and assert it names exactly the tools the
        // expected map covers — completeness check first, so a tool added to (or removed from) the
        // canonical list without a matching map entry fails loudly here, not silently.
        val tools = devrigToolSpecsForTest()
        assertEquals(
            expectedFileSourcesByTool.keys,
            tools.map { it.name }.toSet(),
            "expectedFileSourcesByTool must name exactly the tools devrigToolSpecs() returns",
        )
        for (tool in tools) {
            val expected = expectedFileSourcesByTool.getValue(tool.name)
            val actual = tool.schema.asCliParams()
                .mapNotNull { param -> param.cliFileSource?.let { param.name to it.flag } }
                .toMap()
            assertEquals(expected, actual, "${tool.name}: declared CLI file sources")
        }
    }

    @Test
    fun `a parameter with a file source may be omitted on the CLI while staying MCP-required`() {
        // This is what makes --code-file usable on its own, and it is declared, not coded per tool.
        for (tool in listOf(executeCode, executeFeedback)) {
            val code = tool.schema.asCliParams().single { it.name == "code" }
            assertEquals("--code-file", code.cliFileSource?.flag, "${tool.name}: code file-source flag")
            assertFalse(
                code.required && !code.cliOptional,
                "${tool.name}: code declares a file source so the CLI must not demand --code",
            )
        }
        // execute_code's code is MCP-required; execute_feedback's is optional. Either way the CLI can
        // take the file instead — the generator needs no per-tool knowledge of which case it is in.
        val required = executeCode.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("code"), "execute_code: code must stay MCP-required: $required")
    }

    @Test
    fun `each devrig tool declares exactly its expected tool-level extra CLI options`() {
        // Asserts both the option's name (its identity) and its flag (its CLI spelling) so a future
        // flag rename shows up here as a flag-only diff rather than silently passing on the name alone.
        val expectedExtrasByTool = mapOf(
            "steroid_list_projects" to emptyList(),
            "steroid_list_windows" to emptyList(),
            "steroid_execute_code" to emptyList(),
            "steroid_execute_feedback" to emptyList(),
            "steroid_take_screenshot" to emptyList(),
            "steroid_input" to emptyList(),
            "steroid_fetch_resource" to emptyList(),
            "steroid_open_project" to listOf("wait" to "--wait"),
        )

        val tools = devrigToolSpecsForTest()
        assertEquals(
            expectedExtrasByTool.keys,
            tools.map { it.name }.toSet(),
            "expectedExtrasByTool must name exactly the tools devrigToolSpecs() returns",
        )
        for (tool in tools) {
            assertEquals(
                expectedExtrasByTool.getValue(tool.name),
                tool.cli.extraOptions.map { it.name to it.flag },
                "${tool.name}: declared tool-level extra CLI options (name to flag)",
            )
        }

        val wait = tools.single { it.name == "steroid_open_project" }.cli.extraOptions.single()
        assertEquals("wait", wait.name, "the extra option's identity is its name, not its flag")
        assertEquals(CliOptionType.BOOLEAN, wait.type, "--wait is a boolean switch")
        assertFalse(wait.synopsis.isBlank(), "--wait needs help text")
        assertTrue("300s" in wait.synopsis, "--wait help must state its bounded timeout")
        assertTrue("project route" in wait.synopsis, "--wait help must explain the successful outcome")
        assertFalse("reserved" in wait.synopsis.lowercase(), "implemented --wait must not be described as reserved")
        // An extra option is not a tool input: it must not appear among the parameters.
        val params = tools.single { it.name == "steroid_open_project" }.schema.asCliParams().map { it.name }
        assertFalse(params.contains("wait"), "--wait must not be a schema parameter: $params")
    }

    @Test
    fun `CLI-only declarations are identical on the in-IDE and devrig surfaces`() {
        // The surface rule: a declaration is gated per surface only when it changes the MCP wire (that is
        // what includeBackendName does for backend_name). CLI metadata never reaches the wire, so it is
        // declared unconditionally — one rule for file sources and extra options alike.
        val devrigOpenProject = devrigToolSpecsForTest().single { it.name == "steroid_open_project" }
        assertEquals(
            devrigOpenProject.cli.extraOptions,
            openProject.cli.extraOptions,
            "open_project: extra CLI options must not depend on the surface",
        )
        assertEquals(
            devrigOpenProject.schema.asCliParams().mapNotNull { it.cliFileSource },
            openProject.schema.asCliParams().mapNotNull { it.cliFileSource },
            "open_project: CLI file sources must not depend on the surface",
        )
        // The wire-affecting parameter still is gated, which is the distinction the rule draws.
        assertFalse(
            openProject.schema.asCliParams().any { it.name == "backend_name" },
            "the in-IDE open_project must still advertise no backend_name",
        )
    }

    @Test
    fun `execute_code declares resolvable guide URIs and other tools default to none`() {
        val specs = devrigToolSpecsForTest()
        val executeCode = specs.single { it.name == "steroid_execute_code" }
        assertTrue(executeCode.cli.guideUris.isNotEmpty(), "execute_code must seed guide URIs for `devrig help execute_code`")
        for (uri in executeCode.cli.guideUris) {
            assertEquals(uri, resolveResourceArticle(uri, PromptsContext.Generic)?.uri, "guide uri $uri must resolve")
        }
        assertTrue(specs.single { it.name == "steroid_list_windows" }.cli.guideUris.isEmpty(), "a tool that declares none has none")
    }
}
