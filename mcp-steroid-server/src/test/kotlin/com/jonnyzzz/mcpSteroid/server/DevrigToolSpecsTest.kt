/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import com.jonnyzzz.mcpSteroid.mcp.McpToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase-A hardening (issue #284): `devrigToolSpecs(...)` is the ONE canonical list of devrig-surface tool
 * specs. These tests pin that it enumerates every devrig tool exactly once and that registering it into an
 * [McpToolRegistry] advertises exactly that same set — the guarantee that stdio + help + CLI can never
 * drift, and a new tool can never be silently dropped.
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
        val names = devrigToolSpecs(NoHandlerTools()).map { it.name }
        assertEquals(expectedDevrigTools, names, "devrigToolSpecs must list every devrig tool exactly once")
        assertEquals(names.toSet().size, names.size, "no tool may appear twice")
    }

    @Test
    fun `devrigToolSpecs is a List of CliToolSpec so the CLI reads cli and schema without a cast`() {
        val specs: List<CliToolSpec> = devrigToolSpecs(NoHandlerTools())
        for (spec in specs) {
            // cli + schema are readable directly off the CliToolSpec — no `as? McpToolBase` cast.
            assertTrue(spec.cli.synopsis.isNotBlank(), "${spec.name}: cli.synopsis must be non-blank")
            assertEquals(spec.inputSchema, spec.schema.asMcpJson(), "${spec.name}: schema must back inputSchema")
        }
    }

    @Test
    fun `registering the factory list advertises exactly the same tools as the MCP registry`() {
        val registry = McpToolRegistry()
        for (spec in devrigToolSpecs(NoHandlerTools())) {
            registry.registerTool(spec)
        }
        assertEquals(expectedDevrigTools, registry.listMcpTools().map { it.name })
        // The set the registry advertises to MCP clients matches the factory list exactly.
        assertEquals(
            devrigToolSpecs(NoHandlerTools()).map { it.name }.toSet(),
            registry.listTools().map { it.name }.toSet(),
        )
    }

    @Test
    fun `devrig open_project advertises the backend_name routing param`() {
        val openProject = devrigToolSpecs(NoHandlerTools()).single { it.name == "steroid_open_project" }
        val params = openProject.schema.asCliParams().map { it.name }
        assertTrue(params.contains("backend_name"), "devrig open_project must expose backend_name: $params")
    }
}
