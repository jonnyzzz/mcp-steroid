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
 * Integration test for Gradle test execution via IntelliJ APIs.
 *
 * Validates that GradleRunConfiguration + setRunAsTest(true) + SMTRunnerEventsListener
 * works correctly for executing Gradle tests programmatically inside a Docker IntelliJ container.
 *
 * This is a critical path — arena agents should use this instead of `Bash ./gradlew test`.
 *
 * Uses the test-project which has intentionally-failing JUnit tests (DemoByJonnyzzzTest).
 */
class GradleTestExecutionTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(GradleTestExecutionTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Gradle Test Execution",
            )).waitForProjectReady(
                buildSystem = BuildSystem.GRADLE,
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
    fun `gradle test execution via GradleRunConfiguration with SMTRunner`() {
        val console = session.console

        console.writeStep("Executing Gradle tests via GradleRunConfiguration + SMTRunner")
        val result = session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.SMART_NON_MODAL,
            code = """
                import com.intellij.execution.ProgramRunnerUtil
                import com.intellij.execution.RunManager
                import com.intellij.execution.executors.DefaultRunExecutor
                import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
                import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
                import kotlinx.coroutines.CompletableDeferred
                import kotlinx.coroutines.withTimeout
                import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
                import kotlin.time.Duration.Companion.minutes

                val testFinished = CompletableDeferred<Boolean>()
                var totalTests = 0
                var failedTests = 0

                // Subscribe to SMTRunner test events before launching
                project.messageBus.connect(disposable).subscribe(
                    SMTRunnerEventsListener.TEST_STATUS,
                    object : SMTRunnerEventsListener {
                        override fun onTestingStarted(testsRoot: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy) {
                            println("GRADLE_TESTING_STARTED")
                        }

                        override fun onTestingFinished(testsRoot: com.intellij.execution.testframework.sm.runner.SMTestProxy.SMRootTestProxy) {
                            totalTests = testsRoot.allTests.size
                            failedTests = testsRoot.allTests.count { it.isDefect }
                            println("GRADLE_TESTING_FINISHED: total=${'$'}totalTests, failed=${'$'}failedTests")
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

                // Create Gradle run configuration
                val runManager = RunManager.getInstance(project)
                val factory = GradleExternalTaskConfigurationType.getInstance().configurationFactories.single()
                val runConfig = factory.createTemplateConfiguration(project) as ExternalSystemRunConfiguration

                runConfig.name = "Gradle test (MCP integration)"
                runConfig.settings.externalProjectPath = project.basePath
                runConfig.settings.taskNames = listOf(":test")

                // Enable SMTRunner integration — this is the key setting
                val gradleConfig = runConfig as org.jetbrains.plugins.gradle.service.execution.GradleRunConfiguration
                gradleConfig.isRunAsTest = true

                val settings = runManager.createConfiguration(runConfig, factory)
                runManager.addConfiguration(settings)
                runManager.selectedConfiguration = settings

                // Launch the configuration
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
                }

                // Wait for test execution to complete
                withTimeout(5.minutes) {
                    testFinished.await()
                }

                println("GRADLE_TEST_PASSED=true")
                println("GRADLE_TEST_TOTAL=${'$'}totalTests")
                println("GRADLE_TEST_FAILED=${'$'}failedTests")
            """.trimIndent(),
            taskId = "gradle-test-execution",
            reason = "Execute Gradle tests via GradleRunConfiguration with SMTRunner",
            timeout = 600,
        )

        result.assertExitCode(0, "Gradle test execution via MCP should succeed")
        result.assertOutputContains("GRADLE_TESTING_STARTED", message = "SMTRunner should report testing started")
        result.assertOutputContains("GRADLE_TESTING_FINISHED", message = "SMTRunner should report testing finished")
        result.assertOutputContains("GRADLE_TEST_PASSED=true", message = "Test execution should complete")

        console.writeSuccess("Gradle test execution via GradleRunConfiguration works")
    }
}
