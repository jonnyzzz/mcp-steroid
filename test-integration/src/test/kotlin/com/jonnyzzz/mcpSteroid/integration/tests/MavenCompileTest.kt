/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
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
 * Validates that ProjectTaskManager.build() compiles a Maven project
 * inside a Docker IntelliJ container. Replaces 48 Bash `mvnw test-compile`
 * calls observed in arena analysis.
 *
 * Uses test-project-maven which has a simple Calculator class with passing JUnit tests.
 */
class MavenCompileTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(MavenCompileTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Maven Compile",
                project = IntelliJProject.MavenTestProject,
            )).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
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
    fun `ProjectTaskManager builds Maven project`() {
        val console = session.console

        console.writeStep(text = "Compiling Maven project via ProjectTaskManager.build()")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.task.ProjectTaskManager
                import com.intellij.openapi.module.ModuleManager
                import org.jetbrains.concurrency.await

                val modules = ModuleManager.getInstance(project).modules
                println("MODULES=${'$'}{modules.size}")
                modules.forEach { println("  module: ${'$'}{it.name}") }

                val result = ProjectTaskManager.getInstance(project).build(*modules).await()
                println("BUILD_ERRORS=${'$'}{result.hasErrors()}")
                println("BUILD_ABORTED=${'$'}{result.isAborted}")
            """.trimIndent(),
            taskId = "maven-compile",
            reason = "Compile Maven project via ProjectTaskManager (replaces Bash mvnw test-compile)",
            timeout = 300,
            modal = ModalMode.SMART_NON_MODAL,
        )

        result.assertExitCode(0, "Maven compile via ProjectTaskManager should succeed")
        result.assertOutputContains("BUILD_ERRORS=false", message = "Maven compilation should have no errors")

        console.writeSuccess("Maven project compilation via ProjectTaskManager works")
    }
}
