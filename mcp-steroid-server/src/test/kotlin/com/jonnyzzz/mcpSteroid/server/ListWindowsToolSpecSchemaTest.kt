/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
        // #456: windowId stays camelCase — snake_case is the #381 convention for routing keys only,
        // and renaming would break shipped consumers of the output.
        assertTrue(json.contains("\"windowId\":\"w1\""), json)
        assertFalse(json.contains("\"window_id\""), "output key is camelCase windowId, not window_id: $json")
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

    @Test
    fun `response serializes no top-level ide-plugin-pid header`() {
        // #89: no top-level singular identity header; #155 re-added the backends[] lookup — the
        // fixture carries a realistic element to prove the guard holds against the richer JSON
        // (the nested key is `intellij`; no quoted "ide"/"plugin"/"pid" substring appears).
        val response = ListWindowsResponse(
            windows = listOf(
                WindowInfo(
                    projectName = "n-9fk2a0xq",
                    projectPath = "/p",
                    title = "n – readme.md",
                    isActive = true,
                    isVisible = true,
                    bounds = WindowBounds(0, 0, 100, 100),
                    windowId = "w-1",
                ).listed(projectName = "n-9fk2a0xq", backendName = "iu-1"),
            ),
            backgroundTasks = emptyList(),
            backends = listOf(
                BackendRef(
                    backendName = "iu-1",
                    intellij = IntelliJInfo(name = "IntelliJ IDEA 2026.1.3", version = "2026.1.3", build = "IU-261.25134.95"),
                ),
            ),
        )
        val json = McpJson.encodeToString(ListWindowsResponse.serializer(), response)
        assertTrue(!json.contains("\"ide\""), json)
        assertTrue(!json.contains("\"plugin\""), json)
        assertTrue(!json.contains("\"pid\""), json)

        // Semantic pin: exactly {windows, backgroundTasks, backends} at the top level, and the
        // identity nests under exactly `intellij` inside each backends[] element.
        val root = McpJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("windows", "backgroundTasks", "backends"), root.keys, json)
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("backend_name", "intellij"), backend.keys, json)
    }

    @Test
    fun `description names the live keys and never re-conflates the spellings`() {
        // #456: the description used to promise a `window_id` field and deny `project_path` while the
        // JSON carried camelCase `windowId` and included `project_path`. Both directions are pinned:
        // the live keys must be named, and the two original false phrasings must never come back.
        val description = ListWindowsToolSpec { unreachableHandler() }.description
        assertTrue(description.contains("`windowId`"), "description must name the live output key: $description")
        assertTrue(description.contains("`project_path`"), "description must acknowledge project_path in the output: $description")
        assertFalse(
            description.contains("a `window_id` for"),
            "the output key is windowId — the old claim of a window_id output field must not come back: $description",
        )
        assertFalse(
            description.contains("is null for windows"),
            "null routing fields are omitted, never explicit — the old null claim must not come back: $description",
        )
    }

    @Test
    fun `a window not tied to a project omits the project keys instead of null`() {
        // #456 follow-up comment: a standalone dialog window carries no project binding. McpJson uses
        // explicitNulls=false, so the keys are ABSENT, not explicit nulls — asserted structurally
        // (parsed key set + no JsonNull values), not via wire-text substrings, so a prettyPrint flip
        // cannot make this pass vacuously.
        val unbound = ListedWindow(
            projectName = null,
            projectPath = null,
            title = "MCP Battle Test",
            isActive = false,
            isVisible = true,
            bounds = WindowBounds(679, 304, 370, 150),
            windowId = "w-38057d0c",
            modalDialogShowing = true,
            backendName = "go-4778z40r",
        )
        val json = McpJson.encodeToString(ListedWindow.serializer(), unbound)
        val entry = McpJson.parseToJsonElement(json).jsonObject
        assertFalse(entry.containsKey("project_name"), "unbound window omits project_name entirely: $json")
        assertFalse(entry.containsKey("project_path"), "unbound window omits project_path entirely: $json")
        assertTrue(entry.values.none { it is JsonNull }, "no explicit null values in the entry: $json")
        val decoded = McpJson.decodeFromString(ListedWindow.serializer(), json)
        assertEquals(unbound, decoded)
    }

    @Test
    fun `the whole response never serializes an explicit null anywhere`() {
        // The tool description promises: "Any field whose value is unknown is omitted from the JSON
        // entirely, never serialized as null" — for window AND background-task entries. Assert it over
        // a full response whose every nullable field is null, walking the entire tree.
        val response = ListWindowsResponse(
            windows = listOf(
                ListedWindow(
                    projectName = null,
                    projectPath = null,
                    title = null,
                    isActive = false,
                    isVisible = true,
                    bounds = null,
                    windowId = "w-1",
                    indexingInProgress = null,
                    projectInitialized = null,
                    backendName = null,
                ),
            ),
            backgroundTasks = listOf(
                ListedBackgroundTask(
                    title = "Indexing",
                    text = "scanning",
                    text2 = "",
                    fraction = null,
                    isIndeterminate = true,
                    isCancellable = false,
                    projectName = null,
                    backendName = null,
                ),
            ),
        )
        val root = McpJson.parseToJsonElement(McpJson.encodeToString(ListWindowsResponse.serializer(), response))

        fun assertNoNulls(element: JsonElement, path: String) {
            when (element) {
                is JsonNull -> throw AssertionError("explicit null serialized at $path in: $root")
                is JsonObject -> element.forEach { (k, v) -> assertNoNulls(v, "$path.$k") }
                is JsonArray -> element.forEachIndexed { i, v -> assertNoNulls(v, "$path[$i]") }
                else -> Unit
            }
        }
        assertNoNulls(root, "$")
    }

    @Test
    fun `response without backends still decodes`() {
        // Pre-#155 payload shape (tolerant default) — protects test fixtures and round-tripping consumers.
        val decoded = McpJson.decodeFromString(
            ListWindowsResponse.serializer(),
            """{"windows":[],"backgroundTasks":[]}""",
        )
        assertTrue(decoded.backends.isEmpty())
    }
}
