/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        // Structural, not wire-text: a `prettyPrint` flip inserts a space after every colon, which
        // would break the positive substring checks and silently defeat the negative ones.
        val entry = McpJson.parseToJsonElement(json).jsonObject
        assertEquals("proj-9fk2a0xQ", entry["project_name"]?.jsonPrimitive?.content, json)
        assertEquals("/p", entry["project_path"]?.jsonPrimitive?.content, json)
        assertEquals("iu-9fk2a0xQ", entry["backend_name"]?.jsonPrimitive?.content, json)
        assertFalse(entry.containsKey("projectName"), "MCP surface must not emit camelCase projectName: $json")
        assertFalse(entry.containsKey("projectPath"), "MCP surface must not emit camelCase projectPath: $json")
        // #456: windowId stays camelCase — snake_case is the #381 convention for routing keys only,
        // and renaming would break shipped consumers of the output.
        assertEquals("w1", entry["windowId"]?.jsonPrimitive?.content, json)
        assertFalse(entry.containsKey("window_id"), "output key is camelCase windowId, not window_id: $json")
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
    fun `every key the description names in backticks is a key the serializer really emits`() {
        // #456's root cause: the prose named a key the serializer never emits (`window_id`) and denied
        // one it does (`project_path`). Blacklisting past phrasings only catches phrasings already
        // seen, so derive the guard from the serializer — every identifier the description backticks
        // must be a real key of a fully-populated response, or an explicitly allowed cross-reference.
        val description = ListWindowsToolSpec { unreachableHandler() }.description
        val liveKeys = liveResponseKeys()

        // `window_id` is the INPUT parameter of the screenshot/input tools; `name`/`path` belong to
        // steroid_list_projects; `intellij` is asserted separately by BackendRefSerializationTest.
        val crossReferenced = setOf("window_id", "name", "path", "intellij")
        val named = Regex("`([A-Za-z_][A-Za-z0-9_]*)`").findAll(description).map { it.groupValues[1] }.toSet()
        assertEquals(
            emptySet<String>(),
            named - liveKeys - crossReferenced,
            "the description names keys no ListWindowsResponse entry emits (live keys: $liveKeys): $description",
        )
        assertTrue(description.contains("`windowId`"), "description must name the live output key: $description")
        assertTrue(description.contains("`project_path`"), "description must acknowledge project_path in the output: $description")

        // `window_id` may be mentioned ONLY as the input translation, never as an output field.
        assertEquals(
            0,
            Regex("`window_id`(?! input)").findAll(description).count(),
            "`window_id` is not an output key — name it only as the `window_id` input: $description",
        )
        // Any phrasing that calls an absent routing field "null" is the #456 regression.
        assertFalse(
            Regex("`project_(name|path)` is null|is null for windows|null if not").containsMatchIn(description),
            "absent routing fields are omitted, never explicit null — that claim must not come back: $description",
        )
    }

    /** Every JSON key a fully-populated [ListWindowsResponse] emits, at any depth. */
    private fun liveResponseKeys(): Set<String> {
        val response = ListWindowsResponse(
            windows = listOf(
                ListedWindow(
                    projectName = "p",
                    projectPath = "/p",
                    title = "t",
                    isActive = true,
                    isVisible = true,
                    bounds = WindowBounds(0, 0, 1, 1),
                    windowId = "w1",
                    modalDialogShowing = true,
                    indexingInProgress = false,
                    projectInitialized = true,
                    backendName = "b1",
                ),
            ),
            backgroundTasks = listOf(
                ListedBackgroundTask(
                    title = "t",
                    text = "x",
                    text2 = "y",
                    fraction = 0.5,
                    isIndeterminate = false,
                    isCancellable = true,
                    projectName = "p",
                    backendName = "b1",
                ),
            ),
            backends = listOf(
                BackendRef("b1", IdeInfo(name = "IntelliJ IDEA", version = "2026.1", build = "IU-261").toIntelliJInfo()),
            ),
        )
        val keys = mutableSetOf<String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (k, v) -> keys += k; walk(v) }
                is JsonArray -> element.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(McpJson.parseToJsonElement(McpJson.encodeToString(ListWindowsResponse.serializer(), response)))
        return keys
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
            // Non-empty on purpose: with the default emptyList() the walk below never enters a
            // backends[] element, and the description's never-null promise covers those too.
            backends = listOf(
                BackendRef("b-1", IdeInfo(name = "IntelliJ IDEA", version = "2026.1", build = "IU-261").toIntelliJInfo()),
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
