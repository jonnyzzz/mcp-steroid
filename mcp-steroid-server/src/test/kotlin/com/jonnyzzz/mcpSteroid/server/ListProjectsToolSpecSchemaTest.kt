/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListProjectsToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ListProjectsToolSpec { unreachableHandler() }
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_list_projects")
        assertRequiredExactly(spec.inputSchema)
    }

    @Test
    fun `response decodes ListedProject and defaults backends to empty`() {
        // No top-level ide/plugin/pid header (#89) — projects[] + backends[] only.
        // ListedProject uses snake_case `project_name`/`backend_name` (@SerialName); `backends` defaults empty.
        val json = """{"projects":[{"project_name":"n","name":"n","path":"/p"}]}"""
        val decoded = McpJson.decodeFromString(ListProjectsResponse.serializer(), json)
        val project = decoded.projects.single()
        assertEquals("n", project.projectName)
        assertEquals("n", project.name)
        assertEquals("/p", project.path)
        assertNull(project.backendName)
        assertTrue(decoded.backends.isEmpty())
    }

    @Test
    fun `response serializes no top-level ide-plugin-pid header`() {
        // #89: devrig's own identity lives in the MCP server info; attribution is per-entry via backend_name.
        val response = ListProjectsResponse(
            projects = listOf(ListedProject(projectName = "n", name = "n", path = "/p", backendName = "iu-1")),
        )
        val json = McpJson.encodeToString(ListProjectsResponse.serializer(), response)
        assertTrue(!json.contains("\"ide\""), json)
        assertTrue(!json.contains("\"plugin\""), json)
        assertTrue(!json.contains("\"pid\""), json)
    }

    @Test
    fun `ListedProject round-trips snake_case keys`() {
        val listed = ListedProject(projectName = "proj-9fk2a0xQ", name = "proj", path = "/p", backendName = "iu-9fk2a0xQ")
        val json = McpJson.encodeToString(ListedProject.serializer(), listed)
        assertTrue(json.contains("\"project_name\":\"proj-9fk2a0xQ\""), json)
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), json)
        val decoded = McpJson.decodeFromString(ListedProject.serializer(), json)
        assertEquals(listed, decoded)
    }

    @Test
    fun `BackendInfo round-trips with renamed fields`() {
        val backend = BackendInfo(
            backendName = "iu-9fk2a0xQ",
            source = "marker",
            displayName = "IntelliJ IDEA 2026.1",
            locator = "build IU-261.x, pid 1234",
            routable = true,
            reachable = true,
            plugins = listOf(
                BackendPlugin(MCP_STEROID_PLUGIN_ID, "MCP Steroid", "0.0.0-test", kind = MCP_STEROID_PLUGIN_KIND),
            ),
            pid = 1234,
            ideProductCode = "IU",
            portDetail = PortBackendDetail(baseUrl = "http://localhost:63342"),
        )
        val json = McpJson.encodeToString(BackendInfo.serializer(), backend)
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), json)
        assertTrue(json.contains("\"kind\":\"mcp-steroid\""), json)
        assertTrue(json.contains("\"portDetail\""), json)
        val decoded = McpJson.decodeFromString(BackendInfo.serializer(), json)
        assertEquals(backend, decoded)
        assertTrue(decoded.hasMcpSteroid(), "decoded backend reports MCP Steroid via plugins[]: $decoded")
        assertEquals("0.0.0-test", decoded.mcpSteroidPlugin()?.version)
    }

    @Test
    fun `BackendInfo without an mcp-steroid plugin reports hasMcpSteroid false`() {
        val backend = BackendInfo(
            backendName = "iu-port",
            source = "port",
            displayName = "IntelliJ IDEA",
            locator = "port 63342",
            routable = false,
            reachable = true,
        )
        assertTrue(backend.plugins.isEmpty())
        assertNull(backend.mcpSteroidPlugin())
        assertTrue(!backend.hasMcpSteroid())
    }

    @Test
    fun `mcpSteroidPlugins tags our plugin id as mcp-steroid kind and others as other`() {
        val ours = mcpSteroidPlugins(
            com.jonnyzzz.mcpSteroid.PluginInfo(MCP_STEROID_PLUGIN_ID, "MCP Steroid", "1.2.3"),
        ).single()
        assertEquals(MCP_STEROID_PLUGIN_KIND, ours.kind)
        assertEquals("1.2.3", ours.version)

        val other = mcpSteroidPlugins(
            com.jonnyzzz.mcpSteroid.PluginInfo("com.example.other", "Other", "9"),
        ).single()
        assertEquals("other", other.kind)
    }
}
