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
 * Validates that MavenRunConfigurationType.runConfiguration() with the `install`
 * goal works inside a Docker IntelliJ container. Replaces 7 Bash `mvnw install`
 * calls observed in arena analysis.
 *
 * Uses test-project-maven which has a simple Calculator class with passing JUnit tests.
 */
class MavenInstallTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(MavenInstallTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Maven Install",
                project = IntelliJProject.MavenTestProject,
            )).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
                compileProject = true,
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
    fun `Maven install goal via MavenRunConfigurationType`() {
        val console = session.console

        console.writeStep(text = "Running Maven install via MavenRunConfigurationType")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
                import org.jetbrains.idea.maven.execution.MavenRunnerParameters
                import com.intellij.execution.process.ProcessEvent
                import com.intellij.execution.process.ProcessListener
                import kotlinx.coroutines.CompletableDeferred
                import kotlinx.coroutines.withTimeout
                import kotlin.time.Duration.Companion.minutes

                val done = CompletableDeferred<Int>()

                MavenRunConfigurationType.runConfiguration(
                    project,
                    MavenRunnerParameters(
                        /* isPomExecution= */ true,
                        /* workingDirPath= */ project.basePath!!,
                        /* pomFileName= */ "pom.xml",
                        /* goals= */ listOf("install", "-DskipTests"),
                        /* profiles= */ emptyList()
                    ),
                    /* settings (MavenGeneralSettings) = */ null,
                    /* runnerSettings (MavenRunnerSettings) = */ null,
                ) { descriptor ->
                    descriptor?.processHandler?.addProcessListener(object : ProcessListener {
                        override fun processTerminated(event: ProcessEvent) {
                            done.complete(event.exitCode)
                        }
                    })
                }

                val exitCode = withTimeout(5.minutes) { done.await() }
                println("MAVEN_INSTALL_EXIT_CODE=${'$'}exitCode")
                println("MAVEN_INSTALL=${'$'}{exitCode == 0}")
            """.trimIndent(),
            taskId = "maven-install",
            reason = "Run Maven install goal via MavenRunConfigurationType (replaces Bash mvnw install)",
            timeout = 600,
            modal = ModalMode.SMART_NON_MODAL,
        )

        result.assertExitCode(0, "Maven install via exec_code should succeed")
        result.assertOutputContains("MAVEN_INSTALL=true", message = "Maven install should complete successfully")

        console.writeSuccess("Maven install via MavenRunConfigurationType works")
    }
}
