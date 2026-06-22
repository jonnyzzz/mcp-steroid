/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * R3.5 — mechanical guard that the devrig<->IDE WIRE stays pristine. The MCP/CLI-surface types
 * [BackendInfo] / [ListedProject] (and their `backend_name` / `project_name` keys) must NEVER serialize
 * into a wire-crossing payload. The only wire DTOs are [NpxStreamEnvelope] (`/projects/stream`, carrying
 * the pristine `{name, path}` [ProjectInfo]) and [NpxBridgeWindowsResponse] (`/windows`).
 */
class WirePristinenessTest {
    @Test
    fun `projects-stream envelope serializes only the pristine name+path ProjectInfo`() {
        val envelope = NpxStreamEnvelope(
            type = "snapshot",
            seq = 1,
            sentAt = "2026-06-09T00:00:00Z",
            instanceId = "ide-1",
            pid = 1234,
            projects = listOf(ProjectInfo(name = "x", path = "/p")),
        )
        val json = NpxStreamJson.encodeEnvelope(envelope)

        // The wire ProjectInfo is exactly {name, path}: none of the MCP/CLI backend/project id keys appear.
        assertFalse(json.contains("backend_name"), "wire must not carry backend_name: $json")
        assertFalse(json.contains("project_name"), "wire must not carry project_name: $json")
        assertFalse(json.contains("backendName"), "wire must not carry backendName: $json")
        assertFalse(json.contains("projectName"), "wire must not carry projectName: $json")

        // And it round-trips to the pristine shape.
        val decodedProject = NpxStreamJson.decodeEnvelope(json).projects!!.single()
        assertEquals("x", decodedProject.name)
        assertEquals("/p", decodedProject.path)
    }

    @Test
    fun `windows response never serializes the MCP backend id keys`() {
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
            backgroundTasks = listOf(
                ProgressTaskInfo(
                    title = "Indexing",
                    text = "scanning",
                    text2 = "",
                    fraction = null,
                    isIndeterminate = true,
                    isCancellable = false,
                    projectName = "x",
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

        // The backend-id keys (the R3 additions) must never leak onto the wire.
        assertFalse(json.contains("backend_name"), "wire must not carry backend_name: $json")
        assertFalse(json.contains("backendName"), "wire must not carry backendName: $json")
        // Note: `projectName` IS a legitimate, pre-existing WindowInfo wire field (the IDE's open project
        // name on a window). It is NOT the MCP-only ListedProject.project_name, so it stays on the wire.
    }
}
