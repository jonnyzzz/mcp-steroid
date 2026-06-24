/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that ProjectTaskManager.build() compiles a Gradle project
 * inside a Docker IntelliJ container. Replaces 4 Bash `gradlew compile`
 * calls observed in arena analysis.
 *
 * Uses the default test-project (Gradle/Kotlin) fixture.
 */
class GradleCompileTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(GradleCompileTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Gradle Compile",
            )).waitForProjectReady(
                buildSystem = BuildSystem.GRADLE,
                projectJdkVersion = "25",
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `ProjectTaskManager builds Gradle project`() {
        val console = session.console

        console.writeStep("Compiling Gradle project via ProjectTaskManager.build()")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.task.ProjectTaskManager
                import com.intellij.openapi.module.ModuleManager
                import org.jetbrains.concurrency.await
                import org.jetbrains.plugins.gradle.settings.GradleSettings

                val modules = ModuleManager.getInstance(project).modules
                println("MODULES=${'$'}{modules.size}")
                modules.forEach { println("  module: ${'$'}{it.name}") }

                val linkedGradleProject = GradleSettings.getInstance(project)
                    .getLinkedProjectSettings(project.basePath!!)
                    ?: error("Gradle linked project settings not found")
                val gradleJvm = linkedGradleProject.gradleJvm ?: "<null>"
                println("GRADLE_JVM=${'$'}gradleJvm")
                require(gradleJvm.contains("25")) {
                    "Gradle JVM should use the configured JDK 25, but was ${'$'}gradleJvm"
                }

                val result = ProjectTaskManager.getInstance(project).build(*modules).await()
                println("BUILD_ERRORS=${'$'}{result.hasErrors()}")
                println("BUILD_ABORTED=${'$'}{result.isAborted}")
            """.trimIndent(),
            taskId = "gradle-compile",
            reason = "Compile Gradle project via ProjectTaskManager (replaces Bash gradlew compile)",
            timeout = 300,
            modal = ModalMode.SMART_NON_MODAL,
        )

        result.assertExitCode(0, "Gradle compile via ProjectTaskManager should succeed")
        result.assertOutputContains("GRADLE_JVM=", message = "Gradle JVM setting should be printed")
        result.assertOutputContains("BUILD_ERRORS=false", message = "Gradle compilation should have no errors")
        result.assertOutputContains("BUILD_ABORTED=false", message = "Gradle compilation should not abort")

        console.writeSuccess("Gradle project compilation via ProjectTaskManager works")
    }
}
