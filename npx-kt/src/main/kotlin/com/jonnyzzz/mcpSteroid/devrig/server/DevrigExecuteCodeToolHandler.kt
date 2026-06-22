package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.devrig.BackendVersionSkew
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import kotlinx.serialization.json.put

class DevrigExecuteCodeToolHandler(
    private val bridge: DevrigToolBridgeClient,
    private val routing: DevrigProjectRoutingService,
    private val beacon: DevrigBeacon,
) : ExecuteCodeToolHandler {
    override suspend fun executeCode(
        projectName: String,
        execCodeParams: ExecCodeParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult {
        val route = routing.requireProject(projectName)
        // Version-base skew check on every routed exec_code call (devrig scenario only; stderr).
        BackendVersionSkew.warnOnExecCode(pid = route.route.pid, pluginVersion = route.route.plugin.version)
        val result = bridge.callProjectTool(route, "steroid_execute_code", callProgress) {
            put("code", execCodeParams.code)
            put("task_id", execCodeParams.taskId)
            put("reason", execCodeParams.reason)
            put("timeout", execCodeParams.timeout)
            put("modal", execCodeParams.modal.wire)
        }
        beacon.capture("exec_code", mapOf("result" to if (result.isError) "error" else "success"))
        return result
    }
}
