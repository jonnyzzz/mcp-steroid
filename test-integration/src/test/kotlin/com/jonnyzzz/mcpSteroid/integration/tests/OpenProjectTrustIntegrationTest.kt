/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.McpWindowInfo
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class OpenProjectTrustIntegrationTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `open project trusts path by default and shows no modal`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            "ide-agent",
            consoleTitle = "open-project-trust",
            disableProjectTrustChecks = false,
            trustAllProjectPaths = false,
        )
        val console = session.console
        val secondaryProjectPath = "/home/agent/open-project-trust-secondary"

        console.writeStep(1, "Creating secondary project directory")
        session.scope.startProcessInContainer {
            this
                .args(
                    "bash",
                    "-lc",
                    """
                        set -euo pipefail
                        rm -rf "$secondaryProjectPath"
                        mkdir -p "$secondaryProjectPath"
                        printf '# Open Project Trust Test\n' > "$secondaryProjectPath/README.md"
                    """.trimIndent(),
                )
                .timeoutSeconds(30)
                .description("Create secondary project for trusted open test")
        }.awaitForProcessFinish().assertExitCode(0, "Failed to create secondary project")

        console.writeStep(2, "Verifying secondary project starts untrusted")
        session.assertTrustedState(secondaryProjectPath, expectedTrusted = false, taskId = "trust-before-open")

        console.writeStep(3, "Opening project through MCP default trust path")
        session.mcpSteroid.mcpOpenProject(secondaryProjectPath, trustProject = null)

        console.writeStep(4, "Waiting for secondary project without modal dialogs")
        waitForOpenedProjectWithoutModal(session, secondaryProjectPath)

        console.writeStep(5, "Verifying secondary project was registered as trusted")
        session.assertTrustedState(secondaryProjectPath, expectedTrusted = true, taskId = "trust-after-open")
        console.writeSuccess("MCP open_project trusted the path and opened without a modal dialog")
    }

    private fun IntelliJContainer.assertTrustedState(
        projectPath: String,
        expectedTrusted: Boolean,
        taskId: String,
    ) {
        mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.ide.trustedProjects.TrustedProjects
                import java.nio.file.Path

                println("TRUSTED_STATE: ${'$'}{TrustedProjects.isProjectTrusted(Path.of("$projectPath"))}")
            """.trimIndent(),
            taskId = taskId,
            reason = "Check trusted project state for open_project integration test",
        ).assertExitCode(0, "trusted-state check should succeed")
            .assertOutputContains("TRUSTED_STATE: $expectedTrusted")
    }

    private fun waitForOpenedProjectWithoutModal(
        session: IntelliJContainer,
        projectPath: String,
        timeoutMillis: Long = 180_000L,
    ) {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "not polled"

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = session.mcpSteroid.mcpListWindows(timeoutSeconds = 120)
            val modalWindows = windows.filter { it.modalDialogShowing }
            Assertions.assertTrue(
                modalWindows.isEmpty(),
                "Unexpected modal dialog while opening $projectPath. Windows: $windows",
            )

            val projectWindows = windows.filter { it.projectPath == projectPath }
            if (projectWindows.any { it.projectInitialized == true && it.indexingInProgress == false }) {
                return
            }

            lastStatus = describe(projectWindows.ifEmpty { windows })
            Thread.sleep(1_000L)
        }

        Assertions.fail<Unit>(
            "Timed out waiting for $projectPath to open without a modal dialog. Last status: $lastStatus",
        )
    }

    private fun describe(windows: List<McpWindowInfo>): String =
        windows.joinToString(prefix = "[", postfix = "]") { window ->
            "name=${window.projectName}, path=${window.projectPath}, modal=${window.modalDialogShowing}, " +
                    "indexing=${window.indexingInProgress}, initialized=${window.projectInitialized}"
        }
}
