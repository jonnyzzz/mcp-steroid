/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalSystemFirstOpenPromptContractTest {
    @Test
    fun `Maven recipe triggers first import before awaiting configuration`() {
        val prompt = ExecuteCodeMavenPromptArticle().readPayload(PromptsContext("IU", 253))

        for (fact in listOf(
            "First open",
            "scheduleUpdateAllMavenProjects",
            "Observation.awaitConfiguration(project)",
            "calling it alone is not a trigger",
        )) {
            assertTrue(fact in prompt, "Maven first-open recipe must mention '$fact': $prompt")
        }
    }

    @Test
    fun `Gradle recipe triggers first import and awaits final tasks`() {
        val prompt = ExecuteCodeGradlePromptArticle().readPayload(PromptsContext("IU", 253))

        for (fact in listOf(
            "First open",
            "ExternalSystemUtil.refreshProject",
            "onFinalTasksFinished",
            "Waiting for smart mode alone does not trigger",
        )) {
            assertTrue(fact in prompt, "Gradle first-open recipe must mention '$fact': $prompt")
        }
    }
}
