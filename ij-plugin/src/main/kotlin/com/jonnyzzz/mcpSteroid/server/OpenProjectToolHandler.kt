/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.GeneralSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.util.concurrency.AppExecutorUtil
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path

class OpenProjectToolHandlerIJ : OpenProjectToolHandler {
    private val logger = thisLogger()


    override suspend fun handleOpenProject(
        openProjectParams: OpenProjectParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val projectPath = Path.of(openProjectParams.projectPath).toAbsolutePath()

        // backend_name is a devrig-only routing hint. A direct in-IDE connection serves exactly one
        // backend, so there is nothing to route to — log it and ignore (defense-in-depth for forward
        // compatibility; the direct surface never advertises the parameter).
        openProjectParams.backendName?.let {
            logger.info("steroid_open_project received backend_name='$it' on a direct IDE connection; ignoring (routing applies only via devrig).")
        }

        // Check if project is already open. Lock-free (#214): getOpenProjects() is documented
        // safe outside a read action when each project is checked for isDisposed — a suspend
        // readAction here would park behind any pending write action and wedge the tool.
        val existingProject = ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .find { project ->
                project.basePath?.let { Path.of(it).toAbsolutePath().normalize() == projectPath.normalize() } == true
            }

        if (existingProject != null) {
            return ToolCallResult.builder()
                .addTextContent("Project is already open: ${existingProject.name}")
                .addTextContent("Project path: ${existingProject.basePath}")
                .addTextContent("Use steroid_list_projects to see all open projects.")
                .build()
        }

        val builder = ToolCallResult.builder()
        try {
            // Trust the project if requested
            if (openProjectParams.trustProject) {
                builder.addTextContent("Trusting project path: $projectPath")
                TrustedProjects.setProjectTrusted(projectPath, isTrusted = true)
                check(TrustedProjects.isProjectTrusted(projectPath)) {
                    "TrustedProjects did not mark path as trusted: $projectPath"
                }
                builder.addTextContent("Project path trusted successfully")
            }

            builder.addTextContent("Initiating project open: $projectPath")

            withContext(AppExecutorUtil.getAppExecutorService().asCoroutineDispatcher()) {
                val settings = GeneralSettings.getInstance()
                val originalOpenProjectMode = settings.confirmOpenNewProject
                try {
                    settings.confirmOpenNewProject = GeneralSettings.OPEN_PROJECT_NEW_WINDOW

                    val result = getInstance().loadAndOpenProject(projectPath.toString())
                    if (result != null) {
                        logger.info("Project opened successfully: ${result.name}")
                    } else {
                        logger.warn("Project opening returned null (may have been cancelled): $projectPath")
                    }
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Project opening failed: $projectPath - ${e.message}", e)
                } finally {
                    settings.confirmOpenNewProject = originalOpenProjectMode
                }
            }

            builder.addTextContent(VERIFICATION_WORKFLOW)
            if (!openProjectParams.trustProject) {
                builder.addTextContent(
                    """
                        NOTE: trust_project was false. A 'Trust Project' dialog may appear.
                              Set trust_project=true to skip the trust dialog.
                    """.trimIndent()
                )
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            val message = "Failed to initiate project open: ${e.message}"
            logger.warn(message, e)
            builder.addTextContent("ERROR: $message").markAsError()
        }

        return builder.build()
    }
}

private val VERIFICATION_WORKFLOW = """
    Project opening initiated. The process runs in the background.

    IMPORTANT: You MUST poll to verify the project is ready before using it.

    VERIFICATION WORKFLOW:
    1. Poll steroid_list_windows every 2-3 seconds until:
       - The project appears in the windows list
       - modalDialogShowing is false
       - indexingInProgress is false
       - projectInitialized is true
    2. If modalDialogShowing is true:
       - Call steroid_take_screenshot to see the dialog
       - Use steroid_input to interact with the dialog
    3. Use steroid_take_screenshot to visually confirm project is loaded
    4. Verify with steroid_list_projects that the project appears
""".trimIndent()
