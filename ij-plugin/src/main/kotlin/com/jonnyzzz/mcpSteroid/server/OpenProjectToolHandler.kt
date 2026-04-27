/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.ide.GeneralSettings
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.ProjectManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * Handler for the steroid_open_project MCP tool.
 *
 * This tool initiates opening a project in IntelliJ. It does NOT wait for the project
 * to fully open - instead it returns quickly so the client can interact with any dialogs
 * that may appear (such as the trust project dialog) using screenshot/input tools.
 *
 * The tool can optionally trust the project path before opening, which allows skipping
 * the trust dialog.
 */
class OpenProjectToolHandler : McpRegistrar {
    private val logger = thisLogger()
    private val openProjectLock = Any()
    private val toolDescription = """
        Open a project in the IDE. This tool initiates the project opening process and returns quickly.

        IMPORTANT: Project opening is ASYNCHRONOUS. This tool returns immediately; you MUST poll to verify the project is fully ready before using it.

        Verification Workflow:
        1. Call steroid_open_project with the project path
        2. Poll steroid_list_windows repeatedly (every 2-3 seconds) until:
           - The project appears in the windows list
           - modalDialogShowing is false (no dialogs blocking)
           - indexingInProgress is false (indexing complete)
           - projectInitialized is true
        3. If modalDialogShowing is true, use steroid_take_screenshot + steroid_input to handle dialogs
        4. Use steroid_take_screenshot to visually confirm the project is fully loaded
        5. Verify with steroid_list_projects that the project appears

        Dialog Handling:
        - If trust_project=true (default), the trust dialog is skipped automatically
        - Other dialogs (project type, SDK selection, etc.) may still appear
        - Always check modalDialogShowing in steroid_list_windows response
    """.trimIndent()

    override fun register(server: McpServerCore) {
        server.toolRegistry.registerTool(
            name = "steroid_open_project",
            description = toolDescription,
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("project_path") {
                        put("type", "string")
                        put("description", "Absolute path to the project directory to open.")
                    }
                    putJsonObject("task_id") {
                        put("type", "string")
                        put("description", "Your task identifier to group related executions.")
                    }
                    putJsonObject("reason") {
                        put("type", "string")
                        put("description", "Reason for opening the project. Required for audit logs.")
                    }
                    putJsonObject("trust_project") {
                        put("type", "boolean")
                        put("description", "If true, trust the project path before opening (skips trust dialog). Default: true")
                    }
                }
                putJsonArray("required") {
                    add("project_path")
                    add("task_id")
                    add("reason")
                }
            },
            ::handle
        )
    }

    private suspend fun handle(context: ToolCallContext): ToolCallResult {
        val args = context.params.arguments ?: return errorResult("Missing arguments")
        val projectPathStr = args["project_path"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: project_path")
        args["task_id"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: task_id")
        args["reason"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Missing required parameter: reason")
        val trustProject = args["trust_project"]?.jsonPrimitive?.boolean ?: true

        val requestedProjectPath = try {
            Path.of(projectPathStr).toAbsolutePath().normalize()
        } catch (e: Exception) {
            logger.warn("Invalid project path: $projectPathStr", e)
            return errorResult("Invalid project path: $projectPathStr - ${e.message}")
        }

        // Validate that the path exists
        if (!Files.isDirectory(requestedProjectPath)) {
            return errorResult("Project path is not a directory: $requestedProjectPath")
        }

        val projectPath = try {
            requestedProjectPath.toRealPath()
        } catch (e: Exception) {
            logger.warn("Failed to resolve project path: $requestedProjectPath", e)
            return errorResult("Failed to resolve project path: $requestedProjectPath - ${e.message}")
        }

        // Check if project is already open
        val existingProject = readAction {
            ProjectManager.getInstance().openProjects.find { project ->
                project.basePath?.let { Path.of(it).toAbsolutePath().normalize() == projectPath } == true
            }
        }

        if (existingProject != null) {
            return ToolCallResult.builder()
                .addTextContent("Project is already open: ${existingProject.name}")
                .addTextContent("Project path: ${existingProject.basePath}")
                .addTextContent("Use steroid_list_projects to see all open projects.")
                .build()
        }

        val builder = ToolCallResult.builder()
        fun log(message: String) {
            builder.addTextContent(message)
            context.mcpProgressReporter.report(message)
        }

        try {
            // Trust the project if requested
            if (trustProject) {
                log("Trusting project path: $projectPath")
                TrustedProjects.setProjectTrusted(projectPath, isTrusted = true)
                check(TrustedProjects.isProjectTrusted(projectPath)) {
                    "TrustedProjects did not mark path as trusted: $projectPath"
                }
                log("Project path trusted successfully")
            }

            log("Initiating project open: $projectPath")

            scheduleProjectOpen(projectPath)

            log("Project opening initiated. The process runs in the background.")
            log("")
            log("IMPORTANT: You MUST poll to verify the project is ready before using it.")
            log("")
            log("VERIFICATION WORKFLOW:")
            log("1. Poll steroid_list_windows every 2-3 seconds until:")
            log("   - The project appears in the windows list")
            log("   - modalDialogShowing is false")
            log("   - indexingInProgress is false")
            log("   - projectInitialized is true")
            log("2. If modalDialogShowing is true:")
            log("   - Call steroid_take_screenshot to see the dialog")
            log("   - Use steroid_input to interact with the dialog")
            log("3. Use steroid_take_screenshot to visually confirm project is loaded")
            log("4. Verify with steroid_list_projects that the project appears")
            log("")
            if (!trustProject) {
                log("NOTE: trust_project was false. A 'Trust Project' dialog may appear.")
                log("      Set trust_project=true to skip the trust dialog.")
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

    private fun scheduleProjectOpen(projectPath: Path) {
        ApplicationManager.getApplication().executeOnPooledThread {
            synchronized(openProjectLock) {
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
        }
    }

    private fun errorResult(message: String) = ToolCallResult(
        content = listOf(ContentItem.Text(text = "ERROR: $message")),
        isError = true
    )
}
