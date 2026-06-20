/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the devrig<->IDE WIRE contract after #92. The IDE additively stamps its own `project_name`
 * (its within-IDE-unique key) and `backend_name` (its self id) onto the wire as OPTIONAL fields, for
 * symmetry with the MCP surface — but these are INFORMATIONAL only: devrig recomputes both itself and
 * does not depend on the wire values. "Additive optional" means a new IDE populates them and an older
 * peer omits them, and BOTH decode (forward/back compatible). The unique routing key is NOT placed on the
 * windows/tasks wire (no consumer) — only `ProjectInfo` carries `project_name`.
 */
class WirePristinenessTest {
    @Test
    fun `projects-stream ProjectInfo carries project_name and backend_name additively`() {
        val envelope = NpxStreamEnvelope(
            type = "snapshot",
            seq = 1,
            sentAt = "2026-06-09T00:00:00Z",
            instanceId = "ide-1",
            pid = 1234,
            projects = listOf(ProjectInfo(name = "x", path = "/p", projectName = "x-1a2b3c4d", backendName = "iu-9fk2a0xQ")),
        )
        val json = NpxStreamJson.encodeEnvelope(envelope)

        // The IDE-stamped additive fields appear on the wire (informational; devrig recomputes).
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "wire ProjectInfo should carry the additive project_name: $json")
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "wire ProjectInfo should carry the additive backend_name: $json")

        val decoded = NpxStreamJson.decodeEnvelope(json).projects!!.single()
        assertEquals("x", decoded.name)
        assertEquals("/p", decoded.path)
        assertEquals("x-1a2b3c4d", decoded.projectName)
        assertEquals("iu-9fk2a0xQ", decoded.backendName)
    }

    @Test
    fun `projects-stream stays back-compatible — additive fields are optional and decode as null when unset`() {
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

        // The additive fields are OPTIONAL: a newer peer decoding a payload where they are absent or null
        // sees nulls, and an older peer (ignoreUnknownKeys) ignores them — so old and new peers interoperate.
        // devrig recomputes the real key/backend regardless, so the wire value is never depended upon.
        val decoded = NpxStreamJson.decodeEnvelope(json).projects!!.single()
        assertEquals("x", decoded.name)
        assertEquals("/p", decoded.path)
        assertNull(decoded.projectName)
        assertNull(decoded.backendName)
    }

    @Test
    fun `windows wire carries backend_name additively but never the project_name routing key`() {
        val response = NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "x",
                    projectPath = "/p",
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
                    projectName = "x",
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

        // `backend_name` is an additive informational wire field (#92).
        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "wire window/task should carry the additive backend_name: $json")
        // The within-IDE-unique routing KEY (project_name) is intentionally NOT on the windows/tasks wire —
        // it has no consumer there (devrig recomputes it; the IDE-direct handler derives it from the
        // open-project list).
        assertFalse(json.contains("project_name"), "windows/tasks wire must not carry the project_name key: $json")
        // The raw `projectName` (folder name) IS a legitimate v1 wire field and stays for old-peer compat.
        assertTrue(json.contains("\"projectName\":\"x\""), "wire keeps the v1 raw projectName: $json")
    }

    @Test
    fun `windows wire stays back-compatible — omits backend_name when unset`() {
        val response = NpxBridgeWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "x",
                    projectPath = "/p",
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

        assertFalse(json.contains("backend_name"), "unpopulated additive backend_name must be omitted: $json")
        val decoded = McpJson.decodeFromString(NpxBridgeWindowsResponse.serializer(), json)
        assertNull(decoded.windows.single().backendName)
    }

    @Test
    fun `MCP-only ListedWindow serializes the project_name routing key and backend_name`() {
        val listed = WindowInfo(
            projectName = "x",
            projectPath = "/p",
            title = "x - main",
            isActive = true,
            isVisible = true,
            bounds = WindowBounds(0, 0, 100, 100),
            windowId = "w1",
            modalDialogShowing = true,
            indexingInProgress = false,
            projectInitialized = true,
        ).listed(backendName = "iu-9fk2a0xQ", projectKey = "x-1a2b3c4d")
        val json = McpJson.encodeToString(ListedWindow.serializer(), listed)

        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "MCP ListedWindow must carry backend_name: $json")
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "MCP ListedWindow must carry the routing key project_name: $json")
        val decoded = McpJson.decodeFromString(ListedWindow.serializer(), json)
        assertEquals(listed, decoded)
        // project_name is the resolved routing key; the project's raw name/path are NOT duplicated here
        // (look them up via steroid_list_projects). Other window fields copy verbatim.
        assertEquals("x-1a2b3c4d", decoded.projectName)
        assertEquals("x - main", decoded.title)
        assertEquals(WindowBounds(0, 0, 100, 100), decoded.bounds)
        assertEquals("w1", decoded.windowId)
        assertTrue(decoded.modalDialogShowing)
        assertEquals(false, decoded.indexingInProgress)
        assertEquals(true, decoded.projectInitialized)
    }

    @Test
    fun `MCP-only ListedBackgroundTask serializes the project_name routing key and backend_name`() {
        val listed = ProgressTaskInfo(
            title = "Indexing",
            text = "scanning",
            text2 = "files",
            fraction = 0.5,
            isIndeterminate = false,
            isCancellable = true,
            projectName = "x",
        ).listed(backendName = "iu-9fk2a0xQ", projectKey = "x-1a2b3c4d")
        val json = McpJson.encodeToString(ListedBackgroundTask.serializer(), listed)

        assertTrue(json.contains("\"backend_name\":\"iu-9fk2a0xQ\""), "MCP ListedBackgroundTask must carry backend_name: $json")
        assertTrue(json.contains("\"project_name\":\"x-1a2b3c4d\""), "MCP ListedBackgroundTask must carry the routing key project_name: $json")
        val decoded = McpJson.decodeFromString(ListedBackgroundTask.serializer(), json)
        assertEquals(listed, decoded)
        assertEquals("Indexing", decoded.title)
        assertEquals("scanning", decoded.text)
        assertEquals("files", decoded.text2)
        assertEquals(0.5, decoded.fraction)
        assertEquals(false, decoded.isIndeterminate)
        assertTrue(decoded.isCancellable)
        assertEquals("x-1a2b3c4d", decoded.projectName)
    }
}
