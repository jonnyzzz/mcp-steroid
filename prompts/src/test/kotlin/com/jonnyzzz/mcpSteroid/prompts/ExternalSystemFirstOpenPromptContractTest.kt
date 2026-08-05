/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.prompts

import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExternalSystemFirstOpenPromptContractTest {
    @Test
    fun `Maven recipe triggers first import before awaiting configuration`() {
        val prompt = ExecuteCodeMavenPromptArticle().readPayload(PromptsContext("IU", 253))

        for (fact in listOf(
            "First open",
            "ProjectRootManager.getInstance(project).projectSdk",
            "null project SDK is a warning, not automatic failure",
            "task needs JDK library resolution",
            "scheduleUpdateAllMavenProjects",
            "Observation.awaitConfiguration(project)",
            "calling it alone is not a trigger",
        )) {
            assertTrue(fact in prompt, "Maven first-open recipe must mention '$fact': $prompt")
        }

        assertTrue(
            "ModuleRootManager.getInstance(module).sdk" in prompt,
            "Maven SDK repair must consider configured module SDKs before global registrations: $prompt",
        )
        assertTrue(
            "No unambiguous Java SDK" in prompt,
            "Maven SDK repair must fail visibly instead of guessing among registered SDKs: $prompt",
        )
        assertTrue(
            "FileUtil::toCanonicalPath" in prompt && "javaSdkType.isValidSdkHome" in prompt,
            "Maven SDK repair must canonicalize and validate an explicit JDK home through IntelliJ APIs: $prompt",
        )
        assertTrue(
            "val configuredProjectSdk = readAction" in prompt,
            "Maven SDK repair must read the post-write project SDK under a read action: $prompt",
        )
        assertFalse(
            "getSdksOfType(JavaSdk.getInstance()).firstOrNull()" in prompt,
            "Maven SDK repair must not blindly apply the first registered JDK: $prompt",
        )
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
