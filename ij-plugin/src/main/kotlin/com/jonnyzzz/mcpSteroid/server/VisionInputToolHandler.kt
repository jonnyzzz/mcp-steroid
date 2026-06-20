/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.serialization.json.*

class VisionInputToolHandlerIJ : VisionInputToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val project = service<OpenProjectsService>().resolveProject(projectName)

        val executionId = project.executionStorage.writeToolCall(
            toolName = "steroid_input",
            arguments = json.encodeToJsonElement(inputParams).jsonObject,
            taskId = "input-${inputParams.taskId}"
        )
        project.executionStorage.writeCodeExecutionData(executionId, "reason.txt", inputParams.reason)

        val builder = ToolCallResult.builder()
        suspend fun log(message: String) {
            builder.addTextContent(message)
            project.executionStorage.appendExecutionEvent(executionId, message)
        }

        val windowId = inputParams.windowId

        try {
            log("execution_id: ${executionId.executionId}")
            log("WARNING: Heavy endpoint. Prefer steroid_execute_code for regular automation.")
            log("Using window_id: $windowId")

            VisionService.getInstance(project).executeInput(windowId, inputParams.sequence)
            log("Input sequence executed successfully.")
        } catch (e: Exception) {
            val message = "Input execution failed: ${e.message}"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        }

        return builder.build()
    }
}
