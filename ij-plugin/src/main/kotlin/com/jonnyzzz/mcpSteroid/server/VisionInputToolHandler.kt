/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.builder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.vision.InputStep
import com.jonnyzzz.mcpSteroid.vision.VisionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Fixed allowance for delivering the non-delay steps of an input sequence (issue #309). */
private const val INPUT_DISPATCH_TIMEOUT_MS = 60_000L

class VisionInputToolHandlerIJ : VisionInputToolHandler {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handleInputSequence(projectName: String, inputParams: InputParams): ToolCallResult {
        val project = service<ProjectScopedToolHandler>().resolveProject(projectName)

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

        // Safety net for issue #309, problem 1: input dispatch must never hang the tool call
        // indefinitely (no other layer on the tools/call path bounds it). The budget covers the
        // sequence's own delay steps plus a fixed dispatch allowance.
        val delayBudgetMs = inputParams.sequence.filterIsInstance<InputStep.Delay>().sumOf { it.ms }
        val timeoutMs = delayBudgetMs + INPUT_DISPATCH_TIMEOUT_MS

        try {
            log("execution_id: ${executionId.executionId}")
            log("WARNING: Heavy endpoint. Prefer steroid_execute_code for regular automation.")
            log("Using window_id: $windowId")

            withTimeout(timeoutMs) {
                VisionService.getInstance(project).executeInput(windowId, inputParams.sequence)
            }
            log("Input sequence executed successfully.")
        } catch (e: TimeoutCancellationException) {
            // A domain error the agent must see, not a control-flow signal — caught BEFORE the
            // generic CancellationException rethrow (same pattern as ScriptExecutor.executeCodeBlocks).
            val message = "Input execution timed out after ${timeoutMs} ms — the input events could not be " +
                    "delivered to window_id $windowId (EDT busy or window not processing events)"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = "Input execution failed: ${e.message}"
            builder.addTextContent("ERROR: $message").markAsError()
            project.executionStorage.writeCodeErrorEvent(executionId, message)
        }

        return builder.build()
    }
}
