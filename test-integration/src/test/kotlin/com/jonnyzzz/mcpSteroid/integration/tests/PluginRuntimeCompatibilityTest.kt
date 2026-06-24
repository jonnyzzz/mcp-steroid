package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeChannel
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
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
 * Runtime compatibility: validates the production plugin (built against
 * `McpSteroidIdeTargets.buildTarget` = 261 since commit 5) works
 * correctly when loaded into newer IDE versions and into IDEs that
 * don't bundle the same plugin set as IntelliJ Idea Ultimate.
 *
 * Strategy: install the production plugin .zip into the latest stable
 * and EAP IDEs and exercise core MCP tools. The list_windows call
 * catches the family of bugs around the `c.i.o.u.Pair` vs `kotlin.Pair`
 * direction (mcp-steroid#18) — a regression in either direction surfaces
 * here as ClassCastException at runtime.
 *
 * The PyCharm cases (since followup #1) are the prod-minimal-deps gate:
 * vanilla PyCharm does NOT ship `com.intellij.java` or
 * `org.jetbrains.kotlin`. If the plugin accidentally pulls a type from
 * either plugin into a runtime-required path, the PyCharm container
 * fails to start the plugin and the test catches it.
 *
 * Run:
 *   ./gradlew :test-integration:test --tests '*PluginRuntimeCompatibilityTest*'
 */
class PluginRuntimeCompatibilityTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat idea stable`() =
        verifyRuntimeCompat(
            distribution = IdeDistribution.Latest(IdeProduct.IntelliJIdea, IdeChannel.STABLE),
            dockerFileBase = "ide-agent",
        )

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat idea eap`() =
        verifyRuntimeCompat(
            distribution = IdeDistribution.Latest(IdeProduct.IntelliJIdea, IdeChannel.EAP),
            dockerFileBase = "ide-agent",
        )

    /**
     * Vanilla PyCharm — prod-minimal-deps gate. The production plugin must
     * load even though PyCharm doesn't ship `com.intellij.java` or
     * `org.jetbrains.kotlin`. A regression that drags one of those plugins
     * into a runtime-required path fails the plugin load here.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat pycharm stable`() =
        verifyRuntimeCompat(
            distribution = IdeDistribution.Latest(IdeProduct.PyCharm, IdeChannel.STABLE),
            dockerFileBase = "pycharm-agent",
        )

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `runtime compat pycharm eap`() =
        verifyRuntimeCompat(
            distribution = IdeDistribution.Latest(IdeProduct.PyCharm, IdeChannel.EAP),
            dockerFileBase = "pycharm-agent",
        )

    private fun verifyRuntimeCompat(
        distribution: IdeDistribution,
        dockerFileBase: String,
    ) = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime,IntelliJContainerOpts(
            dockerFileBase,
            consoleTitle = "runtime-compat",
            distribution = distribution,
        ))

        // 1. list_projects — plugin loaded, MCP server started
        val projects = session.mcpSteroid.mcpListProjects()
        Assertions.assertTrue(projects.isNotEmpty(), "Should have at least one project")

        // 2. list_windows — triggers mcp-steroid#18 on 262+
        val windows = session.mcpSteroid.mcpListWindows()
        Assertions.assertTrue(windows.isNotEmpty(), "Should have at least one window")
        val projectWindow = windows.find { it.projectName != null }
        Assertions.assertNotNull(projectWindow, "Should have a window with project")
        Assertions.assertFalse(projectWindow!!.modalDialogShowing, "Should not have modal dialog")

        // 3. execute_code — compilation + execution works
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val version = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
                println("RUNTIME_COMPAT_OK: ${'$'}version")
            """.trimIndent(),
            taskId = "runtime-compat",
            reason = "Runtime compatibility check",
        ).assertExitCode(0, "execute_code should succeed")
            .assertOutputContains("RUNTIME_COMPAT_OK", message = "should print IDE version")
    }
}
