/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that the canonical devrig tool list and its MCP registry projection stay in sync.
 */
class DevrigToolSpecsTest {

    /** Enumerating metadata never resolves a handler; resolving one is a bug. */
    private class NoHandlerTools : McpSteroidTools() {
        override fun <T> handler(type: Class<T>): T =
            error("handler ${type.name} must not be resolved while enumerating tool specs")
    }

    private val expectedDevrigTools = listOf(
        "steroid_list_projects",
        "steroid_list_windows",
        "steroid_execute_code",
        "steroid_execute_feedback",
        "steroid_take_screenshot",
        "steroid_input",
        "steroid_fetch_resource",
        "steroid_open_project",
    )

    @Test
    fun `devrigToolSpecs returns every devrig tool exactly once in registration order`() {
        val names = NoHandlerTools().devrigToolSpecs().map { it.name }
        assertEquals(expectedDevrigTools, names, "devrigToolSpecs must list every devrig tool exactly once")
        assertEquals(names.toSet().size, names.size, "no tool may appear twice")
    }

    @Test
    fun `devrigToolSpecs is a List of CliToolSpec so the CLI reads cli and schema without a cast`() {
        val specs: List<CliToolSpec> = NoHandlerTools().devrigToolSpecs()
        for (spec in specs) {
            assertTrue(spec.cli.synopsis.isNotBlank(), "${spec.name}: cli.synopsis must be non-blank")
            assertEquals(spec.inputSchema, spec.schema.asMcpJson(), "${spec.name}: schema must back inputSchema")
        }
    }

    @Test
    fun `registering the factory list advertises exactly the same MCP tools`() {
        val registry = McpToolRegistry()
        for (spec in NoHandlerTools().devrigToolSpecs()) {
            registry.registerTool(spec)
        }
        assertEquals(
            expectedDevrigTools,
            registry.listTools().map { it.name },
        )
    }

    @Test
    fun `devrig open_project advertises the backend_name routing param`() {
        val openProject = NoHandlerTools().devrigToolSpecs().single { it.name == "steroid_open_project" }
        val params = openProject.schema.asCliParams().map { it.name }
        assertTrue(params.contains("backend_name"), "devrig open_project must expose backend_name: $params")
    }
}
