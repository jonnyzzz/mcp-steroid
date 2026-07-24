/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
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
    fun `every tool has a short non-blank one-line synopsis`() {
        for (tool in allTools) {
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
    fun `fetch_resource exposes the prompt alias and maps uri to the --uri flag`() {
        assertTrue(fetchResource.cli.aliases.contains("prompt"), "fetch_resource should alias 'prompt'")
        val uri = fetchResource.schema.asCliParams().single { it.name == "uri" }
        assertFalse(uri.cliPositional, "fetch_resource uri must map to --uri, not a positional")
        assertEquals("--uri", uri.cliFlag)
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
    fun `project-scoped tools mark project_name CLI-optional but keep it MCP-required`() {
        val projectScoped = listOf(executeCode, executeFeedback, takeScreenshot, input, fetchResource)
        for (tool in projectScoped) {
            val projectName = tool.schema.asCliParams().single { it.name == "project_name" }
            assertTrue(projectName.cliOptional, "${tool.name}: project_name must be cliOptional")
            assertTrue(projectName.required, "${tool.name}: project_name must stay MCP-required")
            assertFalse(projectName.cliSynopsis.isNullOrBlank(), "${tool.name}: project_name needs a curated cliSynopsis")
        }
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
            for (leak in listOf("cliFlag", "cliSynopsis", "cliPositional", "cliHidden", "cliOptional", "enumValues")) {
                assertFalse(rendered.contains(leak), "${tool.name}: MCP schema leaked CLI hint '$leak'")
            }
        }
    }
}
