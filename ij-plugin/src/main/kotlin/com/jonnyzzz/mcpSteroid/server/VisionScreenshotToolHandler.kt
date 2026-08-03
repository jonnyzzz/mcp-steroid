/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.serialization.json.*
import java.util.*

class VisionScreenshotToolHandlerIJ : VisionScreenshotToolHandler {
    private val log = thisLogger()
    private val json = Json { encodeDefaults = true }

    override suspend fun screenshotWindow(
        projectName: String,
        screenshotParams: ScreenshotParams,
        mcpProgressReporter: McpProgressReporter
    ): ToolCallResult {
        val taskId = screenshotParams.taskId
        val reason = screenshotParams.reason

        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_take_screenshot",
            arguments = json.encodeToJsonElement(screenshotParams).jsonObject,
            taskId = taskId,
            executionBackend = screenshotParams.executionBackend,
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", reason)

        val builder = ToolCallResult.builder()

        try {
            val artifacts = VisionService.getInstance(project).capture(executionId, screenshotParams.windowId)
            val imageBase64 = Base64.getEncoder().encodeToString(artifacts.imageBytes)
            builder.addContent(ContentItem.Image(data = imageBase64, mimeType = "image/png"))

            artifacts.logMessages().forEach {
                log.info("Captured window artifact: $it")
                builder.addTextContent(it)
            }
        } catch (e: Exception) {
            val message = "Screenshot capture failed: ${e.message}"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        }

        return builder.build()
    }
}
