/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The golden test that matters for the schema-driven CLI (issue #284): CLI metadata (`cliSynopsis`,
 * `cliMinimum`/`cliMaximum`, `cliMissingHint`, `cliFileSource`, and the tool-level
 * `CliCommandSpec.extraOptions`) must never change a single byte of the MCP `inputSchema` that ships to
 * clients. No CLI declaration is filtered out of `asMcpJson()` any more — every declaration is either a
 * hint on a real MCP parameter or lives outside the schema entirely — so this test is what proves the
 * hints stay invisible.
 *
 * `description` values are masked out to a fixed [DESCRIPTION_MASK] placeholder before comparison (see
 * [maskDescriptions]) — those texts are actively-tuned prompt-engineering copy (see e.g. commit
 * `8a1b212b`), and the eight `*ToolSpecSchemaTest.kt` files already pin them individually. Everything
 * else — property set, property order, `type`, `required`, `enum`, `minimum`/`maximum` — stays
 * byte-identical, which is exactly the surface CLI metadata could accidentally leak onto.
 *
 * Each expected string below was captured from `main` (commit 2fb59a5f, the tip of `main` at the time
 * this test was written), NOT from this branch's working tree — capturing it from the edited tree would
 * make the comparison tautological (it would pass no matter what the CLI-metadata edits did to
 * `asMcpJson()`). It was produced by checking out a disposable `git worktree add /tmp/mcp-steroid-main-golden
 * main`, adding a throwaway JUnit test there that instantiates every `devrigToolSpecs(...)` tool with an
 * `unreachableHandler()` handler and writes `Json.encodeToString(JsonObject.serializer(), tool.inputSchema)`
 * for each to a file, running `./gradlew :mcp-steroid-server:test --tests '*GoldenSchemaDumpTest*'` inside
 * that worktree, and then removing the worktree and the throwaway test entirely
 * (`git worktree remove /tmp/mcp-steroid-main-golden --force`) — nothing from that worktree was merged
 * or left behind. The `description` values were then masked out of the captured strings by parsing them
 * as JSON and applying the same [maskDescriptions] transform used below, not by hand-editing the text.
 *
 * **To legitimately update these constants** (a new devrig tool, a new/renamed/removed property, a
 * changed `type`/`required`/`enum`/`minimum`/`maximum`): regenerate from `main` (or another commit known
 * to be free of uncommitted CLI-metadata changes) using the worktree recipe above — never from this
 * branch's working tree, which would make the test tautological again. Before accepting the new
 * constant, eyeball the diff for exactly one thing: does it introduce a **new property**, a **`cli*`
 * key**, or a **new `minimum`/`maximum`** that isn't an intentional, reviewed part of the change you're
 * making? If so, stop — that is precisely the CLI-metadata-leaking-onto-the-wire failure this test
 * exists to catch. A `description`-only difference is invisible here by design; it is covered by the
 * per-tool `*ToolSpecSchemaTest.kt` files instead.
 */
class DevrigToolSpecsGoldenSchemaTest {

    @Test
    fun `every devrig tool's inputSchema is byte-identical to the schema captured from main, descriptions masked`() {
        val tools = devrigToolSpecsForTest().associateBy { it.name }
        assertEquals(8, tools.size, "devrigToolSpecs() must list exactly the 8 tools this golden test covers")

        assertGoldenSchema(tools, "steroid_list_projects", GOLDEN_LIST_PROJECTS)
        assertGoldenSchema(tools, "steroid_list_windows", GOLDEN_LIST_WINDOWS)
        assertGoldenSchema(tools, "steroid_execute_code", GOLDEN_EXECUTE_CODE)
        assertGoldenSchema(tools, "steroid_execute_feedback", GOLDEN_EXECUTE_FEEDBACK)
        assertGoldenSchema(tools, "steroid_take_screenshot", GOLDEN_TAKE_SCREENSHOT)
        assertGoldenSchema(tools, "steroid_input", GOLDEN_INPUT)
        assertGoldenSchema(tools, "steroid_fetch_resource", GOLDEN_FETCH_RESOURCE)
        assertGoldenSchema(tools, "steroid_open_project", GOLDEN_OPEN_PROJECT)
    }

    private fun assertGoldenSchema(
        tools: Map<String, CliToolSpec>,
        toolName: String,
        expected: String,
    ) {
        val tool = tools.getValue(toolName)
        val rendered = Json.encodeToString(JsonObject.serializer(), maskDescriptions(tool.inputSchema))
        assertEquals(expected, rendered, "$toolName: inputSchema (descriptions masked) drifted from the schema captured on main")
    }

    /**
     * Recursively replaces the value of every `"description"` key in [obj] with [DESCRIPTION_MASK],
     * walking nested [JsonObject]s and [JsonArray]s alike. Operates on the parsed [JsonElement] tree —
     * never on the rendered string — so an escaped quote or backslash inside a description can't defeat
     * it the way a regex over the rendered JSON text could.
     */
    private fun maskDescriptions(obj: JsonObject): JsonObject = JsonObject(
        obj.mapValues { (key, value) ->
            if (key == "description") JsonPrimitive(DESCRIPTION_MASK) else maskDescriptionsElement(value)
        },
    )

    private fun maskDescriptionsElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> maskDescriptions(element)
        is JsonArray -> JsonArray(element.map { maskDescriptionsElement(it) })
        else -> element
    }

    private companion object {
        const val DESCRIPTION_MASK = "<description omitted by golden-schema mask>"

        const val GOLDEN_LIST_PROJECTS = """{"type":"object","properties":{},"required":[]}"""
        const val GOLDEN_LIST_WINDOWS = """{"type":"object","properties":{},"required":[]}"""

        const val GOLDEN_EXECUTE_CODE = """{"type":"object","properties":{"project_name":{"type":"string","description":"<description omitted by golden-schema mask>"},"code":{"type":"string","description":"<description omitted by golden-schema mask>"},"task_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"reason":{"type":"string","description":"<description omitted by golden-schema mask>"},"timeout":{"type":"integer","description":"<description omitted by golden-schema mask>"},"modal":{"type":"string","description":"<description omitted by golden-schema mask>","enum":["smart_non_modal","non_modal","unleashed"]}},"required":["project_name","code","task_id","reason"]}"""

        const val GOLDEN_EXECUTE_FEEDBACK = """{"type":"object","properties":{"project_name":{"type":"string","description":"<description omitted by golden-schema mask>"},"task_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"execution_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"success_rating":{"type":"number","description":"<description omitted by golden-schema mask>","minimum":0.0,"maximum":1.0},"explanation":{"type":"string","description":"<description omitted by golden-schema mask>"},"code":{"type":"string","description":"<description omitted by golden-schema mask>"}},"required":["project_name","task_id","success_rating","explanation"]}"""

        const val GOLDEN_TAKE_SCREENSHOT = """{"type":"object","properties":{"project_name":{"type":"string","description":"<description omitted by golden-schema mask>"},"task_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"reason":{"type":"string","description":"<description omitted by golden-schema mask>"},"window_id":{"type":"string","description":"<description omitted by golden-schema mask>"}},"required":["project_name","task_id","reason"]}"""

        const val GOLDEN_INPUT = """{"type":"object","properties":{"project_name":{"type":"string","description":"<description omitted by golden-schema mask>"},"task_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"reason":{"type":"string","description":"<description omitted by golden-schema mask>"},"window_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"sequence":{"type":"string","description":"<description omitted by golden-schema mask>"}},"required":["project_name","task_id","reason","window_id","sequence"]}"""

        const val GOLDEN_FETCH_RESOURCE = """{"type":"object","properties":{"uri":{"type":"string","description":"<description omitted by golden-schema mask>"},"project_name":{"type":"string","description":"<description omitted by golden-schema mask>"}},"required":["uri","project_name"]}"""

        const val GOLDEN_OPEN_PROJECT = """{"type":"object","properties":{"project_path":{"type":"string","description":"<description omitted by golden-schema mask>"},"task_id":{"type":"string","description":"<description omitted by golden-schema mask>"},"reason":{"type":"string","description":"<description omitted by golden-schema mask>"},"trust_project":{"type":"boolean","description":"<description omitted by golden-schema mask>"},"backend_name":{"type":"string","description":"<description omitted by golden-schema mask>"}},"required":["project_path","task_id","reason"]}"""
    }
}
