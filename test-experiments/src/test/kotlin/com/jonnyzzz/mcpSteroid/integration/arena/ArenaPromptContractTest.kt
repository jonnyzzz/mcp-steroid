/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaPromptContractTest {

    @Test
    fun `mcp prompt routes multi-site edits through one write action script and keeps verification rerun guardrails`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleMavenTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertFalse(
            prompt.contains("steroid_apply_patch"),
            "The dedicated apply-patch tool was removed — prompts must not name it",
        )
        assertFalse(
            prompt.contains("applyPatch"),
            "The applyPatch { } DSL was removed (#206) — prompts must not route agents to it",
        )
        assertFalse(
            prompt.contains("mcp-steroid://ide/apply-patch"),
            "The apply-patch recipe resource was removed (#206) — prompts must not link it",
        )
        assertTrue(
            prompt.contains("Multi-site edits") && prompt.contains("writeAction { }"),
            "MCP prompt should route multi-site edits through a single writeAction script inside steroid_execute_code",
        )
        assertFalse(
            prompt.contains("Run each Maven/Gradle verification target at most once"),
            "Verification guidance must not forbid legitimate reruns after fixes or skipped tests",
        )
        assertTrue(
            prompt.contains("Do not rerun Maven/Gradle just to recover hidden output"),
            "Prompt should keep the measured duplicate-verification guardrail",
        )
        assertTrue(
            prompt.contains("Rerun when you changed code, saw a real failure, got an incomplete run, or Gradle skipped tests"),
            "Prompt should explicitly preserve legitimate rerun cases",
        )
        assertTrue(
            prompt.contains("Before outputting `ARENA_FIX_APPLIED: yes`, the full suite must exit 0"),
            "Prompt must not weaken the DPAIA full-suite success requirement",
        )
    }

    @Test
    fun `gradle prompt batches targeted tests across subprojects`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleGradleTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertTrue(
            prompt.contains("Batch Gradle targeted tests across subprojects"),
            "Gradle prompt should tell agents to batch multi-subproject targeted test runs",
        )
        assertTrue(
            prompt.contains("repeated `:subproject:test --tests FQCN` pairs"),
            "Gradle prompt should describe the batching shape without relying on a single module",
        )
        assertTrue(
            prompt.contains("then keep the full suite as the final separate run"),
            "Batching targeted tests must not weaken the final full-suite requirement",
        )
    }

    @Test
    fun `gradle mcp prompt inlines ide build before gradle resource fallback`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleGradleTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertTrue(
            prompt.contains("Gradle IDE build first"),
            "Gradle MCP prompt should put the IDE build path before Bash fallback",
        )
        assertTrue(
            prompt.contains("ProjectTaskManager.getInstance(project).build(*modules).await()"),
            "Gradle MCP prompt should include the working IDE-native build recipe",
        )
        assertTrue(
            prompt.contains("If this prints `Build errors: false, aborted: false`, do not run Bash Gradle just to compile"),
            "Gradle MCP prompt should prevent duplicate Bash compile checks after a successful IDE build",
        )
        assertTrue(
            prompt.contains("ProjectDataImportListener.onFinalTasksFinished"),
            "Gradle resource fallback should point agents at the final-tasks sync recipe",
        )
        assertFalse(
            prompt.contains("mcp-steroid://skill/execute-code-maven"),
            "Gradle-specific arena prompts should not ask agents to fetch the Maven resource",
        )
    }

    @Test
    fun `maven mcp prompt does not fetch gradle resource on build abort`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleMavenTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertTrue(
            prompt.contains("call `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-maven`"),
            "Maven MCP prompt should route aborted IDE builds through the Maven sync resource",
        )
        assertFalse(
            prompt.contains("mcp-steroid://skill/execute-code-gradle"),
            "Maven-specific arena prompts should not ask agents to fetch the Gradle resource",
        )
    }

    @Test
    fun `gradle prompt exposes configured jdk before first bash gradle call`() {
        val prompt = ArenaTestRunner(
            container = ContainerDriver(
                logPrefix = "prompt-test",
                containerId = "unused",
                startRequest = StartContainerRequest(),
            ),
            projectGuestDir = "/workspace",
        ).buildPrompt(testCase = sampleMicroshopGradleTestCase(), projectDir = "/home/agent/project-home", withMcp = true)

        assertTrue(
            prompt.contains("Configured project JDK version: **24**"),
            "Microshop Gradle prompt should expose the case-configured JDK version",
        )
        assertTrue(
            prompt.contains("Recommended JAVA_HOME"),
            "First MCP call should print the exact JAVA_HOME agents must reuse for Bash Gradle commands",
        )
        assertTrue(
            prompt.contains("JAVA_HOME=<Recommended JAVA_HOME> ./gradlew ..."),
            "Gradle Bash guidance should use the exact printed JAVA_HOME placeholder",
        )
        assertTrue(
            prompt.contains("FAIL_TO_PASS tests must pass — run them with `JAVA_HOME=<Recommended JAVA_HOME> ./gradlew test --tests <TestClass> --console=plain`"),
            "Targeted Gradle test template should carry the required JAVA_HOME assignment",
        )
        assertTrue(
            prompt.contains("e.g. `JAVA_HOME=<Recommended JAVA_HOME> ./gradlew :module:test --tests <Class> --rerun-tasks --no-daemon`"),
            "Copyable Gradle rerun example should carry the required JAVA_HOME assignment",
        )
        assertTrue(
            prompt.contains("`JAVA_HOME=<Recommended JAVA_HOME> ./gradlew test`, NO `-Dtest=` filter"),
            "Full-suite Gradle template should carry the required JAVA_HOME assignment",
        )
        assertFalse(
            prompt.contains("e.g. `./gradlew :module:test"),
            "Prompt should not include copyable Gradle examples without JAVA_HOME",
        )
        assertFalse(
            prompt.contains("JAVA_HOME=/usr/lib/jvm/temurin-24-jdk-*"),
            "Bash does not expand globs in assignment words; prompt must not offer a wildcard JAVA_HOME command",
        )
        assertTrue(
            prompt.contains("temurin-\$configuredJdkVersion-jdk-"),
            "First MCP call should resolve the configured JDK path instead of requiring a Bash JDK search",
        )
        assertTrue(
            prompt.contains("must start with `/usr/lib/jvm/temurin-24-jdk-`"),
            "Prompt should bind the printed JAVA_HOME back to the case-configured JDK version",
        )
        assertTrue(
            prompt.contains("Do NOT try `JAVA_HOME=/usr/lib/jvm/temurin-21-...` first"),
            "Prompt should directly prevent the measured Microshop Java 21 dead-end",
        )
    }

    private fun sampleMavenTestCase() = DpaiaTestCase(
        instanceId = "dpaia__sample",
        issueNumbers = listOf("1"),
        tags = listOf("Spring", "Maven"),
        repo = "dpaia/sample.git",
        patch = "",
        testPatch = """
            diff --git a/src/test/java/SampleTest.java b/src/test/java/SampleTest.java
            +class SampleTest {}
        """.trimIndent(),
        failToPass = listOf("com.example.SampleTest"),
        passToPass = emptyList(),
        createdAt = "2026-04-26T00:00:00Z",
        baseCommit = "0000000000000000000000000000000000000000",
        problemStatement = "Add the missing endpoint.",
        version = "1",
        isMaven = true,
        buildSystem = "maven",
        testArgs = "",
    )

    private fun sampleGradleTestCase() = sampleMavenTestCase().copy(
        tags = listOf("Spring", "Gradle"),
        isMaven = false,
        buildSystem = "gradle",
        failToPass = listOf(
            "com.example.alpha.AlphaTest",
            "com.example.beta.BetaTest",
        ),
    )

    private fun sampleMicroshopGradleTestCase() = sampleGradleTestCase().copy(
        instanceId = "dpaia__spring__boot__microshop-2",
    )
}
