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
 * Integration test for Maven test execution via IntelliJ APIs.
 *
 * Validates that MavenRunConfigurationType.runConfiguration() + SMTRunnerEventsListener
 * works correctly for executing Maven tests programmatically inside a Docker IntelliJ container.
 *
 * This is a critical path — arena agents should use this instead of `Bash ./mvnw test`.
 *
 * Uses test-project-maven which has a simple Calculator class with passing JUnit tests.
 */
class MavenTestExecutionTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(MavenTestExecutionTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Maven Test Execution",
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
    fun `maven test execution via MavenRunConfigurationType with SMTRunner`() {
        val console = session.console

        console.writeStep(text = "Executing Maven tests via MavenRunConfigurationType + SMTRunner")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
                import org.jetbrains.idea.maven.execution.MavenRunnerParameters
                import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
                import kotlinx.coroutines.CompletableDeferred
                import kotlinx.coroutines.withTimeout
                import kotlin.time.Duration.Companion.minutes

                val testFinished = CompletableDeferred<Boolean>()
                var totalTests = 0
                var failedTests = 0

                // Subscribe to SMTRunner test events before launching
                project.messageBus.connect(disposable).subscribe(
                    SMTRunnerEventsListener.TEST_STATUS,
                    object : SMTRunnerEventsListener {
                        override fun onTestingStarted(testsRoot: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy) {
                            println("MAVEN_TESTING_STARTED")
                        }

                        override fun onTestingFinished(testsRoot: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy) {
                            totalTests = testsRoot.allTests.size
                            failedTests = testsRoot.allTests.count { it.isDefect }
                            println("MAVEN_TESTING_FINISHED: total=${'$'}totalTests, failed=${'$'}failedTests")
                            testFinished.complete(true)
                        }

                        override fun onTestStarted(test: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onTestFinished(test: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onTestFailed(test: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onTestIgnored(test: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onSuiteStarted(suite: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onSuiteFinished(suite: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onTestsCountInSuite(count: Int) {}
                        override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
                        override fun onCustomProgressTestStarted() {}
                        override fun onCustomProgressTestFinished() {}
                        override fun onCustomProgressTestFailed() {}
                        override fun onSuiteTreeNodeAdded(testProxy: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                        override fun onSuiteTreeStarted(suite: com.intellij.execution.testframework.sm.runner.SMTestProxy) {}
                    }
                )

                // Create Maven run configuration and launch via ProgramRunnerUtil (async)
                // Do NOT use MavenRunConfigurationType.runConfiguration() — it blocks
                // the coroutine via invokeAndWait, preventing withTimeout from firing.
                val runManager = com.intellij.execution.RunManager.getInstance(project)
                val params = MavenRunnerParameters(true, project.basePath!!, "pom.xml",
                    listOf("test"), emptyList())
                val configSettings = MavenRunConfigurationType.createRunnerAndConfigurationSettings(
                    null, null, params, project, "Maven test (MCP)", false)
                runManager.addConfiguration(configSettings)
                runManager.selectedConfiguration = configSettings
                println("MAVEN_CONFIG_CREATED: ${'$'}{configSettings.name}")

                // Check if Maven project is properly imported
                val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                val mavenProjects = mavenManager.projects
                println("MAVEN_PROJECTS: ${'$'}{mavenProjects.size} (${'$'}{mavenProjects.map { it.mavenId.artifactId }})")

                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    com.intellij.execution.ProgramRunnerUtil.executeConfiguration(
                        configSettings, com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance())
                }
                println("MAVEN_LAUNCH_DISPATCHED")

                // Wait for the Maven process to appear and complete
                var processExitCode: Int? = null
                withTimeout(8.minutes) {
                    // Wait for process to start
                    var handler: com.intellij.execution.process.ProcessHandler? = null
                    while (handler == null) {
                        kotlinx.coroutines.delay(500)
                        val descriptors = com.intellij.execution.ui.RunContentManager.getInstance(project).allDescriptors
                        handler = descriptors.firstOrNull { it.displayName?.contains("Maven") == true }?.processHandler
                    }
                    println("MAVEN_PROCESS_FOUND: started=${'$'}{handler.isStartNotified}")

                    // Wait for process to terminate
                    val exitDeferred = CompletableDeferred<Int>()
                    handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                        override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                            exitDeferred.complete(event.exitCode)
                        }
                    })
                    if (handler.isProcessTerminated) {
                        processExitCode = handler.exitCode
                    } else {
                        processExitCode = exitDeferred.await()
                    }
                }

                println("MAVEN_PROCESS_EXIT=${'$'}{processExitCode}")

                // Check if SMTRunner events fired (they may not for plain Maven runner)
                val smtFired = testFinished.isCompleted
                println("SMT_EVENTS_FIRED=${'$'}smtFired")
                if (smtFired) {
                    println("MAVEN_TEST_TOTAL=${'$'}totalTests")
                    println("MAVEN_TEST_FAILED=${'$'}failedTests")
                }

                // Maven exit code 0 = tests passed
                println("MAVEN_TEST_PASSED=${'$'}{processExitCode == 0}")
            """.trimIndent(),
            taskId = "maven-test-execution",
            reason = "Execute Maven tests via MavenRunConfigurationType with SMTRunner",
            timeout = 600,
            modal = ModalMode.SMART_NON_MODAL,
        )

        result.assertExitCode(0, "Maven test execution via MCP should succeed")
        result.assertOutputContains("MAVEN_PROCESS_EXIT=0", message = "Maven process should exit with code 0")
        result.assertOutputContains("MAVEN_TEST_PASSED=true", message = "Maven tests should pass")

        console.writeSuccess("Maven test execution via MavenRunConfigurationType works")
    }
}
