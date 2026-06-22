package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import kotlinx.serialization.json.put

class DevrigExecuteFeedbackToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
    private val beacon: DevrigBeacon,
) : ExecuteFeedbackToolHandler {
    override suspend fun handleFeedback(projectName: String, params: FeedbackParams): ToolCallResult {
        val route = routing.requireProject(projectName)
        val result = bridge.callProjectTool(route, "steroid_execute_feedback") {
            put("task_id", params.taskId)
            put("success_rating", params.successRating)
            params.explanation?.let { put("explanation", it) }
            params.code?.let { put("code", it) }
        }
        // Mirror the ij-plugin's status_score event: 0.0-1.0 rating -> 0-100 score.
        beacon.captureScore((params.successRating * 100).toInt(), context = "feedback")
        return result
    }
}
