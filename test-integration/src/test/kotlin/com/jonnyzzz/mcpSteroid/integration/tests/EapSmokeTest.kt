/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * EAP smoke test for MCP Steroid plugin compatibility.
 *
 * Validates core plugin functionality via direct MCP HTTP calls (no AI agents):
 * 1. Plugin loads and MCP server starts
 * 2. steroid_list_projects returns projects
 * 3. steroid_list_windows returns window state
 * 4. steroid_execute_code compiles and runs Kotlin code
 * 5. Screenshot capture works via execute_code
 *
 * Runs on both stable and EAP IDE versions via Gradle tasks:
 * - ./gradlew :test-integration:testReleaseSmokeIdea --tests '*EapSmokeTest*'
 * - ./gradlew :test-integration:testReleaseSmokeIdeaEap --tests '*EapSmokeTest*'
 */
class EapSmokeTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `plugin loads and core MCP tools work`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "EAP Smoke"
        ))
        val console = session.console

        // 1. list_projects
        console.writeStep("Verifying steroid_list_projects")
        val projects = session.mcpSteroid.mcpListProjects()
        Assertions.assertTrue(projects.isNotEmpty(), "Should have at least one project")
        console.writeSuccess("Found ${projects.size} project(s): ${projects.map { it.name }}")

        // 2. list_windows
        console.writeStep("Verifying steroid_list_windows")
        val windows = session.mcpSteroid.mcpListWindows()
        Assertions.assertTrue(windows.isNotEmpty(), "Should have at least one window")
        val projectWindow = windows.find { it.projectName != null }
        Assertions.assertNotNull(projectWindow, "Should have a window with project")
        Assertions.assertFalse(projectWindow!!.modalDialogShowing, "Should not have modal dialog")
        console.writeSuccess("Windows OK, project initialized: ${projectWindow.projectInitialized}")

        // 3. execute_code — basic compilation and execution
        console.writeStep("Verifying steroid_execute_code")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val version = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
                println("EAP_SMOKE_OK: ${'$'}version")
            """.trimIndent(),
            taskId = "eap-smoke",
            reason = "EAP smoke test - code execution",
        ).assertExitCode(0, "execute_code should succeed")
            .assertOutputContains("EAP_SMOKE_OK", message = "should print IDE version")

        // 4. execute_code — screenshot capture via vision subsystem
        console.writeStep("Verifying screenshot capture via execute_code")
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val screenshotPath = takeIdeScreenshot()
                println("SCREENSHOT_OK: ${'$'}screenshotPath")
            """.trimIndent(),
            taskId = "eap-smoke-screenshot",
            reason = "EAP smoke test - screenshot capture",
        ).assertExitCode(0, "screenshot capture should succeed")
            .assertOutputContains("SCREENSHOT_OK", message = "should capture screenshot")

        console.writeSuccess("All EAP smoke checks passed")
    }
}
