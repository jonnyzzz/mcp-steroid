/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallParams
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExecutionBackendProvenanceTest {
    @Test
    fun `direct callers cannot spoof storage provenance`() {
        val spoofedArguments = buildJsonObject {
            put(EXECUTION_BACKEND_KIND_ARGUMENT, "d")
            put(EXECUTION_BACKEND_NAME_ARGUMENT, "spoofed-backend")
        }
        val context = ToolCallContext(
            params = ToolCallParams(name = "steroid_execute_code", arguments = spoofedArguments),
            session = McpSession(),
            mcpProgressReporter = NoOpProgressReporter,
        )

        assertNull(context.executionBackendProvenance())
    }

    @Test
    fun `trusted bridge arguments parse backend kind and full name`() {
        val trustedArguments = buildJsonObject {
            put(EXECUTION_BACKEND_KIND_ARGUMENT, "d")
            put(EXECUTION_BACKEND_NAME_ARGUMENT, "iu-47qi79c1")
        }
        val context = ToolCallContext(
            params = ToolCallParams(
                name = "steroid_execute_code",
                trustedArguments = trustedArguments,
            ),
            session = McpSession(),
            mcpProgressReporter = NoOpProgressReporter,
        )

        assertEquals(
            ExecutionBackendProvenance(kind = 'd', name = "iu-47qi79c1"),
            context.executionBackendProvenance(),
        )
    }

    @Test
    fun `partial hidden provenance fails fast`() {
        val arguments = buildJsonObject { put(EXECUTION_BACKEND_KIND_ARGUMENT, "d") }

        assertThrows(IllegalArgumentException::class.java) { arguments.executionBackendProvenance() }
    }

    @Test
    fun `storage provenance is transient in execution params JSON`() {
        val params = ExecCodeParams(
            taskId = "task",
            code = "println(1)",
            reason = "test",
            timeout = 30,
            executionBackend = ExecutionBackendProvenance(kind = 'd', name = "iu-47qi79c1"),
        )

        val json = McpJson.encodeToString(ExecCodeParams.serializer(), params)
        assertFalse(json.contains("executionBackend"), json)
        assertFalse(json.contains(EXECUTION_BACKEND_NAME_ARGUMENT), json)
        assertFalse(json.contains(EXECUTION_BACKEND_KIND_ARGUMENT), json)
    }
}
