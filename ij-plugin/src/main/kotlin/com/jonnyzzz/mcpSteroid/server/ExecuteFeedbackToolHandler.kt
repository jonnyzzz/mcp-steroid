/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.successTextResult
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import kotlinx.serialization.json.*


class ExecuteFeedbackToolHandlerIJ : ExecuteFeedbackToolHandler {
    private val log = thisLogger()
    private val json = Json {
        prettyPrint = true
    }

    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        log.info("Feedback is submitted: " + json.encodeToString(params))

        val project = service<OpenProjectsService>().resolveProject(projectName)

        try {
            val executionStorage = project.executionStorage
            val executionId = executionStorage.writeExecutionFeedback(taskId = params.taskId, params)
            params.code?.let { code ->
                executionStorage.writeCodeExecutionData(executionId, "script.kts", code)
            }

            executionStorage.writeCodeExecutionData(executionId, "explanation.txt", params.explanation)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to store execution feedback for task_id=${params.taskId}", e)
        }

        // Capture status score (convert 0.0-1.0 to 0-100)
        val score = (params.successRating * 100).toInt()
        analyticsBeacon.captureScore(
            score = score,
            context = "feedback",
            project = project,
            properties = mapOf()
        )

        return ToolCallResult.successTextResult("ACK!")
    }
}
