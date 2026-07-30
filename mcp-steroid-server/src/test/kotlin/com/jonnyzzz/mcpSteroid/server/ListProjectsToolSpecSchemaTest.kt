/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
    fun `response decodes ListedProject with snake_case keys`() {
        // No top-level ide/plugin/pid header (#89) — projects[] only.
        // ListedProject uses snake_case `project_name`/`backend_name` (@SerialName).
        // A pre-#155 payload without `backends` still decodes (tolerant default).
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
        // #89: devrig's own identity lives in the MCP server info; attribution is per-entry via
        // backend_name. #155 re-added a populated backends[] lookup — the fixture carries a realistic
        // element to prove the guard holds against the richer JSON: the nested key is `intellij`
        // (no quoted "ide" substring), never a resurrected top-level singular header.
        val response = ListProjectsResponse(
            projects = listOf(ListedProject(projectName = "n", name = "n", path = "/p", backendName = "iu-1")),
            backends = listOf(
                BackendRef(
                    backendName = "iu-1",
                    intellij = IntelliJInfo(name = "IntelliJ IDEA 2026.1.3", version = "2026.1.3", build = "IU-261.25134.95"),
                ),
            ),
        )
        val json = McpJson.encodeToString(ListProjectsResponse.serializer(), response)
        assertTrue(!json.contains("\"ide\""), json)
        assertTrue(!json.contains("\"plugin\""), json)
        assertTrue(!json.contains("\"pid\""), json)

        // Semantic pin (not just lexical): exactly {projects, backends} at the top level,
        // and the identity nests under exactly `intellij` inside each backends[] element.
        val root = McpJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("projects", "backends"), root.keys, json)
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("backend_name", "intellij"), backend.keys, json)
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
}
