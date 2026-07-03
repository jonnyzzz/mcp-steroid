/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeToolDescriptionPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptRoutingContractTest {

    @Test
    fun `execute code tool routes multi-site literal edits to a single write action script`() {
        val prompt = ExecuteCodeToolDescriptionPromptArticle().readPayload(PromptsContext("IU", 253))

        assertFalse(
            prompt.contains("steroid_apply_patch"),
            "steroid_apply_patch was removed — the prompt must not name it",
        )
        assertFalse(
            prompt.contains("applyPatch"),
            "the applyPatch { } DSL was removed (#206) — the prompt must not route agents to it",
        )
        assertFalse(
            prompt.contains("mcp-steroid://ide/apply-patch"),
            "the apply-patch recipe resource was removed (#206) — the prompt must not link it",
        )
        assertTrue(
            prompt.contains("Multi-site edits"),
            "execute-code tool description must keep multi-site edit guidance (single writeAction script)",
        )
        assertTrue(
            prompt.contains("writeAction { }") && prompt.contains("VfsUtil.saveText"),
            "the multi-site recipe must teach the writeAction { } + VfsUtil.saveText shape " +
                "(pinned by MultiSiteEditRecipeTest, which executes the same shape)",
        )
        assertTrue(
            prompt.contains("error(\"not found:"),
            "lookup examples must teach `?: error(\"not found: ...\")` over `!!` (#156)",
        )
    }
}
