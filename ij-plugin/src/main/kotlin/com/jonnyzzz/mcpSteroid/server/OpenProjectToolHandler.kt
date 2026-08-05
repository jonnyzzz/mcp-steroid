/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.GeneralSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.concurrency.AppExecutorUtil
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
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

        // Check if project is already open
        val existingProject = run { // #214: no read action — must not park behind a pending write (wedges every tool)
            ProjectManager.getInstance().openProjects.find { project ->
                project.basePath?.let { Path.of(it).toAbsolutePath().normalize() == projectPath.normalize() } == true
            }
        }

        if (existingProject != null) {
            return ToolCallResult.builder()
                .addTextContent("Project is already open: ${existingProject.name}")
                .addTextContent("Project path: ${existingProject.basePath}")
                .addTextContent("Use the project-listing tool or command to see all open projects.")
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

                    val result = ProjectManager.getInstance().loadAndOpenProject(projectPath.toString())
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

            builder.addTextContent(OPEN_PROJECT_VERIFICATION_WORKFLOW)
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
