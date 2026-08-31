/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.openProject.OverviewPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #456: `steroid_list_windows` entries carry a camelCase `windowId` (the snake_case spelling is
 * reserved for the `*_name`/`*_path` routing keys, #381), while `window_id` is the INPUT parameter
 * of the screenshot/input tools. Prompt articles used to re-conflate the two and to claim absent
 * routing keys are "null" — this pins the prose of the two highest-traffic articles.
 *
 * Named `*MarkdownArticleContract*` so it runs in the fast path CLAUDE.md documents for prose-only
 * prompt edits (`./gradlew :prompts:test --tests '*MarkdownArticleContract*'`).
 */
class ListWindowsMarkdownArticleContractTest {

    private val forbiddenConflations = listOf(
        // list_windows does NOT return a window_id key. The first three are the exact pre-fix
        // sentences (skill.md's screenshot line and its two parameter mirrors); the `targeting`
        // variant lived in the Kotlin tool description and is pinned so copying it into an article
        // is caught too.
        "`window_id` is also returned by `steroid_list_windows`",
        "`window_id` for screenshot/input tools",
        "window_id` for screenshot/input targeting",
        "Window id from `steroid_list_windows`",
        // The null-vs-omitted lie:
        "null if not a project window",
        "is null for windows",
        // The project_path misdirection: the path is ON the window entry — only the display name
        // needs steroid_list_projects.
        "not duplicated on window/task entries",
        // screenshot-meta.json has camelCase keys (ScreenshotMeta declares no @SerialName), so the
        // window_id spelling must never be attached to that file.
        "also stored in `screenshot-meta.json`",
    )

    @Test
    fun `skill article teaches the windowId to window_id translation and absent-not-null`() {
        val prompt = SkillPromptArticle().readPayload(PromptsContext("IU", 261))

        assertTrue(
            prompt.contains("carries a `windowId`") &&
                prompt.contains("pass its value as the `window_id` input"),
            "the list_windows section must teach the output→input key translation explicitly",
        )
        assertTrue(
            prompt.contains("has no `project_name`/`project_path` keys"),
            "the article must keep the absent-not-null note for unbound windows",
        )
        assertTrue(
            prompt.contains("windows also carry the project's"),
            "the article must state project_path is on the window entry, not only in steroid_list_projects",
        )
        assertTrue(
            prompt.contains("that file's keys are camelCase throughout"),
            "the screenshot section must say screenshot-meta.json uses camelCase keys, not window_id",
        )
        for (phrase in forbiddenConflations) {
            assertFalse(prompt.contains(phrase), "old #456 conflation must not come back: '$phrase'")
        }
    }

    @Test
    fun `open-project overview polling table names windowId and does not misdirect to list_projects`() {
        val prompt = OverviewPromptArticle().readPayload(PromptsContext("IU", 261))

        assertTrue(
            prompt.contains("`windowId`"),
            "the polling table must name the windowId output key",
        )
        assertTrue(
            prompt.contains("omitted, not null"),
            "the polling table must state routing keys are omitted, not null",
        )
        for (phrase in forbiddenConflations) {
            assertFalse(prompt.contains(phrase), "old #456 conflation must not come back: '$phrase'")
        }
    }
}
