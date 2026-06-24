/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test for DialogKiller functionality.
 *
 * This test runs the IDE in a Docker container with full GUI (via Xvfb) and verifies
 * that the dialog killer can detect and close modal dialogs.
 *
 * Two modes are tested:
 * 1. Explicit: call dialogKiller().killProjectDialogs() from script code
 * 2. Automatic: the pre-execution dialog killer (dialog_killer=true) closes dialogs before code runs
 *
 * Uses direct MCP HTTP calls (bypassing AI agents) for reliable testing.
 */
class DialogKillerIntegrationTest {
    private val testDialogTitle = "MCP Steroid Test Modal Dialog"

    companion object {
        val lifetime by lazy { CloseableStackHost(this::class.java.simpleName) }
        // `waitForProjectReady()` is required: `IntelliJContainer.create` only starts the
        // IDE process, it does NOT wait for `ProjectManager.openProjects` to populate.
        // Test bodies here issue `mcpExecuteCode` straight away, and without an explicit
        // wait they race the project open and fail with `No project found`. Previously
        // the Corretto-consent modal (fixed in `mcpResolveUnknownSdks`) happened to
        // block long enough to mask the race; no such accidental delay now.
        val session by lazy {
            IntelliJContainer.create(
                lifetime, IntelliJContainerOpts(
                    consoleTitle = "Dialog Killer",
                    // Project content is irrelevant here — use the empty project so setup skips
                    // JDK setup and build-system import (fast, no Gradle/Maven to configure).
                    project = IntelliJProject.EmptyProject,
                )
            ).waitForProjectReady()
        }
        val console get() = session.console

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    private fun doTest(modeName: String, closeAction: (IntelliJContainer) -> Unit) {
        console.writeInfo("Mode: $modeName")

        // Step 1: Open a custom modal DialogWrapper and leave it open (dialog killer disabled)
        console.writeStep("Opening test modal dialog")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = """
                // modal=unleashed → no pre-flight modality checks and no dialog monitor, so the modal
                // this opens stays up after the execution returns (for the close step to dismiss).
                // Open the test modal from a VANILLA EDT scope — a fresh CoroutineScope(Dispatchers.EDT),
                // NOT this MCP execution's coroutine context — so it mirrors a real, user-opened modal.
                // show() blocks the launched coroutine inside the modal loop; the launch is fire-and-forget.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.EDT).launch {
                    val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                        init {
                            title = "$testDialogTitle"
                            setModal(true)
                            init()
                        }

                        override fun createCenterPanel(): javax.swing.JComponent {
                            val panel = javax.swing.JPanel()
                            panel.add(javax.swing.JLabel("Dialog killer integration test"))
                            return panel
                        }
                    }
                    dialog.show()
                }

                kotlinx.coroutines.delay(1000)
                println("Test modal dialog opened")
            """.trimIndent(),
            taskId = "open-test-modal-dialog",
            reason = "Open test modal dialog",
        ).assertExitCode(0)

        // Step 2: Verify test modal dialog appeared via xcvb window list
        console.writeStep("Verifying test modal dialog is visible")
        val dialogWindow = {
            val idePid = session.pid
            val listWindows = session.windows.listWindows()
            console.writeInfo("[TEST] Windows after opening test modal dialog:")
            listWindows.filter { it.pid == idePid }.forEach { println("  $it") }

            listWindows.find { it.title == testDialogTitle && it.pid == idePid }
        }


        Assertions.assertNotNull(dialogWindow(), "Test modal dialog should be visible")
        console.writeSuccess("Test modal dialog visible")

        // Step 3: Execute the close action
        console.writeStep("Running dialog killer ($modeName)")
        closeAction(session)

        // Step 4: Verify test modal dialog is gone via xcvb window list
        console.writeStep("Verifying test modal dialog is gone")
        console.writeInfo("[TEST] Windows after dialog killer:")
        Assertions.assertNull(dialogWindow(), "Test modal dialog should have been closed by dialog killer")
        console.writeSuccess("Test modal dialog closed")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `explicit dialog killer via script API`() = doTest("explicit") { session ->
        // modal=unleashed so the pre-flight does NOT sweep — the explicit context API does the closing.
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = """
                val closed = closeModalDialogs()
                println("Explicit dialog killer closed ${'$'}closed dialog(s)")
            """.trimIndent(),
            taskId = "explicit-dialog-killer",
            reason = "Explicitly call closeModalDialogs() from script",
        ).assertExitCode(0)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `automatic dialog killer closes test modal dialog`() = doTest("automatic") { session ->
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.SMART_NON_MODAL,
            code = """
                println("smart_non_modal should have closed the test modal dialog before this runs")
            """.trimIndent(),
            taskId = "automatic-dialog-killer",
            reason = "Trigger automatic dialog sweep via modal=smart_non_modal",
        ).assertExitCode(0)
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `dialog killer captures screenshot before closing`() = doTest("screenshot") { session ->
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = """
                val closed = closeModalDialogs()
                println("closeModalDialogs closed ${'$'}closed dialog(s)")
            """.trimIndent(),
            taskId = "dialog-killer-screenshot",
            reason = "closeModalDialogs() with screenshot verification",
        ).assertExitCode(0)
            .assertOutputContains("Screenshot saved to", message = "closeModalDialogs must capture a screenshot before closing the dialog")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `screenshot tool works in IDE`() {
        console.writeStep("Taking screenshot of IDE via execute_code")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.jonnyzzz.mcpSteroid.vision.VisionService
                import com.jonnyzzz.mcpSteroid.storage.ExecutionId

                val executionId = ExecutionId("screenshot-tool-test")
                val artifacts = VisionService.getInstance(project).capture(executionId)
                println("Screenshot captured: ${artifacts.imagePath}")
                println("Image size: ${artifacts.meta.imageSize.width}x${artifacts.meta.imageSize.height}")
                println("Component tree: ${artifacts.treePath}")
                println("Screenshot metadata: ${artifacts.metaPath}")
                println("Screenshot bytes: ${artifacts.imageBytes.size}")
            """.trimIndent(),
            taskId = "screenshot-tool-test",
            reason = "Verify VisionService.capture works in IDE",
        )

        result.assertExitCode(0)
        result.assertOutputContains("Screenshot captured:", message = "VisionService.capture must produce image")
        result.assertOutputContains("Image size:", message = "Screenshot must have dimensions")
        result.assertOutputContains("Screenshot bytes:", message = "Screenshot must have non-empty bytes")

        console.writeSuccess("Screenshot tool works")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `kills 4 nested modal dialogs deepest-first`() {
        console.writeStep("Opening 4 nested modal dialogs")
        // Open four stacked modal dialogs: the first is scheduled non-modal; each subsequent
        // one is scheduled ModalityState.any() so it runs DURING the previous modal and nests
        // on top of it -> four modal levels. modal=unleashed (no monitor) keeps them open after
        // this execution returns so the killer (next step) has all four to close.
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = $$"""
                val app = com.intellij.openapi.application.ApplicationManager.getApplication()
                withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    for (d in 1..4) {
                        val modality = if (d == 1)
                            com.intellij.openapi.application.ModalityState.nonModal()
                        else
                            com.intellij.openapi.application.ModalityState.any()
                        app.invokeLater({
                            val dialog = object : com.intellij.openapi.ui.DialogWrapper(project) {
                                init { title = "Nested Dialog $d"; setModal(true); init() }
                                override fun createCenterPanel(): javax.swing.JComponent {
                                    val panel = javax.swing.JPanel()
                                    panel.add(javax.swing.JLabel("Nested dialog $d"))
                                    return panel
                                }
                            }
                            dialog.show()
                        }, modality)
                    }
                }
                kotlinx.coroutines.delay(4000)
                println("4 nested dialogs opened")
            """.trimIndent(),
            taskId = "open-nested-dialogs",
            reason = "Open 4 nested modal dialogs",
        ).assertExitCode(0)

        val idePid = session.pid
        val nestedTitles = (1..4).map { "Nested Dialog $it" }
        val visibleNested = {
            val windows = session.windows.listWindows()
            nestedTitles.filter { t -> windows.any { it.title == t && it.pid == idePid } }
        }

        console.writeStep("Verifying 4 nested dialogs are visible")
        Assertions.assertEquals(
            nestedTitles.toSet(), visibleNested().toSet(),
            "All 4 nested modal dialogs should be visible before the killer runs",
        )
        console.writeSuccess("4 nested dialogs visible")

        console.writeStep("Running dialog killer (closes deepest-first, one-by-one)")
        session.mcpSteroid.mcpExecuteCode(
            modal = ModalMode.UNLEASHED,
            code = """
                val closed = closeModalDialogs()
                println("Nested dialog killer closed ${'$'}closed dialog(s)")
            """.trimIndent(),
            taskId = "kill-nested-dialogs",
            reason = "Kill 4 nested modal dialogs via closeModalDialogs()",
        ).assertExitCode(0)

        console.writeStep("Verifying all 4 nested dialogs are gone")
        Assertions.assertTrue(
            visibleNested().isEmpty(),
            "All nested modal dialogs should have been closed by the dialog killer; still visible: ${visibleNested()}",
        )
        console.writeSuccess("All 4 nested dialogs closed")
    }
}
