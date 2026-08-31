/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #456: `docs/guides/AGENT-STEROID-GUIDE.md` restates the `steroid_list_windows` output contract in
 * prose and in a field table. It was the most-edited surface of the fix and the only one with no
 * regression pin — the prompt articles are covered by `ListWindowsMarkdownArticleContractTest`, the
 * tool description by `ListWindowsToolSpecSchemaTest`, this file by nothing.
 *
 * Both guards are derived from the serializer rather than from a list of past phrasings: every field
 * the table names must be a key `ListWindowsResponse` really emits, and the spelling conflations the
 * issue reported must not come back in any form the article ever carried.
 */
class ListWindowsGuideDriftTest {

    private val forbiddenConflations = listOf(
        "`window_id` is also returned by `steroid_list_windows`",
        "`window_id` for screenshot/input tools",
        "window_id` for screenshot/input targeting",
        "Window id from `steroid_list_windows`",
        "null if not a project window",
        "is null for windows",
        "not duplicated on window/task entries",
        "also stored in `screenshot-meta.json`",
    )

    @Test
    fun `every field the per-window table names is a key the serializer emits`() {
        val table = guideSection("**Response Fields (per window):**")
        val named = Regex("""^\|\s*`([A-Za-z_][A-Za-z0-9_]*)`""", RegexOption.MULTILINE)
            .findAll(table).map { it.groupValues[1] }.toSet()
        assertTrue(named.isNotEmpty(), "no `field` rows found in the per-window table:\n$table")
        assertEquals(
            emptySet<String>(),
            named - liveResponseKeys(),
            "the guide's per-window table names fields no ListedWindow emits (live keys: ${liveResponseKeys()})",
        )
        assertTrue(named.contains("windowId"), "the table must name the live output key windowId: $table")
        assertTrue(named.contains("project_path"), "the table must name project_path: $table")
    }

    @Test
    fun `the guide never re-conflates the windowId output key with the window_id input`() {
        val text = guideFile().readText()
        for (phrase in forbiddenConflations) {
            assertTrue(!text.contains(phrase), "old #456 conflation is back in the guide: '$phrase'")
        }
        assertTrue(
            text.contains("`windowId` value is what you pass as the `window_id` input"),
            "the guide must state the output->input key translation explicitly",
        )
    }

    /** The markdown between [heading] and the following blank line that ends its table. */
    private fun guideSection(heading: String): String {
        val text = guideFile().readText()
        val start = text.indexOf(heading)
        assertTrue(start >= 0, "'$heading' not found in ${guideFile()} — the field table is the pinned surface")
        val end = text.indexOf("\n\n", start).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    /**
     * Hard requirement, not an assumption: `:mcp-steroid-server:test` always sets `mcp.repo.root`
     * (see the module's build.gradle.kts), and a missing guide is exactly the drift this catches.
     */
    private fun guideFile(): File {
        val root = System.getProperty("mcp.repo.root")
            ?: error("mcp.repo.root is not set — the :mcp-steroid-server:test Gradle task always sets it")
        val file = File(File(root), "docs/guides/AGENT-STEROID-GUIDE.md")
        assertTrue(file.isFile, "$file not found — the guide drift gate must see the published guide")
        return file
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
}
