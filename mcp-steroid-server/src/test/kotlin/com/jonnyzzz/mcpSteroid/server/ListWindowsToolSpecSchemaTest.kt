/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ListWindowsToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ListWindowsToolSpec { unreachableHandler() }
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_list_windows")
        assertRequiredExactly(spec.inputSchema)
    }

    @Test
    fun `ListedWindow round-trips snake_case project keys`() {
        // #381: the MCP surface uses snake_case `project_name`/`project_path` — the same routing key
        // spelling as ListedProject and the sibling `backend_name` on this very entry.
        val listed = ListedWindow(
            projectName = "proj-9fk2a0xQ",
            projectPath = "/p",
            title = "proj – main",
            isActive = true,
            isVisible = true,
            bounds = WindowBounds(0, 0, 100, 100),
            windowId = "w1",
            backendName = "iu-9fk2a0xQ",
        )
        val json = McpJson.encodeToString(ListedWindow.serializer(), listed)
        assertTrue(json.contains("\"project_name\":\"proj-9fk2a0xQ\""), json)
        assertTrue(json.contains("\"project_path\":\"/p\""), json)
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), json)
        assertFalse(json.contains("projectName"), "MCP surface must not emit camelCase projectName: $json")
        assertFalse(json.contains("projectPath"), "MCP surface must not emit camelCase projectPath: $json")
        val decoded = McpJson.decodeFromString(ListedWindow.serializer(), json)
        assertEquals(listed, decoded)
    }

    @Test
    fun `ListedBackgroundTask round-trips snake_case project key`() {
        // #381: background-task entries reference their project by `project_name`, like windows do.
        val listed = ListedBackgroundTask(
            title = "Indexing",
            text = "scanning",
            text2 = "",
            fraction = null,
            isIndeterminate = true,
            isCancellable = false,
            projectName = "proj-9fk2a0xQ",
            backendName = "iu-9fk2a0xQ",
        )
        val json = McpJson.encodeToString(ListedBackgroundTask.serializer(), listed)
        assertTrue(json.contains("\"project_name\":\"proj-9fk2a0xQ\""), json)
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), json)
        assertFalse(json.contains("projectName"), "MCP surface must not emit camelCase projectName: $json")
        val decoded = McpJson.decodeFromString(ListedBackgroundTask.serializer(), json)
        assertEquals(listed, decoded)
    }
}
