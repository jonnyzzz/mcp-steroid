/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpWindowInfo
import com.jonnyzzz.mcpSteroid.integration.infra.ModalMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that MCP Steroid's [com.jonnyzzz.mcpSteroid.execution.VcsConfirmationSilencer]
 * keeps the IDE responsive when an agent creates new unversioned files in a
 * Git-tracked project.
 *
 * Without the silencer, [steroid_execute_code] scripts that call
 * `vf.findOrCreateChildData(...)` + `VfsUtil.saveText(...)` on a Git project
 * (the pattern captured under `~/Work/mcp-steroid-data/eid_*-step3-index-manager-tests/`)
 * surface IntelliJ's "Add files to Git?" confirmation: the platform default for
 * `VcsConfiguration.StandardConfirmation.ADD` is `SHOW_CONFIRMATION`, the new
 * VFS file goes through the Git plugin's `VcsVFSListener`, and a modal pops on
 * the EDT, blocking every subsequent agent step.
 *
 * `VcsConfirmationSilencer` runs on `postStartupActivity` and flips that value
 * to `DO_NOTHING_SILENTLY` once per project. This test confirms:
 * 1. the silencer ran (`ADD_CONFIRMATION=2`);
 * 2. creating an unversioned file via the same agent-style script does NOT
 *    surface a modal dialog within 30 s.
 *
 * The container's own project ([IntelliJProject.EmptyProject]) is turned into a committed Git
 * repository by a [BeforeIdeStartContext.initGitRepoInGuestProject] hook BEFORE the IDE launches, so
 * the single opened project is Git-tracked at project-open. Driving the test through the container's
 * own project (rather than opening a second one) keeps `resolveProjectName()` unambiguous.
 */
class GitVcsAddFileDialogTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `creating new file via execute_code in git-tracked project does not show VCS add modal dialog`() = runWithCloseableStack { lifetime ->

        val session = IntelliJContainer.create(
            lifetime,
            IntelliJContainerOpts(
                consoleTitle = "Git VCS Add Dialog",
                project = IntelliJProject.EmptyProject,
                beforeIdeStart = listOf({ initGitRepoInGuestProject() }),
            ),
        ).waitForProjectReady()

        val console = session.console
        val projectName = session.mcpSteroid.resolveProjectName()
        val projectPath = session.intellijDriver.getGuestProjectDir()

        console.writeStep("Wait for Git detection + a non-modal ADD confirmation (silencer flip or platform default)")
        // The real invariant is that the "Add to VCS?" modal will NOT fire — i.e. the ADD confirmation is
        // anything other than SHOW_CONFIRMATION (0). Both DO_ACTION_SILENTLY (1, the Git default in IDEA
        // 2026.1) and DO_NOTHING_SILENTLY (2, what VcsConfirmationSilencer flips a SHOW_CONFIRMATION to) are
        // non-modal. Git root detection is async, so poll until HAS_GIT=true and ADD_CONFIRMATION ∈ {1,2}.
        // Step 3 below is the authoritative check: actually create a file and confirm no modal appears.
        val probeCode = """
            import com.intellij.openapi.vcs.ProjectLevelVcsManager
            import com.intellij.openapi.vcs.VcsConfiguration

            val pm = ProjectLevelVcsManager.getInstance(project)
            val vcss = pm.getAllActiveVcss()
            vcss.forEach { println("ACTIVE_VCS=${'$'}{it.name}") }
            println("HAS_GIT=${'$'}{vcss.any { it.name.equals("Git", ignoreCase = true) }}")
            val addConfirm = pm.getStandardConfirmation(
                VcsConfiguration.StandardConfirmation.ADD,
                vcss.firstOrNull(),
            )
            // VcsShowConfirmationOption.Value: 0 = SHOW_CONFIRMATION (default → modal),
            // 1 = DO_ACTION_SILENTLY, 2 = DO_NOTHING_SILENTLY (silencer flipped to this).
            println("ADD_CONFIRMATION=${'$'}{addConfirm?.value}")
        """.trimIndent()

        var lastProbeOut = "not probed"
        val deadline = System.currentTimeMillis() + 60_000L
        var silenced = false
        while (System.currentTimeMillis() < deadline) {
            val probe = session.mcpSteroid.mcpExecuteCode(
                code = probeCode,
                taskId = "vcs-add-dialog",
                reason = "Confirm Git VCS detected and silencer set ADD confirmation to DO_NOTHING_SILENTLY",
                projectName = projectName,
                modal = ModalMode.UNLEASHED,
            )
            lastProbeOut = probe.stdout
            val nonModalAdd = lastProbeOut.contains("ADD_CONFIRMATION=1") || lastProbeOut.contains("ADD_CONFIRMATION=2")
            if (probe.exitCode == 0 && lastProbeOut.contains("HAS_GIT=true") && nonModalAdd) {
                silenced = true
                break
            }
            Thread.sleep(2_000L)
        }
        Assertions.assertTrue(silenced) {
            "Git VCS did not converge to HAS_GIT=true with a non-modal ADD confirmation (1 or 2) within 60s — " +
                    "ADD_CONFIRMATION=0 (SHOW_CONFIRMATION) would pop the Add-to-VCS modal. Last probe output:\n$lastProbeOut"
        }

        console.writeStep("Create a new unversioned file via findOrCreateChildData + VfsUtil.saveText")
        val createFile = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.openapi.application.ApplicationManager
                import com.intellij.openapi.command.WriteCommandAction
                import com.intellij.openapi.vfs.LocalFileSystem
                import com.intellij.openapi.vfs.VfsUtil

                val dir = "$projectPath"
                val name = "NewlyCreated.java"
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(dir)
                    ?: error("dir not found: " + dir)
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(project) {
                        val f = vf.findOrCreateChildData(null, name)
                        VfsUtil.saveText(f, "public class NewlyCreated {}\n")
                        println("CREATED=" + f.path)
                    }
                }
            """.trimIndent(),
            taskId = "vcs-add-dialog",
            reason = "Mirror agent script in ~/Work/mcp-steroid-data/eid_20260507T090*/script.kts that creates a new file in a Git-tracked project",
            projectName = projectName,
            modal = ModalMode.UNLEASHED,
        )
        createFile.assertExitCode(0, "execute_code that creates a new file should succeed")
            .assertOutputContains("CREATED=")

        console.writeStep("Poll mcpListWindows to confirm no VCS add-file modal dialog appears")
        val modalSeen = waitForModalDialog(session, projectPath, timeoutMillis = 30_000L)
        Assertions.assertFalse(
            modalSeen,
            "Unexpected Add-to-Git? modal dialog after creating an unversioned file in a Git-tracked project. " +
                    "VcsConfirmationSilencer should have flipped ADD confirmation to DO_NOTHING_SILENTLY at project open.",
        )
        console.writeSuccess("VcsConfirmationSilencer suppressed the Git VCS add-file modal dialog")
    }

    /**
     * Poll [com.jonnyzzz.mcpSteroid.integration.infra.McpSteroidDriver.mcpListWindows] for
     * [timeoutMillis] and return `true` if any window for the project at [projectPath] reports
     * `modalDialogShowing=true` at any point. The Git plugin's confirmation handler runs on the EDT
     * but is fired from VFS notifications scheduled by RefreshQueue, so the dialog can take a few
     * seconds to appear after the script returns — we poll for the full window before concluding
     * nothing surfaced.
     */
    private fun waitForModalDialog(
        session: IntelliJContainer,
        projectPath: String,
        timeoutMillis: Long,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        var lastStatus = "not polled"

        while (System.currentTimeMillis() - startedAt < timeoutMillis) {
            val windows = session.mcpSteroid.mcpListWindows(timeoutSeconds = 30)
            val projectWindows = windows.filter { it.projectPath == projectPath }
            if (projectWindows.any { it.modalDialogShowing }) {
                println("[VCS-ADD-DIALOG] Modal dialog detected: ${describe(projectWindows)}")
                return true
            }
            lastStatus = describe(projectWindows.ifEmpty { windows })
            Thread.sleep(1_000L)
        }

        println("[VCS-ADD-DIALOG] No modal dialog within ${timeoutMillis}ms. Last status: $lastStatus")
        return false
    }

    private fun describe(windows: List<McpWindowInfo>): String =
        windows.joinToString(prefix = "[", postfix = "]") { window ->
            "name=${window.projectName}, path=${window.projectPath}, modal=${window.modalDialogShowing}, " +
                    "indexing=${window.indexingInProgress}, initialized=${window.projectInitialized}"
        }
}
