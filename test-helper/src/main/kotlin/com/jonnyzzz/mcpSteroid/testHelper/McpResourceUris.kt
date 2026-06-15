/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.CreateApplicationConfigPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.DebugAttachRemoteJvmPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.debugger.DemoDebugTestPromptArticle as DebuggerDemoDebugTestPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.CallHierarchyPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.DemoDebugTestPromptArticle as IdeDemoDebugTestPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.FindDuplicatesPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.InspectAndFixPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.OptimizeImportsPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.TestSkillPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.CodingWithIntelliJVfsPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.test.RunTestAtCaretPromptArticle

object McpResourceUris {
    private const val SCHEME = "mcp-steroid"
    private const val SCHEME_SEPARATOR = "://"

    val debuggerOverview: String = buildUri("debugger/overview")
    val debuggerEvaluateExpression: String = buildUri("debugger/evaluate-expression")
    val debuggerWaitForSuspend: String = buildUri("debugger/wait-for-suspend")
    val debuggerStepOver: String = buildUri("debugger/step-over")
    val promptSkill: String = SkillPromptArticle().uri

    /**
     * The prompt articles that were un-gated / made multi-IDE in the #81 (inspect-and-fix)
     * and #98 (un-gate test-debugging entry points) prompt batch. Every one of these must
     * resolve through `steroid_fetch_resource` in a non-IDEA IDE — the end-to-end proof that
     * the article-level `IdeFilter` un-gating reaches the real plugin, not only the static
     * assertion in `:prompts` `PerIdeAvailabilityContractTest`.
     *
     * Sourced from the generated `*PromptArticle().uri` so the list cannot drift from the
     * articles' real URIs (and avoids hardcoded `mcp-steroid://` literals).
     */
    val multiIdePromptBatch: List<String> = listOf(
        InspectAndFixPromptArticle().uri,
        CallHierarchyPromptArticle().uri,
        FindDuplicatesPromptArticle().uri,
        OptimizeImportsPromptArticle().uri,
        IdeDemoDebugTestPromptArticle().uri,
        TestSkillPromptArticle().uri,
        RunTestAtCaretPromptArticle().uri,
        DebuggerDemoDebugTestPromptArticle().uri,
        CreateApplicationConfigPromptArticle().uri,
        DebugAttachRemoteJvmPromptArticle().uri,
        CodingWithIntelliJVfsPromptArticle().uri,
    )

    private fun buildUri(path: String): String = "$SCHEME$SCHEME_SEPARATOR$path"
}
