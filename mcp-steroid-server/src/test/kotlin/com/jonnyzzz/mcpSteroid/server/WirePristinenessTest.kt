/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the devrig<->IDE wire DTO shapes after #92. `project_name` is the single project identifier
 * everywhere: [WindowInfo]/[ProgressTaskInfo] carry it directly (the IDE-stamped within-IDE-unique key),
 * and [ProjectInfo] additively carries it alongside the v1 `{name, path}` (plus `backend_name`). The MCP
 * `.listed()` mapping copies the wire entry verbatim into the MCP-only [ListedWindow]/[ListedBackgroundTask].
 */
class WirePristinenessTest {
    @Test
    fun `projects-stream ProjectInfo additively carries project_name and backend_name`() {
        val envelope = NpxStreamEnvelope(
            type = "snapshot",
            seq = 1,
            sentAt = "2026-06-09T00:00:00Z",
            instanceId = "ide-1",
            pid = 1234,
            projects = listOf(ProjectInfo(name = "x", path = "/p", projectName = "x-1a2b3c4d", backendName = "iu-9fk2a0xQ")),
        )
        val json = NpxStreamJson.encodeEnvelope(envelope)

        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "wire ProjectInfo should carry the additive project_name: $json")
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "wire ProjectInfo should carry the additive backend_name: $json")

        val decoded = NpxStreamJson.decodeEnvelope(json).projects!!.single()
        assertEquals("x", decoded.name)
        assertEquals("/p", decoded.path)
        assertEquals("x-1a2b3c4d", decoded.projectName)
        assertEquals("iu-9fk2a0xQ", decoded.backendName)
    }

    @Test
    fun `projects-stream stays back-compatible — additive ProjectInfo fields are optional`() {
        val envelope = NpxStreamEnvelope(
            type = "snapshot",
            seq = 1,
            sentAt = "2026-06-09T00:00:00Z",
            instanceId = "ide-1",
            pid = 1234,
            // v1 shape: only {name, path}. The additive fields are optional (default null).
            projects = listOf(ProjectInfo(name = "x", path = "/p")),
        )
        val json = NpxStreamJson.encodeEnvelope(envelope)
        val decoded = NpxStreamJson.decodeEnvelope(json).projects!!.single()
        assertEquals("x", decoded.name)
        assertEquals("/p", decoded.path)
        assertNull(decoded.projectName)
        assertNull(decoded.backendName)
    }

    @Test
    fun `windows wire carries project_name as the key and backend_name`() {
        val response = NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "x-1a2b3c4d",
                    title = "x - main",
                    isActive = true,
                    isVisible = true,
                    bounds = WindowBounds(0, 0, 100, 100),
                    windowId = "w1",
                    backendName = "iu-9fk2a0xQ",
                ),
            ),
            backgroundTasks = listOf(
                ProgressTaskInfo(
                    title = "Indexing",
                    text = "scanning",
                    text2 = "",
                    fraction = null,
                    isIndeterminate = true,
                    isCancellable = false,
                    projectName = "x-1a2b3c4d",
                    backendName = "iu-9fk2a0xQ",
                ),
            ),
            pid = 1234,
            mcpUrl = "http://127.0.0.1:0/mcp",
            instanceId = "npx-1",
            seq = 1,
            schemaVersion = "1",
            updatedAt = "2026-06-09T00:00:00Z",
        )
        val json = McpJson.encodeToString(NpxBridgeWindowsResponse.serializer(), response)

        // project_name is the single project identifier on the windows/tasks wire (#92).
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "windows/tasks wire should carry project_name: $json")
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "windows/tasks wire should carry backend_name: $json")
        // The legacy camelCase raw-name field is gone — project_name is the only project field now.
        assertFalse(json.contains("\"projectName\""), "the legacy camelCase projectName must not appear: $json")

        val decoded = McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), json)
        assertEquals("x-1a2b3c4d", decoded.windows.single().projectName)
        assertEquals("x-1a2b3c4d", decoded.backgroundTasks.single().projectName)
    }

    @Test
    fun `windows wire backend_name is optional`() {
        val response = NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "x-1a2b3c4d",
                    title = "x - main",
                    isActive = true,
                    isVisible = true,
                    bounds = WindowBounds(0, 0, 100, 100),
                    windowId = "w1",
                ),
            ),
            backgroundTasks = emptyList(),
            pid = 1234,
            mcpUrl = "http://127.0.0.1:0/mcp",
            instanceId = "npx-1",
            seq = 1,
            schemaVersion = "1",
            updatedAt = "2026-06-09T00:00:00Z",
        )
        val json = McpJson.encodeToString(NpxBridgeWindowsResponse.serializer(), response)

        assertFalse(json.contains("backend_name"), "unset backend_name must be omitted: $json")
        val decoded = McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), json)
        assertNull(decoded.windows.single().backendName)
    }

    @Test
    fun `windows wire decodes a project-less window or task (null project_name) without error`() {
        // A non-project window (welcome screen, etc.) has project_name = null → omitted by explicitNulls=false.
        // The optional `= null` field must decode it back to null, never throw MissingFieldException.
        val response = NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = null,
                    title = "Welcome",
                    isActive = true,
                    isVisible = true,
                    bounds = null,
                    windowId = "welcome-1",
                ),
            ),
            backgroundTasks = listOf(
                ProgressTaskInfo(
                    title = "Indexing",
                    text = "",
                    text2 = "",
                    fraction = null,
                    isIndeterminate = true,
                    isCancellable = false,
                    projectName = null,
                ),
            ),
            pid = 1234,
            mcpUrl = "http://127.0.0.1:0/mcp",
            instanceId = "npx-1",
            seq = 1,
            schemaVersion = "1",
            updatedAt = "2026-06-09T00:00:00Z",
        )
        val json = McpJson.encodeToString(NpxBridgeWindowsResponse.serializer(), response)
        assertFalse(json.contains("project_name"), "null project_name must be omitted: $json")
        val decoded = McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), json)
        assertNull(decoded.windows.single().projectName)
        assertNull(decoded.backgroundTasks.single().projectName)
    }

    @Test
    fun `MCP-only ListedWindow carries project_name and backend_name`() {
        val listed = WindowInfo(
            projectName = "x-1a2b3c4d",
            title = "x - main",
            isActive = true,
            isVisible = true,
            bounds = WindowBounds(0, 0, 100, 100),
            windowId = "w1",
            modalDialogShowing = true,
            indexingInProgress = false,
            projectInitialized = true,
            backendName = "iu-9fk2a0xQ",
        ).listed(backendName = "iu-9fk2a0xQ")
        val json = McpJson.encodeToString(ListedWindow.serializer(), listed)

        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "MCP ListedWindow must carry backend_name: $json")
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "MCP ListedWindow must carry the routing key project_name: $json")
        val decoded = McpJson.decodeFromString(ListedWindow.serializer(), json)
        assertEquals(listed, decoded)
        assertEquals("x-1a2b3c4d", decoded.projectName)
        assertEquals("x - main", decoded.title)
        assertEquals(WindowBounds(0, 0, 100, 100), decoded.bounds)
        assertEquals("w1", decoded.windowId)
        assertTrue(decoded.modalDialogShowing)
        assertEquals(false, decoded.indexingInProgress)
        assertEquals(true, decoded.projectInitialized)
    }

    @Test
    fun `MCP-only ListedBackgroundTask carries project_name and backend_name`() {
        val listed = ProgressTaskInfo(
            title = "Indexing",
            text = "scanning",
            text2 = "files",
            fraction = 0.5,
            isIndeterminate = false,
            isCancellable = true,
            projectName = "x-1a2b3c4d",
        ).listed(backendName = "iu-9fk2a0xQ")
        val json = McpJson.encodeToString(ListedBackgroundTask.serializer(), listed)

        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "MCP ListedBackgroundTask must carry backend_name: $json")
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "MCP ListedBackgroundTask must carry the routing key project_name: $json")
        val decoded = McpJson.decodeFromString(ListedBackgroundTask.serializer(), json)
        assertEquals(listed, decoded)
        assertEquals("x-1a2b3c4d", decoded.projectName)
        assertEquals("Indexing", decoded.title)
        assertEquals(0.5, decoded.fraction)
        assertTrue(decoded.isCancellable)
    }
}
